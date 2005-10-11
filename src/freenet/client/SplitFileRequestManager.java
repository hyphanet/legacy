package freenet.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Vector;

import freenet.Core;
import freenet.client.events.BlockQueuedEvent;
import freenet.client.events.ExceptionEvent;
import freenet.client.events.SegmentDecodingEvent;
import freenet.client.events.SegmentHealingStartedEvent;
import freenet.client.events.SegmentRequestFinishedEvent;
import freenet.client.events.SegmentRequestStartedEvent;
import freenet.client.events.SplitFileEvent;
import freenet.client.events.SplitFileStartedEvent;
import freenet.client.events.TransferStartedEvent;
import freenet.client.events.VerifyingChecksumEvent;
import freenet.client.metadata.SplitFile;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.SegmentHeader;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.NullBucket;
import freenet.support.io.BucketInputStream;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.HeapSorter;

/**
 * Helper class handles downloading and decoding SplitFiles.
 * <p>
 * 
 * @author giannij
 */
class SplitFileRequestManager extends RequestManager {
	Bucket[] decoded;
	Bucket[] segments;
	Bucket destBucket;
	OutputStream out;
	long totalWritten = 0;

	SplitFileGetRequest request;

	int[] requestedIndices = null;

	boolean postedFinished = false;

	Vector segmentMap;

	public String toString() {
	    return super.toString()+": "+request+": "+stateAsString(state);
	}
	
	// Turns on:
	// 0) CHK double checking after block
	// downloads. I have seen problems with
	// download failures in the past with
	// InternalClient.
	// 1) Decoded block CHK checking after decode.

	boolean doParanoidChecks = false;

	int defaultHealingHtl = 5;
	int healPercentage = 10;
	Vector healingInserts = new Vector();
	// REDFLAG:
	final static String BLOCK_CIPHER = "Twofish";

	SplitFileRequestManager(SplitFileGetRequest req) {
		super(
			req.sf,
			req.defaultHtl,
			req.defaultRetryIncrement,
			req.defaultRetries,
			req.maxThreads,
			req.nonLocal,
			req.bf);
		this.destBucket = req.destBucket;
		this.request = req;
		this.doParanoidChecks = req.doParanoidChecks;
		if (req.sf.getFECAlgorithm() == null)
			this.doParanoidChecks = false;
		// They might include metadata... not worth the hassle to check them
		this.healPercentage = req.healPercentage;
		this.defaultHealingHtl = req.healingHtl;
		this.checksum = req.checksum;
		// We do this to make sure that buckets coming out
		// of BucketTools.splitFile() are properly released.
		this.bf = new BucketTools.BucketFactoryWrapper(bf);
	}

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
		}
	}

	// override parent
	void nextSegment() {
		currentSegmentNr++;
		currentSegment =
			((Integer) segmentMap.elementAt(currentSegmentNr)).intValue();
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass for SplitFile block requests.
	////////////////////////////////////////////////////////////

	class GetBlock extends RetryableInfo {
		boolean hasMetadata = true;

		GetBlock() {
			super(STATE_REQUESTING_BLOCKS, STATE_HAS_BLOCKS, true);
		}

		// REDFLAG: Remove this hack once the bad SplitFiles die out.
		// Hack to keep CHK checking from tripping for blocks with Metadata.
		// Blocks *should* not have metadata in them, but allow
		// existing ones to be read in all their corruptable glory.
		public void rawEvent(ClientEvent ce) {
			if (ce instanceof TransferStartedEvent) {
				TransferStartedEvent tse = (TransferStartedEvent) ce;
				if (tse.getMetadataLength() > 0) {
					if (doParanoidChecks) {
						System.err.println("");
						System.err.println(
							"============================================================");
						System.err.println(
							"WARNING: This SplitFile contains blocks with metadata.");
						System.err.println(
							"         Integrity checks have been disabled!");
						System.err.println(
							"============================================================");
						System.err.println("");

						// SFRM doesn't have the SplitFile URI. That's why it's
						// not included.
						Core.logger.log(
							this,
							"Disabled integrity checks because of metadata.",
							Logger.DEBUG);
						doParanoidChecks = false;
					}
				}
			}

			super.rawEvent(ce);
		}

		int realHtl() {
			return htl + retryCount * htlRetryIncrement;
		}
		void onSuccess() {
			synchronized (SplitFileRequestManager.this) {
				// A request can finish successfully
				// after it has been canceled.
				// if (state != STATE_REQUESTING_BLOCKS) {
				//    return;
				//}
				// REDFLAG: back to this.

				if (isData) {
					blocks[index] = data;
				} else {
					checks[index] = data;
				}

				// Keep cleanup from freeing it.
				data = null;
			}
		}

		void cleanup(boolean success) {
			if (data != null) {
				try {
					bf.freeBucket(data);
				} catch (IOException e) {
				}
			}
		}
		boolean chain() {
			if (!doParanoidChecks) {
				return false;
			}

			// Queue a request to check that the CHK of
			// the downloaded data really matches the
			// value we requested.
			// This is the GI check (GIGO).
			RequestInfo i = new CheckDownloadedCHK(GetBlock.this, data);
			preQueueRequest(i);

			// Keep cleanup from freeing it.
			data = null;

			return true;
		}
	}

	////////////////////////////////////////////////////////////
	// Checks the CHK of bucket (not chained)
	////////////////////////////////////////////////////////////
	class SimpleCheckCHK extends RequestInfo {

		SimpleCheckCHK(String uri, Bucket data) {
			SimpleCheckCHK.this.uri = uri;
			SimpleCheckCHK.this.data = data;
		}

		void done(boolean success) {
			if (success) {
				ComputeCHKRequest r = (ComputeCHKRequest) req;
				if (!equal(uri, r.getURI().toString())) {
					System.err.println("PARANOID CHK CHECK FAILED(1):");
					System.err.println("   expected: " + uri);
					System.err.println("   got:      " + r.getURI());
					System.err.println("   data.size(): " + data.size());
					System.err.println(
						"   CHK of decoded data block doesn't match.");
					System.err.println(
						"   Please report this failure to devl@freenetproject.org,");
					System.err.println(
						"   Make sure you include the URI of the SplitFile.");
					errorMsg = "Decoded block CHK check failed: " + uri;
					Core.logger.log(
						this,
						"PARANOID CHK CHECK FAILED(1): expected "
							+ uri
							+ ", got "
							+ r.getURI()
							+ ", data.size(): "
							+ data.size(),
						Logger.ERROR);
					setState(STATE_FAILING);
				}
			} else {
				errorMsg = "ComputeCHKRequest failed.";
				setState(STATE_FAILING);
			}
		}

		String uri;
		// We don't own this, so we don't clean it up.
		Bucket data;
		boolean entireFile;
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass to check the CHK values of
	// downloaded blocks.
	////////////////////////////////////////////////////////////

	// This is chained request spawned by GetBlock.
	class CheckDownloadedCHK extends RequestInfo {
		GetBlock parent;
		Bucket data;

		CheckDownloadedCHK(GetBlock gb, Bucket data) {
			parent = gb;
			this.data = data;
		}

		void done(boolean success) {
			if (success) {
				ComputeCHKRequest r = (ComputeCHKRequest) req;

				if (equal(parent.uri, r.getURI().toString())) {
					if (state == STATE_REQUESTING_BLOCKS) {
						// Ouch... ugly hack.
						parent.data = data;
						// Keep cleanup from freeing data.
						data = null;
					} else {
						// Translate SUCCEEDED -> CANCELED
						// for the following scenario.

						// 1. Parent succeeds.
						// 2. Enough blocks are received.
						//    --> STATE_HAS_BLOCKS
						//    pending requests are canceled.
						// 3. The child succeeds

						parent.suggestedExitCode = SplitFileEvent.CANCELED;
						// Cleanup deletes bucket.
					}

					parent.notifySuccess();
				} else {
					System.err.println("PARANOID CHK CHECK FAILED!");
					System.err.println("   expected: " + parent.uri);
					System.err.println("   got:      " + r.getURI());
					System.err.println("   data.size(): " + data.size());
					System.err.println("   parent.segment: " + parent.segment);
					System.err.println("   parent.index: " + parent.index);
					System.err.println("   parent.isData: " + parent.isData);
					System.err.println(
						"   CHK of downloaded block doesn't match CHK in sf metadata.");

					// We know this is happening, no need to generate more
					// reports. --gj 20030120
					//System.err.println(" Please report this failure to
					// devl@freenetproject.org,");
					//System.err.println(" Make sure you include the URI of
					// the SplitFile.");

					parent.notifyFailure();
				}
			} else {
				parent.notifyFailure();
			}
		}

		void cleanup(boolean success) {
			if (data != null) {
				try {
					bf.freeBucket(data);
				} catch (IOException e) {
				}
			}
		}
	}

	////////////////////////////////////////////////////////////
	// Creates a SHA1 hash of the entire srcBucket
	////////////////////////////////////////////////////////////
	class VerifyChecksum extends RequestInfo {
		void done(boolean success) {
			if (success) {
				ComputeSHA1Request r = (ComputeSHA1Request) req;
				String sha1 = r.getSHA1();
				if (!sha1.toLowerCase().equals(checksum.toLowerCase())) {
					System.err.println("CHECKSUM FAILED:");
					System.err.println("   expected: " + checksum);
					System.err.println("   go      : " + sha1);

					errorMsg = "Reconstructed file checksum failed. ";
					setState(STATE_FAILING);
				}
			} else {
				errorMsg =
					"ComputeSHA1Request failed. Couldn't make file checksum.";
				setState(STATE_FAILING);
				System.err.println("Compute checksum FAILED");
			}
		}
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass for SegmentSplitFile requests
	////////////////////////////////////////////////////////////

	class GetHeaders extends RequestInfo {
		SplitFile sf;
		GetHeaders(SplitFile s) {
			GetHeaders.this.sf = s;
		}

		void done(boolean success) {
			if (success) {
				synchronized (SplitFileRequestManager.this) {
					SegmentSplitFileRequest r = (SegmentSplitFileRequest) req;
					segmentCount = r.segments();

					// setup randomized segment mapping
					segmentMap = new Vector(segmentCount);
					for (int i = 0; i < segmentCount; i++)
						segmentMap.add(new Integer(i));
					if (request.randomSegs) {
						shuffleVector(segmentMap);
						currentSegment =
							((Integer) segmentMap.elementAt(0)).intValue();
						segments = new Bucket[segmentCount];
					}

					headers = r.getHeaders();
					maps = r.getMaps();
					length = headers[0].getFileLength();
				}
				produceEvent(new SplitFileStartedEvent(headers, true));
				setState(STATE_HAS_HEADERS);
			} else {
				errorMsg = "SegmentSplitFileRequest failed.";
				setState(STATE_FAILING);
			}
		}
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass for DecodeSegment requests
	////////////////////////////////////////////////////////////

	class DoDecode extends RequestInfo {
		void done(boolean success) {
			if (success) {
				if (doParanoidChecks) {
					final DecodeSegmentRequest r = (DecodeSegmentRequest) req;
					final int[] list = r.requestedIndices;
					final String[] blockUris_ =
						maps[currentSegment].getDataBlocks();
					final String[] checkUris_ =
						maps[currentSegment].getCheckBlocks();
					final int blockCount =
						headers[currentSegment].getBlockCount();
					Bucket[] blocks_ = r.decoded;
					for (int i = 0; i < list.length; i++) {
						// Check the CHKs of the decoded blocks against
						// the values specified in the SplitFile.
						// This is the GO check (GIGO).
						if (list[i] < blockCount) {
							queueRequest(
								new SimpleCheckCHK(
									blockUris_[list[i]],
									blocks_[i]));
						} else {
							queueRequest(
								new SimpleCheckCHK(
									checkUris_[list[i] - blockCount],
									blocks_[i]));
						}
					}
					setState(STATE_CHECKING_DECODED);
				} else {
					setState(STATE_DECODED);
				}
			} else {
				errorMsg = "DecodeSegmentRequest failed.";
				setState(STATE_FAILING);
			}
		}

		void cleanup(boolean success) {
			if (!success) {
				try {
					BucketTools.freeBuckets(bf, decoded);
				} catch (IOException e) {
					Core.logger.log(
						this,
						"WARNING: Cannot free decoded buckets",
						e,
						Logger.ERROR);
				}

			}
		}
	}

	////////////////////////////////////////////////////////////
	// RequestInfo subclass for inserting unretrievable blocks
	////////////////////////////////////////////////////////////
	class HealingInsertBlock extends RetryableInfo {
		HealingInsertBlock() {
			super(
				STATE_INSERTING_MISSING_BLOCKS,
				STATE_INSERTED_MISSING_BLOCKS,
				false);
		}
		int realHtl() {
			return htl;
		}
		void onSuccess() {
		}
		void cleanup(boolean success) {
		}
	}

	////////////////////////////////////////////////////////////
	// RequestManager abstracts
	////////////////////////////////////////////////////////////

	int[] nullIndices(Bucket[] array) {
		Vector list = new Vector();
		int i = 0;
		for (i = 0; i < array.length; i++) {
			if (array[i] == null) {
				list.addElement(new Integer(i));
			}
		}

		int[] ret = new int[list.size()];
		for (i = 0; i < list.size(); i++) {
			ret[i] = ((Integer) list.elementAt(i)).intValue();
		}
		return ret;
	}

	int[] nonNullIndices(Bucket[] array) {
		Vector list = new Vector();
		int i = 0;
		for (i = 0; i < array.length; i++) {
			if (array[i] != null) {
				list.addElement(new Integer(i));
			}
		}

		int[] ret = new int[list.size()];
		for (i = 0; i < list.size(); i++) {
			ret[i] = ((Integer) list.elementAt(i)).intValue();
		}
		return ret;
	}

	Bucket[] nonNullBuckets(Bucket[] array) {
		Vector list = new Vector();
		int i = 0;
		for (i = 0; i < array.length; i++) {
			if (array[i] != null) {
				list.addElement(array[i]);
			}
		}

		Bucket[] ret = new Bucket[list.size()];
		list.copyInto(ret);
		return ret;
	}

	protected Request constructRequest(RequestInfo i) throws IOException {
		if (i instanceof GetBlock) {
			GetBlock gb = (GetBlock) i;
			if (gb.data != null) {
				bf.freeBucket(gb.data);
			}
			// -1 size values for NullFECDecoder will work.
			gb.data =
				bf.makeBucket(
					gb.isData
						? headers[currentSegment].getBlockSize()
						: headers[currentSegment].getCheckBlockSize());
			gb.req =
				new GetRequest(
					gb.realHtl(),
					gb.uri,
					new NullBucket(),
					gb.data,
					nonLocal);
		} else if (i instanceof GetHeaders) {
			i.req = new SegmentSplitFileRequest(((GetHeaders) i).sf);
		} else if (i instanceof DoDecode) {
			i.req = setupForDecode();
		} else if (i instanceof CheckDownloadedCHK) {
			CheckDownloadedCHK cc = (CheckDownloadedCHK) i;
			cc.req = new ComputeCHKRequest(null, new NullBucket(), cc.data);
		} else if (i instanceof VerifyChecksum) {
			i.req = new ComputeSHA1Request(request.destBucket);
		} else if (i instanceof SimpleCheckCHK) {
			SimpleCheckCHK cc = (SimpleCheckCHK) i;
			cc.req = new ComputeCHKRequest(null, new NullBucket(), cc.data);
		} else if (i instanceof HealingInsertBlock) {
			HealingInsertBlock ib = (HealingInsertBlock) i;
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
		} else {
			// Base class will throw.
			return null;
		}

		return i.req;
	}

	////////////////////////////////////////////////////////////
	// State machine for SplitFile downloading and decoding.
	////////////////////////////////////////////////////////////

	public static final int STATE_REQUESTING_BLOCKS = 10;
	public static final int STATE_HAS_BLOCKS = 11;
	public static final int STATE_REQUESTING_HEADERS = 12;
	public static final int STATE_HAS_HEADERS = 13;
	public static final int STATE_DECODING = 14;
	public static final int STATE_CHECKING_DECODED = 15;
	public static final int STATE_DECODED = 16;
	public static final int STATE_INSERTING_MISSING_BLOCKS = 17;
	public static final int STATE_INSERTED_MISSING_BLOCKS = 18;
	public static final int STATE_VERIFYING_CHECKSUM = 19;

	synchronized Request getNextRequest() {
		for (;;) {
			try {
				switch (state) {
					case STATE_START :
						out = destBucket.getOutputStream();
						queueRequest(new GetHeaders(sf));
						setState(STATE_REQUESTING_HEADERS);
						return super.getNextRequest();
					case STATE_REQUESTING_HEADERS :
						break; // Wait for request to finish.
					case STATE_HAS_HEADERS :
						setupForSegment();
						setState(STATE_REQUESTING_BLOCKS);
						return super.getNextRequest();
					case STATE_REQUESTING_BLOCKS :
						if (canRequest()) {
							return super.getNextRequest();
						}
						//System.err.println("Waiting for job or thread. " +
						//                   requestsQueued() + " " + requestsRunning());
						break; // Wait for pending requests to finish.
					case STATE_HAS_BLOCKS :
						if (isWorking()) {
							// Cancel any pending block requests once we have
							// enough blocks to decode from.
							cancelAll();
						}
						if (requestsRunning() > 0) {
							break; // Wait for a pending request to finish.
						}
						// Skip decode if we have all the data blocks.
						if (nullIndices(blocks).length == 0) {
							setState(STATE_DECODED);
							continue;
						}

						// Save information we will need to re-insert
						// unretrievable blocks after the decode.
						if (healPercentage > 0) {
							setupHealingInserts();
						}

						// see constructRequest .
						queueRequest(new DoDecode());

						setState(STATE_DECODING);
						return super.getNextRequest();
					case STATE_DECODING :
						break; // Wait for decoding to finish.

					case STATE_CHECKING_DECODED :
						if (canRequest()) {
							return super.getNextRequest();
						}
						if (isWorking()) {
							break;
						}

						setState(STATE_DECODED);
						continue; // Don't wait.
					case STATE_DECODED :
						if (request.randomSegs) {
							storeSegment();
						} else {
							writeData();
						}

						assertTrue(
							(requestsRunning() == 0)
								&& (requestsQueued() == 0));

						if (healingInserts.size() > 0) {
							if (request.inserter == null) {
								// No background requester was specified,
								// so we are responsible for doing the inserts.
								setState(STATE_INSERTING_MISSING_BLOCKS);

								// Post this event before queueing the inserts
								// so the client will know to expect the
								// BlockQueuedEvents.
								produceEvent(
									new SegmentHealingStartedEvent(
										headers[currentSegment],
										true,
										healingInserts.size()));

								queueHealingInserts();

								continue;
							} else {
								// Give the reconstructed blocks to be
								// reinserted
								// to the BackgroundInserter.
								giveAwayHealingInserts(request.inserter);
							}
						}

						setState(STATE_INSERTED_MISSING_BLOCKS);
						continue;

					case STATE_INSERTING_MISSING_BLOCKS :
						if (canRequest()) {
							return super.getNextRequest();
						}
						if (isWorking()) {
							break;
						}

						setState(STATE_INSERTED_MISSING_BLOCKS);
						continue;

					case STATE_INSERTED_MISSING_BLOCKS :
						if (canRequest()) {
							return super.getNextRequest();
						}
						if (isWorking()) {
							break;
						}

						if (currentSegmentNr + 1 < segmentCount) {
							produceEvent(
								new SegmentRequestFinishedEvent(
									headers[currentSegment],
									true,
									SplitFileEvent.SUCCEEDED));

							nextSegment();
							setupForSegment();

							setState(STATE_REQUESTING_BLOCKS);

							return super.getNextRequest();
						}

						if (request.randomSegs) {
							writeSegments();
						}

						if ((checksum != null) && doParanoidChecks) {
							// Check the file's checksum.
							queueRequest(new VerifyChecksum());
							setState(STATE_VERIFYING_CHECKSUM);
							produceEvent(
								new VerifyingChecksumEvent(
									headers[currentSegment],
									true,
									checksum));
							continue;
						}

						postedFinished = true;
						produceEvent(
							new SegmentRequestFinishedEvent(
								headers[currentSegment],
								true,
								SplitFileEvent.SUCCEEDED));

						setState(STATE_DONE);

						// IMPORANT: Don't wait!
						continue;

					case STATE_VERIFYING_CHECKSUM :
						// Wait for the checksum verification to finish.
						if (canRequest()) {
							return super.getNextRequest();
						}
						if (isWorking()) {
							break;
						}

						setState(STATE_DONE);

						// IMPORANT: Don't wait!
						continue;

					case STATE_FAILING :
						// Sloppy, but ok. Could call cancel multiple times.
						cancelAll();

						if (requestsRunning() > 0) {
							break; // Wait for pending requests to finish.
						}
						setState(STATE_FAILED);
						// IMPORANT: Don't wait!
						continue;
					case STATE_CANCELING :
						// Sloppy, but ok. Could call cancel multiple times.
						cancelAll();

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
				boolean logDebug=Core.logger.shouldLog(Logger.DEBUG,this);
				if (logDebug)
					Core.logger.log(
						this,
						"Entering wait(), state=" + stateAsString(),
						new Exception("debug"),
						Logger.DEBUG);
				wait(200);
				// If we have got here, it is safe to do an incomplete wait
				// JVM bugs may cause wait() to never return, so we are adding
				// an argument everywhere
				if (logDebug)
					Core.logger.log(
						this,
						"Leaving wait(), state=" + stateAsString(),
						new Exception("debug"),
						Logger.DEBUG);
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
				errorThrowable = e;
				setState(STATE_FAILED);
				// doCleanup called next pass through.
			}
		}

	}

	synchronized void setupForSegment() {
		try {
			BucketTools.freeBuckets(bf, blocks);
		} catch (IOException e) {
			Core.logger.log(
				this,
				"WARNING: Cannot free blocks buckets",
				e,
				Logger.ERROR);
		}
		blocks = null;
		try {
			BucketTools.freeBuckets(bf, checks);
		} catch (IOException e) {
			Core.logger.log(
				this,
				"WARNING: Cannot free checks buckets",
				e,
				Logger.ERROR);
		}
		checks = null;
		try {
			BucketTools.freeBuckets(bf, decoded);
		} catch (IOException e) {
			Core.logger.log(
				this,
				"WARNING: Cannot free decoded buckets",
				e,
				Logger.ERROR);
		}

		decoded = null;
		healingInserts.removeAllElements();
		requestedIndices = null;
		decoded = null;

		successes = 0;
		failures = 0;

		final SegmentHeader h = headers[currentSegment];

		// Do this before any other events for the
		// segment are posted (i.e. BlockQueuedEvents below).
		produceEvent(new SegmentRequestStartedEvent(h, true, currentSegmentNr));

		successesRequired = h.getBlocksRequired();
		failuresAllowed =
			h.getBlockCount() + h.getCheckBlockCount() - h.getBlocksRequired();

		blocks = new Bucket[h.getBlockCount()];
		checks = new Bucket[h.getCheckBlockCount()];

		final BlockMap m = maps[currentSegment];
		int i = 0;
		String[] uris = m.getDataBlocks();
		for (i = 0; i < uris.length; i++) {
			GetBlock gb = new GetBlock();
			gb.uri = uris[i];
			gb.index = i;
			gb.isData = true;
			gb.segment = h.getSegmentNum();
			gb.htl = defaultHtl;
			gb.htlRetryIncrement = defaultRetryIncrement;
			gb.retries = defaultRetries;
			gb.retryCount = 0;
			gb.data = null;
			queueRequest(gb);
			produceEvent(new BlockQueuedEvent(h, true, i, true, defaultHtl));
		}

		uris = m.getCheckBlocks();
		for (i = 0; i < uris.length; i++) {
			GetBlock gb = new GetBlock();
			gb.uri = uris[i];
			gb.index = i;
			gb.isData = false;
			gb.segment = h.getSegmentNum();
			gb.htl = defaultHtl;
			gb.htlRetryIncrement = defaultRetryIncrement;
			gb.retries = defaultRetries;
			gb.retryCount = 0;
			gb.data = null;
			queueRequest(gb);
			produceEvent(new BlockQueuedEvent(h, true, i, false, defaultHtl));
		}
		// Shuffle to keep from favoring earlier requests.
		shuffleRequestQueue();
	}

	// This where we decide which unretreivable
	// blocks we want to insert.
	synchronized boolean setupHealingInserts() {
		healingInserts.removeAllElements();
		// Queue requests for every missing block.
		int i = 0;
		for (i = 0; i < blocks.length; i++) {
			if (blocks[i] == null) {
				HealingInsertBlock ib = new HealingInsertBlock();
				ib.index = i;
				ib.isData = true;
				healingInserts.addElement(ib);
			}
		}

		for (i = 0; i < checks.length; i++) {
			if (blocks[i] == null) {
				HealingInsertBlock ib = new HealingInsertBlock();
				ib.index = i;
				ib.isData = false;
				healingInserts.addElement(ib);
			}
		}

		if (healingInserts.size() < 1) {
			return false;
		}

		// Shuffle
		shuffleVector(healingInserts);

		// Truncate to the requested percentage
		if (healPercentage < 100) {
			int num = (healPercentage * healingInserts.size()) / 100;
			if (num < 1) {
				num++;
			}
			healingInserts.setSize(num);
		}

		// Reset stats that are used by RequestManager.RetryableInfo.
		successes = 0;
		failures = 0;
		successesRequired = healingInserts.size();
		failuresAllowed = Integer.MAX_VALUE;
		return true;
		// We do the per-block initialization in queueHealingInserts
	}

	private static class ComparableInteger
		implements Comparable {
		Integer value = null;
		ComparableInteger(int v) {
			this.value = new Integer(v);
		}

		public int compareTo(Object o) {
			if (!(o instanceof ComparableInteger)) {
				throw new RuntimeException("I only know about ComparableIntegers. Sorry.");
			}
			return value.compareTo(((ComparableInteger) o).value);
		}
	}

	// This tells us which check blocks we need to request when decoding.
	synchronized int[] requiredCheckIndices() {
		if (healingInserts.size() == 0) {
			return new int[0];
		}

		Vector checkRequests = new Vector();

		int blockCount = headers[currentSegment].getBlockCount();
		for (Enumeration e = healingInserts.elements(); e.hasMoreElements();) {
			HealingInsertBlock ib = (HealingInsertBlock) e.nextElement();
			if (!ib.isData) {
				// add offset.
				checkRequests.addElement(
					new ComparableInteger(ib.index + blockCount));
			}
		}

		if (checkRequests.size() == 0) {
			return new int[0];
		}

		ComparableInteger[] values =
			new ComparableInteger[checkRequests.size()];
		checkRequests.copyInto(values);

		// Sort by count.
		HeapSorter.heapSort(new ArraySorter(values));

		int[] ret = new int[values.length];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = values[i].value.intValue();
		}

		return ret;
	}

	// This queues the blocks for insertion.
	synchronized void queueHealingInserts() {
		assertTrue(healingInserts.size() > 0);
		SegmentHeader h = headers[currentSegment];
		int seg = h.getSegmentNum();
		for (Enumeration e = healingInserts.elements(); e.hasMoreElements();) {
			HealingInsertBlock ib = (HealingInsertBlock) e.nextElement();
			ib.uri = "CHK@";
			ib.segment = seg;
			ib.htl = defaultHealingHtl;
			ib.retries = 0;
			ib.retryCount = 0;
			ib.data = ib.isData ? blocks[ib.index] : checks[ib.index];
			assertTrue(ib.data != null);
			queueRequest(ib);
			produceEvent(
				new BlockQueuedEvent(
					h,
					false,
					ib.index,
					ib.isData,
					defaultHealingHtl));
		}
	}

	// Queue healing inserts with a BackgroundInserter instead of
	// inserting them ourself.
	synchronized void giveAwayHealingInserts(BackgroundInserter inserter) {
		assertTrue(healingInserts.size() > 0);
		for (Enumeration e = healingInserts.elements(); e.hasMoreElements();) {
			HealingInsertBlock ib = (HealingInsertBlock) e.nextElement();
			ib.data = ib.isData ? blocks[ib.index] : checks[ib.index];
			assertTrue(ib.data != null);
			inserter.queue(ib.data, bf, defaultHealingHtl, BLOCK_CIPHER);

			// The inserter owns the bucket now, so make sure
			// we don't try to delete it.
			if (ib.isData) {
				blocks[ib.index] = null;
			} else {
				checks[ib.index] = null;
			}
		}
	}

	// note: allocates decode buckets and sets requestedIndices
	//       as a side effect.
	synchronized DecodeSegmentRequest setupForDecode() throws IOException {
		// Should never get here.
		assertTrue(
			!headers[currentSegment].getFECAlgorithm().equals(
				FECTools.NULLDECODERNAME));

		int[] dataIndices = nonNullIndices(blocks);
		int[] checkIndices = nonNullIndices(checks);
		requestedIndices = nullIndices(blocks);

		int[] rci = requiredCheckIndices();
		if (rci.length > 0) {
			// Request missing check blocks too.
			int[] tmp = requestedIndices;
			requestedIndices = new int[tmp.length + rci.length];
			System.arraycopy(tmp, 0, requestedIndices, 0, tmp.length);
			// rci is already sorted in ascending order
			System.arraycopy(rci, 0, requestedIndices, tmp.length, rci.length);
		}

		BucketTools.freeBuckets(bf, decoded);
		decoded =
			BucketTools.makeBuckets(
				bf,
				requestedIndices.length,
				headers[currentSegment].getBlockSize());
		assertTrue(decoded != null);
		assertTrue(decoded.length == requestedIndices.length);
		DecodeSegmentRequest ret =
			new DecodeSegmentRequest(
				headers[currentSegment],
				nonNullBuckets(blocks),
				nonNullBuckets(checks),
				decoded,
				dataIndices,
				checkIndices,
				requestedIndices);

		produceEvent(
			new SegmentDecodingEvent(
				headers[currentSegment],
				true,
				dataIndices.length,
				checkIndices.length,
				rci.length));

		return ret;
	}

	// This writes the decoded data/check blocks into checks/blocks.
	synchronized void fillInDecodedBlocks() {
	    if(decoded == null) {
	        Core.logger.log(this, "Skipping fillInDecodedBlocks because "+
	                this+" has all data blocks?", Logger.MINOR);
	        return; // we skipped decode?
	    }
		assertTrue(requestedIndices != null);
		assertTrue(decoded.length == requestedIndices.length);
		int blockCount = headers[currentSegment].getBlockCount();
		for (int i = 0; i < requestedIndices.length; i++) {
			int index = requestedIndices[i];
			if (index < blockCount) {
				blocks[index] = decoded[i];
				// Keeps cleanup() from freeing if the
				// the blocks[] ref was given away
				// to the BackgroundInserter.
				decoded[i] = null;
			} else {
				checks[index - blockCount] = decoded[i];
				// As above.
				decoded[i] = null;
			}
		}
	}

	// write current segment to segments[]
	synchronized void storeSegment() throws IOException {
		// There are no blocks to fill in for non-redundant SplitFiles.
		// This should fix the assertion that Bombe reported 20030122.
		if (!headers[currentSegment]
			.getFECAlgorithm()
			.equals(FECTools.NULLDECODERNAME)) {
			fillInDecodedBlocks();
		}

		long byteCount = 0;
		for (int i = 0; i < blocks.length; i++) {
			byteCount += blocks[i].size();
		}
		segments[currentSegment] = bf.makeBucket(byteCount);

		InputStream in = null;
		OutputStream out = segments[currentSegment].getOutputStream();
		try {
			in = new BucketInputStream(blocks, byteCount);
			byte[] buffer = new byte[16384];

			int nRead = in.read(buffer);
			while (nRead > 0) {
				out.write(buffer, 0, nRead);
				nRead = in.read(buffer);
			}
		} finally {
			if (in != null) {
				in.close();
			}
			if (out != null) {
				out.close();
			}
		}
	}

	// concatenate segments[]
	// REQUIRES: out already opened.
	synchronized void writeSegments() throws IOException {
		long totalWritten = 0;

		for (int seg = 0; seg < segmentCount; seg++) {
			// Padded segment length.
			long segmentLength =
				headers[seg].getBlockSize() * headers[seg].getBlockCount();

			if (totalWritten + segmentLength > length) {
				segmentLength = length - totalWritten;
			}

			InputStream in = null;
			try {
				in = segments[seg].getInputStream();
				byte[] buffer = new byte[16384];
				int nRead = in.read(buffer);
				long toWrite = segmentLength;

				while (toWrite >= nRead && nRead > 0) {
					out.write(buffer, 0, nRead);
					toWrite -= nRead;
					nRead = in.read(buffer);
				}
				if (nRead > 0) {
					out.write(buffer, 0, (int) toWrite);
				} else if (toWrite > 0) {
					throw new IOException(
						"Didn't write enough data: " + toWrite + " missing.");
				}
			} finally {
				if (in != null) {
					in.close();
				}
			}
			if (segments[seg] != null) {
				bf.freeBucket(segments[seg]);
			}
			totalWritten += segmentLength;
		}
	}

	// REQUIRES: out already opened.
	synchronized void writeData() throws IOException {
		// There are no blocks to fill in for non-redundant SplitFiles.
		// This should fix the assertion that Bombe reported 20030122.
		if (!headers[currentSegment]
			.getFECAlgorithm()
			.equals(FECTools.NULLDECODERNAME)) {
			fillInDecodedBlocks();
		}

		long byteCount = 0;
		for (int i = 0; i < blocks.length; i++) {
			byteCount += blocks[i].size();
		}

		// Padded segment length.
		long segmentLength =
			headers[currentSegment].getBlockSize()
				* headers[currentSegment].getBlockCount();

		assertTrue(
			(headers[currentSegment]
				.getFECAlgorithm()
				.equals(FECTools.NULLDECODERNAME))
				|| (segmentLength == byteCount));

		segmentLength = byteCount;

		if (totalWritten + segmentLength > length) {
			segmentLength = length - totalWritten;
		}

		InputStream in = null;
		try {
			in = new BucketInputStream(blocks, segmentLength);
			byte[] buffer = new byte[16384];
			int nRead = in.read(buffer);

			long count = 0;

			while (nRead > 0) {
				out.write(buffer, 0, nRead);
				count += nRead;
				nRead = in.read(buffer);
			}
			if (count != segmentLength) {
				throw new IOException(
					"Didn't write the right amount of data! count:"
						+ count
						+ " length: "
						+ segmentLength);
			}
		} finally {
			if (in != null) {
				in.close();
			}
		}
		totalWritten += segmentLength;
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
			produceEvent(
				new SegmentRequestFinishedEvent(
					headers == null ? null : headers[currentSegment],
					true,
					exitCode));
			//headers might be null if the splitfile request failed really
			// early
		}

		if (decoded != null) {
			try {
				BucketTools.freeBuckets(bf, decoded);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"WARNING: Cannot free decoded buckets",
					e,
					Logger.ERROR);
			}
			decoded = null;
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

		healingInserts.removeAllElements();
		if (out != null) {
			try {
				out.close();
			} catch (Exception e) {
			}
			out = null;
		}
	}

	//     public String stateAsString() {

	static public String stateAsString(int state) {

		String ret = RequestManager.stateAsString(state);
		if (ret != null) {
			return ret;
		}

		switch (state) {
			case STATE_REQUESTING_BLOCKS :
				return "STATE_REQUESTING_BLOCKS";
			case STATE_HAS_BLOCKS :
				return "STATE_HAS_BLOCKS";
			case STATE_REQUESTING_HEADERS :
				return "STATE_REQUESTING_HEADERS";
			case STATE_HAS_HEADERS :
				return "STATE_HAS_HEADERS ";
			case STATE_DECODING :
				return "STATE_DECODING ";
			case STATE_CHECKING_DECODED :
				return "STATE_CHECKING_DECODED ";
			case STATE_DECODED :
				return "STATE_DECODED ";
			case STATE_INSERTING_MISSING_BLOCKS :
				return "STATE_INSERTING_MISSING_BLOCKS";
			case STATE_INSERTED_MISSING_BLOCKS :
				return "STATE_INSERTED_MISSING_BLOCKS";
			case STATE_VERIFYING_CHECKSUM :
				return "STATE_VERIFYING_CHECKSUM";
		}

		return "Unknown state: " + state;
	}
}
