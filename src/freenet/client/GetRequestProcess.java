package freenet.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import freenet.Core;
import freenet.Key;
import freenet.KeyException;
import freenet.client.events.DataNotFoundEvent;
import freenet.client.events.DocumentNotValidEvent;
import freenet.client.events.ErrorEvent;
import freenet.client.events.ExceptionEvent;
import freenet.client.events.RedirectFollowedEvent;
import freenet.client.events.StateReachedEvent;
import freenet.client.listeners.CollectingEventListener;
import freenet.client.listeners.DoneListener;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataPart;
import freenet.client.metadata.MetadataSettings;
import freenet.support.ArrayBucket;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.FileBucket;
import freenet.support.Logger;
import freenet.support.TempBucketFactory;
import freenet.support.io.LimitedInputStream;

/**
 * Handles a series of requests to fill a bucket by traversing down metadata
 * redirections.
 * 
 * @author oskar
 */
public class GetRequestProcess extends ControlRequestProcess {

	private Bucket metadataBucket;

	private CollectingEventListener cel;

	public GetRequestProcess(
		FreenetURI uri,
		int htl,
		Bucket data,
		BucketFactory ptBuckets,
		int recursionLevel,
		boolean follow,
		MetadataSettings msettings) {
		super(uri, htl, data, ptBuckets, recursionLevel, follow, msettings);
	}

	public GetRequestProcess(
		FreenetURI uri,
		int htl,
		Bucket data,
		BucketFactory ptBuckets,
		int recursionLevel,
		MetadataSettings msettings) {
		super(uri, htl, data, ptBuckets, recursionLevel, true, msettings);
	}

	public synchronized Request getNextRequest() {
		return this.getNextRequest(msettings.getFollowContainers());
		// TODO: Put it in the msettings
	}

	public synchronized Request getNextRequest(boolean followContainer) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"In getNextRequest() for " + this,
				Logger.DEBUG);
		if (aborted || failed) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Failed or aborted: " + this,
					Logger.DEBUG);
			return null;
		}
		if (dl == null) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Creating first request in getNextRequest for " + this,
					Logger.DEBUG);
			metadataBucket = new ArrayBucket();
			//System.err.println("LALA REQUESTING URI: " + uri);

			int msz = msettings.getMaxLog2Size();
			if (msz > 0) {
				ClientKey ckey;
				try {
					ckey = AbstractClientKey.createFromRequestURI(uri);
				} catch (KeyException e) {
					error = "Could not parse key: " + e;
					origThrowable = e;
					failed = true;
					return null;
				}
				Key key = ckey.getKey();
				if (key.log2size() > msz) {
					error = "Key too large for specified maxsize";
					origThrowable = new RequestSizeException(error);
					failed = true;
					return null;
				}
			}
			r =
				new GetRequest(
					htl,
					uri,
					metadataBucket,
					data,
					msettings.isNonLocal());
			dl = new MyListener();
			if (Core.logger.shouldLog(Logger.NORMAL, this))
				cel = new CollectingEventListener(16);
			r.addEventListener(dl);
			if (cel != null)
				r.addEventListener(cel);
			return r;
		} else {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Not first request for " + this,
					Logger.DEBUG);
			if (!nextLevel) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Waiting for completion for " + this,
						Logger.DEBUG);
				dl.strongWait();
				if (logDEBUG)
					Core.logger.log(
						this,
						"Completed for " + this,
						Logger.DEBUG);
				//if(aborted) return null; - this would leave a Client
				// running...
				nextLevel = true;
				if (r.state() == Request.DONE) {
					try {
						//System.err.println("QUACK: " + msettings);
						if (logDEBUG)
							Core.logger.log(
								this,
								"Raw Metadata:\n" + metadataBucket.toString(),
								Logger.DEBUG);
						metadata =
							new Metadata(
								metadataBucket.getInputStream(),
								msettings);
						if (logDEBUG)
							Core.logger.log(
								this,
								"Processed Metadata for "
									+ this
									+ ":\n"
									+ metadata.writeString(),
								Logger.DEBUG);

						// Check for a JAR archive - do this by mime type
						String mimeType = metadata.getMimeType(null);
						if (mimeType != null && followContainer) {
							if (mimeType
								.equalsIgnoreCase("application/x-java-archive")
								|| mimeType.equalsIgnoreCase("application/zip")) {
								// 				System.err.println("Handling ZIP");
								if (handleZip())
									return null;
							}
						}

						// Extract the checksum CHK from the info part.
						// If more than one value is specified, we
						// favor the one that is closest to the end
						// of the redirect chain.
						//
						// I did this so that people can't create
						// redirects with "corrected" checksum values.
						// --gj
						String checksum = metadata.getChecksum(null);
						if (checksum != null) {
							msettings.setChecksum(checksum);
						}
						//System.err.println("LALA GOT MD: " + metadata);
						if (follow) {
						    if(logDEBUG)
						        Core.logger.log(this, "Will follow: "+this, Logger.DEBUG);
							String mds = uri.getMetaString();
							DocumentCommand d =
								(mds == null
									? null
									: metadata.getDocument(mds));
							FreenetURI nuri;
							// 			    System.err.println("d = "+d+", mds = "+mds);
							if (d != null) {
								nuri = uri.popMetaString();
							} else {
								d = metadata.getDefaultDocument();
								nuri = uri;
							}
							if (d != null) {
							    if(logDEBUG)
							        Core.logger.log(this, "d="+d+" - redirecting", Logger.DEBUG);
							    MetadataPart p = d.getControlPart();
							    
							    // Can return null, in which case we are finished.
								next =
									d.getGetProcess(
										nuri,
										htl,
										data,
										ptBuckets,
										recursionLevel,
										msettings);
								if(next != null) {
								    if(p == null)
								        Core.logger.log(this, "p = null for "+d+
								                " from "+metadata+": "+mds+" on "+
								                this, Logger.ERROR);
								    r.produceEvent(new RedirectFollowedEvent(p));
								}
							}
							
							if(logDEBUG)
							    Core.logger.log(this, "d = "+d, Logger.DEBUG);

							if (next == null && mds != null) {
								// there was a metastring left, and
								// there are no more control documents
								// to apply it to.
								// 				System.err.println("Key not found in
								// manifest");
								error = "Key not found in manifest";
								origThrowable = new KeyNotInManifestException();
								failed = true;
								return null;
							}
						} else {
						    if(logDEBUG)
						        Core.logger.log(this, "Not following... "+this, Logger.DEBUG);
						}
						// 			System.err.println("Finishing");
					} catch (IOException e) {
						return null;
					} catch (InvalidPartException e) {
						return null;
					}
				} else {
					failed = true;
					if (error != null && error.length()!=0) {
						if (aborted) {
							error = "Request for " + getURI() + " aborted!";
						} else {
							error = "Request for " + getURI() + " failed.";
						}
					}

					if (origThrowable != null)
						error += ": " + origThrowable;

					if (cel != null) {
						Vector v = cel.asVector();
						if (v.size() > 0) {
							int x = v.size() - 1;
							boolean explained = false;
							while (x >= 0) {
								ClientEvent ce = (ClientEvent) (v.elementAt(x));
								x--;
								if (Core
									.logger
									.shouldLog(Logger.MINOR, this)) {
									Core.logger.log(
										this,
										"Event: "
											+ ce.getDescription()
											+ " for "
											+ this
											+ " before failure",
										Logger.MINOR);
								}
								if ((!explained)
									&& !(ce instanceof StateReachedEvent)) {
									explained = true;
									error += ": " + ce.getDescription();
									if (ce instanceof ExceptionEvent)
										origThrowable =
											((ExceptionEvent) ce)
												.getException();
								}
							}
						}
					}

					if (origThrowable == null)
						origThrowable =
							new WrongStateException(
								error,
								Request.DONE,
								r.state());

					return null;
				}
			}
			// 	    System.err.println("Still finishing");
			if (next != null) {
				// 		System.err.println("Almost finished");
				Request rr = next.getNextRequest();
				failed = next.failed();
				if (failed) {
					if (next.getThrowable() == null && next.getError() == null)
						Core.logger.log(
							this,
							"GRRRR! Both getThrowable "
								+ "AND getError return null on "
								+ next
								+ " for "
								+ this
								+ ": PLEASE REPORT TO "
								+ "devl@freenetproject.org",
							Logger.ERROR);
					error =
						getNextFailedErrorString(
							next.getThrowable(),
							next.getError());
					origThrowable = next.getThrowable();
				}
				return rr;
			} else
				return null;
		}
	}

	/**
	 * @return true to quit now
	 */
	protected boolean handleZip() throws IOException, InvalidPartException {
		// FIXME: this is crap
		// FIXME: this is slow
		// FIXME: the other version CRASHES THE JVM (1.4.1_04, 1.4.2)
		String containerFile = uri.getMetaString();
		boolean notFound = false;
		// follow the jar
		// Check if we need to de-container this file
		// FIXME: cache jar's
		// 	System.err.println("Handling zip("+containerFile+")");
		if (containerFile != null) {
			if (data.size() > 1024 * 1024) {
				// 		System.err.println("Too big");
				error = "This container is too large for me to process.";
				origThrowable = new KeyNotInManifestException();
				failed = true;
				return true;
			}

			Bucket newData = new FileBucket();
			metadataBucket = new FileBucket();

			InputStream myIs = data.getInputStream();
			JarInputStream myJis = new JarInputStream(myIs);
			String containerMeta = "metadata";
			// because we don't have access to a File object,
			// we have to skip through until we hit our filename...
			JarEntry ent = null;
			boolean done = false;
			boolean metadone = false;
			boolean metafound = false;
			byte[] buffer = new byte[16384];
			do {
				try {
					ent = myJis.getNextJarEntry();
					if (ent == null) {
						// 			System.err.println("No more entries");
						if (!done) {
							// No file of this name
							// 			    System.err.println("No key of this name");
							notFound = true;
							break;
						} else {
							// no metadata
							metadone = true;
						}
					} else if (ent.getName().equalsIgnoreCase(containerFile)) {
						BufferedOutputStream myOs =
							new BufferedOutputStream(newData.getOutputStream());
						BufferedInputStream mybj =
							new BufferedInputStream(myJis);
						LimitedInputStream lis =
							new LimitedInputStream(mybj, 1024 * 1024, true);
						int tt;
						do {
							tt = lis.read(buffer);
							if (tt == -1) {
								done = true;
							} else {
								myOs.write(buffer, 0, tt);
							}
							myOs.flush();
						} while (!done);
					} else if (ent.getName().equalsIgnoreCase(containerMeta)) {
						BufferedOutputStream myOs =
							new BufferedOutputStream(
								metadataBucket.getOutputStream());
						BufferedInputStream mybj =
							new BufferedInputStream(myJis);
						LimitedInputStream lis =
							new LimitedInputStream(mybj, 1024 * 1024, true);
						int tt;
						do {
							tt = lis.read(buffer);
							if (tt == -1) {
								metadone = metafound = true;
							} else {
								myOs.write(buffer, 0, tt);
							}
							myOs.flush();
						} while (!metadone);
					}
				} catch (ZipException e) {
					notFound = true;
					break;
				}
			}
			while (!done || !metadone);
			metadata = new Metadata(metadataBucket.getInputStream(), msettings);
			// 	    System.err.println("After handling zip, metadata=\n"+metadata);
			if (notFound && (metadata.getDocument(containerFile) == null)) {
				error = "Key not found in JAR Container";
				origThrowable = new KeyNotInManifestException();
				failed = true;
				return true;
			} // otherwise it's just metadata which can be handled...

			if (metafound) {
				// 		System.err.println("Found metadata");
				// check that this document exists in the metadata
				// and ignore the metadata if it doens't, to prevent
				// big scary monsters eating you and, more importantly,
				// throwing a key not in metadata exception
				//
				// 
				// 		System.err.println("metadata currently:\n"+metadata);
				DocumentCommand dc = metadata.getDocument(containerFile);
				if (dc != null) {
					// 		    System.err.println("dc: "+dc);
					metadata = new Metadata(msettings);
					dc.setName("");
					metadata.addDocument(dc);
					// 		    System.err.println("metadata:\n"+metadata);
				}
			}
			data.resetWrite();
			BucketTools.copy(newData, data);
			uri = uri.popMetaString();
		}
		return false;
	}

	/**
	 * Faster version of above. Crashes Sun 1.4.1_04 and 1.4.2. :(
	 * 
	 * @return true to quit now
	 */
	protected boolean fastHandleZip()
		throws IOException, InvalidPartException {
		String containerFile = uri.getMetaString();
		// follow the jar
		// Check if we need to de-container this file
		// FIXME: cache jar's
		if (containerFile != null) {
			if (data.size() > 1024 * 1024) {
				error = "This container is too large for me to process.";
				origThrowable = new KeyNotInManifestException();
				failed = true;
				return true;
			}

			// Write it to a temp file
			// ZipInputStream is really inefficient

			TempBucketFactory factory = new TempBucketFactory();
			boolean needsDeallocBucket = false;

			FileBucket fileData;
			if (data instanceof FileBucket) {
				fileData = (FileBucket) data;
			} else {
				fileData = factory.makeBucket(data.size(), 2.0F, 1);
				needsDeallocBucket = true;
				BucketTools.copy(data, fileData);
			}

			try {
				ZipFile zf = new ZipFile(fileData.getFile());

				String containerMeta = "metadata";

				ZipEntry metadataEntry = zf.getEntry(containerMeta);

				if (metadataEntry != null) {
					long metaSize = metadataEntry.getSize();
					if (metaSize > 1024 * 1024 /* !! */
						) {
						error =
							"This container is too large for me to process.";
						origThrowable = new KeyNotInManifestException();
						failed = true;
						return true;
						// >1MB of metadata is insane
					}

					InputStream metadataStream =
						zf.getInputStream(metadataEntry);

					metadataStream =
						new LimitedInputStream(
							metadataStream,
							1024 * 1024,
							false);
					// FIXME: arbitrary hardcoded value

					metadata = new Metadata(metadataStream, msettings);
				}

				ZipEntry dataEntry = zf.getEntry(containerFile);
				if (dataEntry != null) {
					InputStream is = zf.getInputStream(dataEntry);
					data.resetWrite();
					OutputStream os = data.getOutputStream();
					byte[] buffer = new byte[16384];
					long copied = 0;
					while (copied < (1024 * 1024) /* FIXME! */
						) {
						int moved = is.read(buffer);
						if (moved <= 0)
							break;
						copied += moved;
						os.write(buffer, 0, moved);
					}
					if (copied >= (1024 * 1024)) {
						error =
							"This contained file is too large for me to "
								+ "process.";
						origThrowable = new KeyNotInManifestException();
						failed = true;
						return true;
					}
					if (metadataEntry != null) {
						// check that this document exists in the metadata
						// and ignore the metadata if it doens't, to prevent
						// big scary monsters eating you and, more importantly,
						// throwing a key not in metadata exception
						//
						// 
						DocumentCommand dc =
							metadata.getDocument(containerFile);
						metadata = new Metadata(msettings);
						dc.setName("");
						metadata.addDocument(dc);
					}
				} else {
					error = "The key was not found in the ZIP container";
					origThrowable = new KeyNotInManifestException();
					failed = true;
					return true;
				}
				uri = uri.popMetaString();
			} finally {
				if (needsDeallocBucket) {
					factory.freeBucket(fileData);
				}
			}
		} else
			return true;
		return false;
	}

	class MyListener extends DoneListener {
		public void receive(ClientEvent ce) {
			if (ce instanceof ExceptionEvent) {
				origThrowable = ((ExceptionEvent) ce).getException();
				// This is usually quite serious, so log at NORMAL
				if (Core.logger.shouldLog(Logger.NORMAL, this))
					Core.logger.log(
						this,
						GetRequestProcess.this.toString()
							+ ": "
							+ ce.getDescription(),
						origThrowable,
						Logger.NORMAL);
			} else if (ce instanceof ErrorEvent) {
				error = ce.getDescription();
				Core.logger.log(
					this,
					GetRequestProcess.this.toString()
						+ ": "
						+ ce.getDescription(),
					Logger.NORMAL);
			} else if (ce instanceof DocumentNotValidEvent) {
				origThrowable = ((DocumentNotValidEvent) ce).getDNV();
				if (Core.logger.shouldLog(Logger.MINOR, this))
					Core.logger.log(
						this,
						GetRequestProcess.this.toString()
							+ ": "
							+ ce.getDescription(),
						origThrowable,
						Logger.MINOR);
			} else if (ce instanceof DataNotFoundEvent) {
				cel = null;
			} // RouteNotFound DOES trigger an event dump
			super.receive(ce);
		}
	}
}
