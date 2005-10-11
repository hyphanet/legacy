package freenet.client;

import freenet.client.events.*;
import freenet.message.client.FEC.SegmentHeader;

/**
 * A listener that collects information about the progress
 * of SplitFile uploads and downloads.
 * <p>
 * @author giannij
 */
public class SplitFileStatus implements ClientEventListener {

    protected SegmentHeader header;
    protected int queued;
    protected int running;
    protected int processed;
    protected int failed;
    protected int retried;
    protected int rnfs;
    protected int dnfs;
    protected int statusCode = STARTING;
    protected long touched =  System.currentTimeMillis();
    protected boolean inserting = false;
    
    protected int reinsertions = -1;
    protected String checksum = "";
    protected int dataBlocks;
    protected int[] retrievedBlockStatus;
    protected int[] retrievedBlockRetryCount;
    protected long[] retrievedBlockSize;
    protected int[] insertedBlockStatus;
    protected int[] insertedBlockRetryCount;
    protected long[] insertedBlockSize;
    protected long completeSize;
    protected long dataSize;
    protected long retrievedBytes;
    protected long insertedBytes;
    protected int segmentNr;

    // status values for a single block
    public final static int QUEUED = 51;
    public final static int RUNNING = 52;
    public final static int FAILED_RNF = 53;
    public final static int FAILED_DNF = 54;
    //public final static int FAILED = 55; // defined below
    public final static int REQUEUED_RNF = 56;
    public final static int REQUEUED_DNF = 57;

    public final static int REQUEUED = 58; // requeued with unknown reason. can that happen? /Bombe
    // Yes it can.  For example if CHK paranoid checking is 
    // enabled, a block can be requeued if its
    // CHK doesn't match the CHK in the manifest.  
    // Its a scary bug, but it does happen.

    public final static int SUCCESS = 59;

    // for "unused" healing insertion blocks
    public final static int UNDEFINED = 60;

    // Exit status values.
    public final static int STARTING = 1;
    public final static int FETCHING_BLOCKS = 2;
    public final static int INSERTING_BLOCKS = 3;
    public final static int DECODING = 4;
    public final static int ENCODING = 5;
    public final static int SUCCEEDED = 6;
    public final static int FAILED = 7;
    public final static int CANCELED = 8;
    public final static int VERIFYING_CHECKSUM = 9;

    protected void reset() {
        reinsertions = 0;
        header = null;
        queued = 0;
        running = 0;
        processed = 0;
        failed = 0;
        retried = 0;
        rnfs = 0;
        dnfs = 0;
        touched = System.currentTimeMillis();
    }

    /**
     * The current segment.
     **/
    public final synchronized SegmentHeader segment() { return header; }

    /**
     * The number of data and check blocks that are
     * queued up to be run.
     **/
    public final synchronized int blocksQueued() { return queued; }

    /**
     * The number of requests that are currently being executed.
     **/
    public final synchronized int blocksRunning() { return running; }


    /**
     * The number of blocks that have been successfully fetched or
     * inserted.
     **/
    public final synchronized int blocksProcessed() { return processed; }


    /**
     * The number of blocks that have failed.  Blocks are only 
     * counted as failed after all allowed retries have failed.
     **/
    public final synchronized int blocksFailed() { return failed; }


    /**
     * The total number of blocks that were queued to be retried 
     * after failing to download.
     **/
    public final synchronized int blocksRetried() { return retried; }

    /**
     * The status of the specified block.
     * @param index The number of the block
     * @return Status
     */
    public final synchronized int retrievedBlockStatus(int index) { return retrievedBlockStatus[index]; }

    /**
     * The number of retries for the specified block
     * @param index The number of the block
     * @return Number of Retries
     */
    public final synchronized int retrievedBlockRetries(int index) { return retrievedBlockRetryCount[index]; }
    
    /**
     * The size for the specified block
     * @param index The number of the block
     * @return Number of Retries
     */
    public final synchronized long retrievedBlockSize(int index) { return retrievedBlockSize[index]; }
    
    /**
     * The number of already retrieved bytes.
     * @return Number of Retries
     */
    public final synchronized long retrievedBytes() { return retrievedBytes; }

    /**
     * The total number of bytes for this splitfile
     * including all check blocks, padding, etc.
     * @return The total size of the splitfile
     */
    public final synchronized long completeSize() { return completeSize; }

    /**
     * The total number of bytes for this splitfile
     * including padding, etc. but without check blocks.
     * @return Number of Retries
     */
    public final synchronized long dataSize() { return dataSize; }

    /**
     * The status of the specified block.
     * @param index The number of the block
     * @return Status
     */
    public final synchronized int insertedBlockStatus(int index) { return insertedBlockStatus[index]; }

    /**
     * The number of retries for the specified block
     * @param index The number of the block
     * @return Number of Retries
     */
    public final synchronized int insertedBlockRetries(int index) { return insertedBlockRetryCount[index]; }

    /**
     * @return the total number of bytes successfully inserted so far
     */
    public final synchronized long insertedBytes() { return insertedBytes; }

    /**
     * Are we requesting or inserting?
     * @return <i>true</i> if requesting, <i>false</i> if inserting
     */
    public final synchronized boolean isRequesting() { return !inserting; }

    /**
     * The number of request attemps that have failed 
     * with RouteNotFound errors.
     * <p>
     * Note that because of retrying this can be
     * incremented more than once for each request queued.
     **/
    public final synchronized int rnfs() { return rnfs; }

    /**
     * The number of request attemps that have failed 
     * with DataNotFound errors.
     * <p>
     * Note that because of retrying this can be
     * incremented more than once for each request queued.
     **/
    public final synchronized int dnfs() { return dnfs; }

    /**
     * The time the that the last low level status update
     * event was retrieved. 
     */
    public final synchronized long touched() { return touched; }

    /**
     * A code indicating the status of the request.
     */
    public final synchronized int statusCode() { return statusCode; }
    

    /**
     * The SplitFile's checksum if it has one.
     * This can be null.
     **/
    public final synchronized String checksum() { return checksum; }


    /**
     * The number of reconstructed unretrievable blocks the
     * that are being re-inserted.
     **/
    public final synchronized int reinsertions() { return reinsertions; }

    /**
     * The number of the currently downloaded segment with respect
     * to download order.
     */
    public final synchronized int segmentNr() { return segmentNr; }

    private final static String[] names = { "STARTING","FETCHING_BLOCKS",
                                            "INSERTING_BLOCKS",
                                            "DECODING", "ENCODING", "SUCCEEDED",
                                            "FAILED", "CANCELED", "VERIFYING_CHECKSUM" };

    public final static String statusString(int statusCode) {
        if (statusCode < 1 || statusCode > names.length) {
            return "UNKNOWN";
        }

        return names[statusCode - 1];
    }

    public synchronized void receive(ClientEvent ce) {
        //System.err.println("SFDS: " + ce.getDescription() + ", " + ce.getClass().getName());
        touched = System.currentTimeMillis();

	// For use by any Events that can happen for both Insert and Request
	int[] statusArray = null;
	
	if (((SplitFileEvent) ce).isRequesting()) {
	    statusArray = retrievedBlockStatus;
	} else {
	    statusArray = insertedBlockStatus;
        }
        switch (ce.getCode()) {
        case SplitFileStartedEvent.code:
            SegmentHeader headers[] = ((SplitFileStartedEvent) ce).headers();
            completeSize = 0;
            dataSize = 0;
            retrievedBytes = 0;
            insertedBytes = 0;
            for (int h = 0; h < headers.length; h++) {
                completeSize +=
                    headers[h].getBlockCount() * headers[h].getBlockSize() +
                    headers[h].getCheckBlockCount() * headers[h].getCheckBlockSize();
                dataSize +=
                    headers[h].getBlockCount() * headers[h].getBlockSize();
            }
            break;
        case SegmentRequestStartedEvent.code:
            reset();
            inserting = false;
            header = ((SegmentRequestStartedEvent)ce).getHeader();
            dataBlocks = header.getBlockCount();
            retrievedBlockStatus = new int[dataBlocks + header.getCheckBlockCount()];
            retrievedBlockRetryCount = new int[dataBlocks + header.getCheckBlockCount()];
            retrievedBlockSize = new long[dataBlocks + header.getCheckBlockCount()];
            for (int i = 0; i < dataBlocks + header.getCheckBlockCount(); i++) {
                retrievedBlockRetryCount[i] = 0;
                retrievedBlockSize[i] = 0;
            }
            segmentNr = ((SegmentRequestStartedEvent)ce).getSegmentNr();
            statusCode = FETCHING_BLOCKS;
            break;
        case SegmentInsertStartedEvent.code:
            reset();
            inserting = true;


            // hmmmm... what info do we need for insert status?
            // more statistics.
            header = ((SegmentInsertStartedEvent)ce).getHeader();
            dataBlocks = header.getBlockCount();
            insertedBlockStatus = new int[dataBlocks + header.getCheckBlockCount()];
            insertedBlockRetryCount = new int[dataBlocks + header.getCheckBlockCount()];
            insertedBlockSize = new long[dataBlocks + header.getCheckBlockCount()];
            header = ((SegmentInsertStartedEvent)ce).getHeader();
            statusCode = ENCODING;
            break;

        case SegmentHealingStartedEvent.code:
            // We have started to re-insert unretrievable
            // blocks after a successful decode.
	    int x = retrievedBlockStatus.length;
            reset();
            
            header = ((SegmentHealingStartedEvent)ce).getHeader();
            reinsertions = ((SegmentHealingStartedEvent)ce).getReinsertions();
	    /* Excessive, but the easiest way to handle it
	     * The block number is not the number within the healing set, 
	     * it's the number within the overall array */
	    insertedBlockStatus = new int[x];
            insertedBlockSize = new long[x];
            for (int i = 0; i < x; insertedBlockStatus[i++] = UNDEFINED);
	    
            statusCode = INSERTING_BLOCKS;
            break;
        case SegmentEncodedEvent.code:
            reset();
            header = ((SegmentEncodedEvent)ce).getHeader();
            statusCode = INSERTING_BLOCKS;
            break;
        case SegmentRequestFinishedEvent.code: {
            SegmentRequestFinishedEvent rf = (SegmentRequestFinishedEvent)ce;

	    if(freenet.Core.diagnostics != null) {
		int success = rf.getExitCode() == SplitFileEvent.SUCCEEDED
		    ? 1 : 0;
		freenet.Core.diagnostics.occurrenceBinomial("segmentSuccessRatio",
							    1, success);
	    }
	    	if(header == null) //If the splitfile segment request failed really early we might not even have this 
				statusCode = FAILED;
			else
            if ((header.getSegments() == header.getSegmentNum() + 1) ||
                (rf.getExitCode() != SplitFileEvent.SUCCEEDED)) {
                switch(rf.getExitCode()) {
                    case SplitFileEvent.SUCCEEDED:
                        statusCode = SUCCEEDED; break;
                    case SplitFileEvent.FAILED:
                        statusCode = FAILED; break;
                    case SplitFileEvent.CANCELED:
                        statusCode = CANCELED; break;
                }
            }
            break;
        }
        case BlockQueuedEvent.code:
            queued++;
            BlockEvent be = (BlockEvent) ce;
            statusArray[(be.isData() ? 0 : dataBlocks) + be.index()] = QUEUED;
            break;
        case BlockRequeuedEvent.code: {
            retried++;
            queued++;
            running--;
            BlockRequeuedEvent re = (BlockRequeuedEvent)ce;
            if (re.reason() != null) {
                if (re.reason() instanceof RouteNotFoundEvent) {
                    rnfs++;
                    statusArray[(re.isData() ? 0 : dataBlocks) + re.index()] = REQUEUED_RNF;
                }
                else if (re.reason() instanceof DataNotFoundEvent) {
                    dnfs++;
                    statusArray[(re.isData() ? 0 : dataBlocks) + re.index()] = REQUEUED_DNF;
                }
            } else {
                statusArray[(re.isData() ? 0 : dataBlocks) + re.index()] = REQUEUED;
            }
	    if(re.isRequesting())
		retrievedBlockRetryCount[(re.isData() ? 0 : dataBlocks) + re.index()]++;
            else
		insertedBlockRetryCount[(re.isData() ? 0 : dataBlocks) + re.index()]++;
            break;
        }
        case BlockStartedEvent.code:
            running++;
            queued--;
            BlockStartedEvent se = (BlockStartedEvent) ce;
	    statusArray[(se.isData() ? 0 : dataBlocks) + se.index()] = RUNNING;
	    break;
        case BlockStartedTransferringEvent.code:
            BlockEventWithReason bewr = (BlockEventWithReason) ce;
            TransferStartedEvent tse = (TransferStartedEvent) bewr.reason();
            if (bewr.isRequesting()) {
                retrievedBlockSize[(bewr.isData() ? 0 : dataBlocks) + bewr.index()] = tse.getDataLength();
            } else {
                insertedBlockSize[(bewr.isData() ? 0 : dataBlocks) + bewr.index()] = tse.getDataLength();
            }
            break;
	case BlockFinishedEvent.code: {            
            BlockFinishedEvent fe = (BlockFinishedEvent)ce;
            running--;

	    statusArray[(fe.isData() ? 0 : dataBlocks) + fe.index()] = FAILED;
            if (fe.reason() != null) {
                if (fe.reason() instanceof RouteNotFoundEvent) {
                    rnfs++;
             	    statusArray[(fe.isData() ? 0 : dataBlocks) + fe.index()] = FAILED_RNF;
                }
                else if (fe.reason() instanceof DataNotFoundEvent) {
                    dnfs++;
                    statusArray[(fe.isData() ? 0 : dataBlocks) + fe.index()] = FAILED_DNF;
                } else if (fe.reason() instanceof CollisionEvent) {
                    // Different status code for collisions? /Bombe
                    //statusArray[(fe.isData() ? 0 : dataBlocks) + fe.index()] = SUCCESS;
                }
            }
	    
            if (fe.exitCode() == SplitFileEvent.SUCCEEDED) {
                statusArray[(fe.isData() ? 0 : dataBlocks) + fe.index()] = SUCCESS;
                if (fe.isRequesting()) {
                    if (processed < header.getBlocksRequired()) {
                        retrievedBytes += retrievedBlockSize[(fe.isData() ? 0 : dataBlocks) + fe.index()];
                    }
                } else {
                    insertedBytes += insertedBlockSize[(fe.isData() ? 0 : dataBlocks) + fe.index()];
                }
                processed++;
            }
            else if (fe.exitCode() == SplitFileEvent.FAILED) {
                failed++;
            }
	    if (fe.isRequesting()) {
		retrievedBlockSize[(fe.isData() ? 0 : dataBlocks) + fe.index()] = 0;
	    } // reset so it doesn't always show up as in-transfer
            // Don't count canceled blocks
            break;
	    
        }
        case SegmentDecodingEvent.code:
            statusCode = DECODING;
            break;

        case SegmentEncodingEvent.code:
            statusCode = ENCODING;
            break;

        case VerifyingChecksumEvent.code:
            checksum = ((VerifyingChecksumEvent)ce).getChecksum();
            statusCode = VERIFYING_CHECKSUM;
            break;
        default:
            return;
        }

        notifyAll();
    }
}





