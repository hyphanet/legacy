package freenet.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import freenet.Core;
import freenet.client.events.BlockQueuedEvent;
import freenet.client.events.CollisionEvent;
import freenet.client.events.ExceptionEvent;
import freenet.client.events.SegmentEncodedEvent;
import freenet.client.events.SegmentEncodingEvent;
import freenet.client.events.SegmentInsertFinishedEvent;
import freenet.client.events.SegmentInsertStartedEvent;
import freenet.client.events.SplitFileEvent;
import freenet.client.events.SplitFileStartedEvent;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.SplitFile;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.SegmentHeader;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.NullBucket;

/**
 * Helper class handles segmenting, encoding and inserting SplitFiles blocks.
 * <p>
 * It also creates, <em>but does not insert</em> the SplitFile metadata.
 * Insertion of the metadata is handled by Oskars metadata handling framework.
 * See PutRequestProcess.
 * </p>
 * 
 * @author giannij
 */
class SplitFileInsertManager extends RequestManager {

	// REDFLAG: check with oskar/scott.
	// Is this what we should be using? Make it explict in
	// the SF metadata?
	private final static String BLOCK_CIPHER = "Twofish";

	SplitFilePutRequest request;
	private boolean postedFinished = false;

	String[] blockCHKs = null;
	String[] checkCHKs = null;

	SplitFileInsertManager(SplitFilePutRequest req) {
		super(
			req.sf,
			req.defaultHtl,
			0,
			req.defaultRetries,
			req.maxThreads,
			false,
			req.bf);
		this.request = req;

		// REDFLAG: Didn't I do this somewhere else???
		// IMPORTANT:
		// So that the buckets made by BucketTools.splitFile
		// will be freed correctly.
		this.bf = new BucketTools.BucketFactoryWrapper(req.bf);

		if (request.srcBucket.size() < 1) {
			throw new IllegalArgumentException("req.srcBucket is empty!");
		}
	}

	// I don't push this into the base because I don't want to have to cast
	// this.request.
	protected void produceEvent(ClientEvent ce) {
		try {
			request.produceEvent(ce);
		} catch (Exception e) {
			// Deal with bad client code.
			System.err.println(
				"------------------------------------------------------------");
			System.err.println(
				"WARNING: Crappy client code is throwing from an event handler!");
			e.printStackTrace();
			System.err.println(
				"------------------------------------------------------------");
			Core.logger.log(
				this,
				"WARNING: Crappy client code is throwing from an event handler!",
				e,
				Logger.ERROR);
		}
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass for inserting SplitFile blocks
	////////////////////////////////////////////////////////////

	class InsertBlock extends RetryableInfo {

		String collisionCHK = null;

		InsertBlock() {
			super(STATE_INSERTING_BLOCKS, STATE_INSERTED_BLOCKS, false);
		}
		int realHtl() {
			return htl; /* Doesn't change on retries */
		}

		// Why did the FCP insert clients work without
		// this???

		// Handle key collisions correctly.
		void rawEvent(ClientEvent ce) {
			Core.logger.log(this, "Got event " + ce, Logger.DEBUG);
			if (ce.getCode() == CollisionEvent.code) {
				CollisionEvent coe = (CollisionEvent) ce;
				ClientKey ckey = coe.getKey();
				collisionCHK = ckey.getURI().toString();
			}
			super.rawEvent(ce);
		}

		// Why is ClientPut spec'd to report collisions
		// of CHK keys as failure?
		//
		// Translate key collisions into successes.
		//
		void done(boolean success) {
			if ((!success) && (collisionCHK != null)) {
				if (suggestedExitCode == SplitFileEvent.FAILED) {
					// Fix FAILED, but not CANCELED.
					suggestedExitCode = SplitFileEvent.SUCCEEDED;
				}
				super.done(true);
			} else {
				super.done(success);
			}
		}

		void onSuccess() {
			synchronized (SplitFileInsertManager.this) {
				String uriValue = collisionCHK;
				if (uriValue == null) {
				    PutRequest putReq = (PutRequest) req;
				    if(req == null) throw new NullPointerException();
				    FreenetURI uri = putReq.getURI();
				    if(uri == null) {
				        Core.logger.log(this, "URI on "+putReq+" is null!",
				                Logger.ERROR);
				        throw new NullPointerException();
				    }
				    uriValue = uri.toString();
					//uriValue = ((PutRequest) req).getURI().toString();
				}

				if (isData) {
					blockCHKs[index] = uriValue;
				} else {
					checkCHKs[index] = uriValue;
				}
			}
		}
		void cleanup(boolean success) {
			if (data != null && !requeued) {
				try {
					bf.freeBucket(data);
				} catch (IOException e) {
				}
				data = null;
			}
		}
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass for SegmentSplitFile requests
	////////////////////////////////////////////////////////////

	class GetHeadersFromLength extends RequestInfo {
		void done(boolean success) {
			if (success) {
				synchronized (SplitFileInsertManager.this) {
					SegmentFileRequest r = (SegmentFileRequest) req;
					segmentCount = r.segments();
					headers = r.getHeaders();
					maps = new BlockMap[r.segments()];
				}
				produceEvent(new SplitFileStartedEvent(headers, false));
				setState(STATE_HAS_HEADERS);
			} else {
				errorMsg = "SegmentFileRequest failed.";
				setState(STATE_FAILING);
			}
		}
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass for EncodeSegmentRequests
	////////////////////////////////////////////////////////////

	class DoEncode extends RequestInfo {
		void done(boolean success) {
			if (success) {
				setState(STATE_ENCODED);
			} else {
				errorMsg = "EncodeSegmentRequest failed.";
				setState(STATE_FAILING);
			}
		}

		void cleanup(boolean success) {
			if (!success) {
				try {
					BucketTools.freeBuckets(bf, checks);
				} catch (IOException e) {
					Core.logger.log(
						this,
						"WARNING: Cannot free check buckets",
						e,
						Logger.ERROR);
				}

			}
		}
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass for MakeMetadataRequests
	////////////////////////////////////////////////////////////

	class DoMakeMetadata extends RequestInfo {
		Bucket data = null;
		void done(boolean success) {
			if (success) {
				// Extract SplitFile instance from
				// the metadata.
				InputStream in = null;
				try {
					in = data.getInputStream();
					Metadata md = new Metadata(in, new MetadataSettings());
					// REDFLAG: ok for now --------^
					//          There's nothing in the SplitFile that requires
					//          the MetadataSettings.
					SplitFile sfTmp = md.getSplitFile();
					if (sfTmp == null) {
						throw new Exception("No SplitFile metadata in bucket???");
					}
					// NOTE:
					// We can't just replace the SplitFile reference
					// because Oskars metadata framework expects the existing
					// instance
					// to be modified in place.
					sf.lateConstruct(
						sfTmp.getSize(),
						sfTmp.getBlockURIs(),
						sfTmp.getCheckBlockURIs(),
						sfTmp.getFECAlgorithm());
				} catch (Exception e) {
					e.printStackTrace();
					errorMsg =
						"Internal error making SplitFile: " + e.getMessage();
					setState(STATE_FAILING);
					return;
				} finally {
					if (in != null) {
						try {
							in.close();
						} catch (Exception e) {
						}
					}
				}

				setState(STATE_MADE_METADATA);
			} else {
				errorMsg = "MakeMetadataRequest failed.";
				setState(STATE_FAILING);
			}
		}
		void cleanup(boolean success) {
			if (data != null) {
				try {
					bf.freeBucket(data);
				} catch (IOException e) {
				}
				data = null;
			}
		}
	}

	////////////////////////////////////////////////////////////
	// Creates a SHA1 hash of the entire srcBucket
	////////////////////////////////////////////////////////////
	class MakeChecksum extends RequestInfo {
		void done(boolean success) {
			if (success) {
				ComputeSHA1Request r = (ComputeSHA1Request) req;
				checksum = r.getSHA1();
				// IMPORTANT: Update the checksum value in the
				//            in the MetadataSettings,
				//            so that it will be inserted correctly
				//            by PutRequestProcess after the
				//            SplitFilePutRequest finishes executing.
				//
				request.ms.setChecksum(checksum);
			} else {
				errorMsg =
					"ComputeSHA1Request failed. Couldn't make file checksum.";
				setState(STATE_FAILING);
				System.err.println("Compute checksum FAILED");
			}
		}
	}

	////////////////////////////////////////////////////////////
	// RequestManager abstracts
	////////////////////////////////////////////////////////////

	protected Request constructRequest(RequestInfo i) throws IOException {
		if (i instanceof InsertBlock) {
			InsertBlock ib = (InsertBlock) i;
			try {
				ib.req =
					new PutRequest(
						ib.realHtl(),
						ib.uri,
						BLOCK_CIPHER,
						new NullBucket(),
						ib.data);
			} catch (InsertSizeException ise) {
				System.err.println(
					"--- Unexpected exception making PutRequest ! ---");
				ise.printStackTrace();
				// This should not happen since I am only using CHK keys.
				throw new IOException(
					"Block insert request creation failed: "
						+ ise.getMessage());
			}
		} else if (i instanceof GetHeadersFromLength) {
			i.req =
				new SegmentFileRequest(
					request.algoName,
					request.srcBucket.size());
		} else if (i instanceof DoEncode) {
			BucketTools.freeBuckets(bf, checks);

			checks =
				BucketTools.makeBuckets(
					bf,
					headers[currentSegment].getCheckBlockCount(),
					headers[currentSegment].getCheckBlockSize());

			i.req =
				new EncodeSegmentRequest(
					headers[currentSegment],
					blocks,
					checks,
					null /* encode all blocks */
			);

			produceEvent(
				new SegmentEncodingEvent(
					headers[currentSegment],
					false,
					blocks.length,
					checks.length));

		} else if (i instanceof DoMakeMetadata) {
			Bucket mdTmp = bf.makeBucket(-1);
			((DoMakeMetadata) i).data = mdTmp;
			i.req = new MakeMetadataRequest(headers, maps, mdTmp, "", "");
		} else if (i instanceof MakeChecksum) {
			i.req = new ComputeSHA1Request(request.srcBucket);
		} else {
			// Base class will throw.
			return null;
		}

		return i.req;
	}

	////////////////////////////////////////////////////////////
	// State machine for SplitFile segmentation, encoding and
	// insertion.
	////////////////////////////////////////////////////////////

	final int STATE_REQUESTING_HEADERS = 30;
	final int STATE_HAS_HEADERS = 31;
	final int STATE_INSERTING_BLOCKS = 32;
	final int STATE_INSERTED_BLOCKS = 33;
	final int STATE_ENCODING = 34;
	final int STATE_ENCODED = 35;
	final int STATE_FINISHED_INSERTING = 36;
	final int STATE_MAKING_METADATA = 37;
	final int STATE_MADE_METADATA = 38;

	synchronized Request getNextRequest() {
		for (;;) {
			try {
				switch (state) {
					case STATE_START :
						queueRequest(new GetHeadersFromLength());
						setState(STATE_REQUESTING_HEADERS);
						return super.getNextRequest();
					case STATE_REQUESTING_HEADERS :
						break; // Wait for request to finish.
					case STATE_HAS_HEADERS :
						try {
							setupForSegment();
						} catch (IOException e) {
							errorMsg =
								"Couldn't segment input bucket: "
									+ e.getMessage();
							setState(STATE_FAILING);
							continue;
						}
						preQueueRequest(new DoEncode());
						setState(STATE_ENCODING);
						return super.getNextRequest();
					case STATE_ENCODED :
						// hmmm never implemented a SegmentDecodingEvent
						// because
						//      I didn't need it in the ui..
						produceEvent(
							new SegmentEncodedEvent(
								headers[currentSegment],
								false,
								blocks.length,
								checks.length));
						setupBlockInserts();
						setState(STATE_INSERTING_BLOCKS);
						return super.getNextRequest();

					case STATE_INSERTING_BLOCKS :
						if (canRequest()) {
							return super.getNextRequest();
						}
						// The running BlockInsert instances
						// will drive the transition to STATE_INSERTED_BLOCKS
						// or STATE_FAILING

						break; // Wait for pending requests to finish.
					case STATE_INSERTED_BLOCKS :
						if (requestsRunning() > 0) {
							break; // Wait for a pending request to finish.
						}

						// Save the URIs for this segment.
						maps[currentSegment] =
							new BlockMap(-1, blockCHKs, checkCHKs);
						if (currentSegmentNr >= segmentCount - 1) {
							// Finished with last segment.
							setState(STATE_FINISHED_INSERTING);
							continue;
						}

						nextSegment();

						// Encode and insert next segment.
						try {
							setupForSegment();
						} catch (IOException e) {
							errorMsg =
								"Couldn't segment input bucket: "
									+ e.getMessage();
							setState(STATE_FAILING);
							continue;
						}
						preQueueRequest(new DoEncode());
						setState(STATE_ENCODING);
						return super.getNextRequest();

					case STATE_FINISHED_INSERTING :
						preQueueRequest(new DoMakeMetadata());
						setState(STATE_MAKING_METADATA);
						return super.getNextRequest();

					case STATE_MADE_METADATA :
						setState(STATE_DONE);
						continue;

					case STATE_FAILING :
						if (requestsQueued() > 0) {
							cancelAll();
						}
						if (requestsRunning() > 0) {
							break; // Wait for pending requests to finish.
						}
						setState(STATE_FAILED);
						// IMPORANT: Don't wait!
						continue;
					case STATE_CANCELING :
						if (requestsQueued() > 0) {
							cancelAll();
						}

						if (requestsRunning() > 0) {
							break; // Wait for pending requests to finish.
						}

						setState(STATE_CANCELED);
						// IMPORANT: Don't wait!
						continue;
					case STATE_DONE :
					case STATE_FAILED :
					case STATE_CANCELED :
						doCleanup();
						return null;
				}
				// Should be safe to do an incomplete wait here
				// wait() is dangerous
				wait(200);
			} catch (Exception e) {
				// REDFLAG: untested code path.
				e.printStackTrace();
				produceEvent(new ExceptionEvent(e));

				// Crash land.
				// We can't go to STATE_FAILING and wait to exit cleanly
				// because we can't guarantee that pending requests will
				// finish.
				//
				// This is somewhat underwhelming and may result in
				// temp file leaks.

				cancelAll();

				errorMsg = "Unexpected Exception:" + e.getMessage();
				setState(STATE_FAILED);
				// doCleanup called next pass through.
			}
		}
	}

	synchronized void setupForSegment() throws IOException {
		BucketTools.freeBuckets(bf, blocks);
		BucketTools.freeBuckets(bf, checks);
		checks = null;

		successes = 0;
		failures = 0;

		final SegmentHeader h = headers[currentSegment];

		// Do this before any other events for the
		// segment are posted (i.e. BlockQueuedEvents below).
		produceEvent(new SegmentInsertStartedEvent(h, false));

		successesRequired = h.getBlockCount() + h.getCheckBlockCount();
		failuresAllowed = 0;

		blockCHKs = new String[h.getBlockCount()];
		checkCHKs = new String[h.getCheckBlockCount()];

		File file = request.srcBucket.getFile();

		// Segment file.
		blocks =
			BucketTools.splitFile(
				file,
				h.getBlockSize(),
				h.getOffset(),
				h.getBlockCount(),
				true,
				(BucketTools.BucketFactoryWrapper) bf);

		// Ready to start encoding.
	}

	synchronized void setupBlockInserts() {
		if (currentSegmentNr == 0) {
			// Calculate the file checksum.
			queueRequest(new MakeChecksum());
		}

		SegmentHeader h = headers[currentSegment];
		int i = 0;
		for (i = 0; i < blocks.length; i++) {
			InsertBlock ib = new InsertBlock();
			ib.uri = "CHK@";
			ib.index = i;
			ib.isData = true;
			ib.segment = h.getSegmentNum();
			ib.htl = defaultHtl;
			ib.retries = defaultRetries;
			ib.retryCount = 0;
			ib.data = blocks[i];
			assertTrue(ib.data != null);
			queueRequest(ib);
			produceEvent(new BlockQueuedEvent(h, false, i, true, defaultHtl));
		}

		for (i = 0; i < checks.length; i++) {
			InsertBlock ib = new InsertBlock();
			ib.uri = "CHK@";
			ib.index = i;
			ib.isData = false;
			ib.segment = h.getSegmentNum();
			ib.htl = defaultHtl;
			ib.retries = defaultRetries;
			ib.retryCount = 0;
			ib.data = checks[i];
			assertTrue(ib.data != null);
			queueRequest(ib);
			produceEvent(new BlockQueuedEvent(h, false, i, false, defaultHtl));
		}
	}

	synchronized void doCleanup() {
		if (!postedFinished
			&& (state == STATE_DONE
				|| state == STATE_FAILED
				|| state == STATE_CANCELED)) {
			int exitCode = -1;
			switch (state) {
				case STATE_DONE :
					exitCode = SplitFileEvent.SUCCEEDED;
					break;
				case STATE_FAILED :
					exitCode = SplitFileEvent.FAILED;
					break;
				case STATE_CANCELED :
					exitCode = SplitFileEvent.CANCELED;
					break;
			}
			postedFinished = true;

			// We can get here a the result of an error so headers can be null.
			SegmentHeader h = null;
			if (headers != null) {
				h = headers[currentSegment];
			}

			produceEvent(new SegmentInsertFinishedEvent(h, false, exitCode));
		}

		if (blocks != null) {
			try {
				BucketTools.freeBuckets(bf, blocks);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"WARNING: Cannot free block buckets",
					e,
					Logger.ERROR);
			}
			blocks = null;
		}

		if (checks != null) {
			try {
				BucketTools.freeBuckets(bf, checks);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"WARNING: Cannot free check buckets",
					e,
					Logger.ERROR);
			}
			checks = null;
		}
	}

	public synchronized final SplitFile getSplitFile() {
		if (state != STATE_DONE) {
			throw new IllegalStateException("state != STATE_DONE.");
		}
		return sf;
	}

	public String stateAsString() {
		String ret = super.stateAsString();
		if (ret != null) {
			return ret;
		}

		switch (state) {
			case STATE_REQUESTING_HEADERS :
				return "STATE_REQUESTING_HEADERS";
			case STATE_HAS_HEADERS :
				return "STATE_HAS_HEADERS ";
			case STATE_INSERTING_BLOCKS :
				return "STATE_INSERTING_BLOCKS";
			case STATE_INSERTED_BLOCKS :
				return "STATE_INSERTED_BLOCKS";
			case STATE_ENCODING :
				return "STATE_ENCODING";
			case STATE_ENCODED :
				return "STATE_ENCODED";
			case STATE_FINISHED_INSERTING :
				return "STATE_FINISHED_INSERTING";
			case STATE_MAKING_METADATA :
				return "STATE_MAKING_METADATA";
			case STATE_MADE_METADATA :
				return "STATE_MADE_METADATA";
		}
		return "Unknown state: " + state;
	}
}
