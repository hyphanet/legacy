package freenet.client.cli;

import freenet.client.ClientEvent;
import freenet.client.SplitFileStatus;
import freenet.client.events.BlockFinishedEvent;
import freenet.client.events.SegmentDecodingEvent;
import freenet.client.events.SegmentEncodingEvent;
import freenet.client.events.SegmentHealingStartedEvent;
import freenet.client.events.SegmentRequestStartedEvent;
import freenet.client.events.SplitFileEvent;
import freenet.client.events.VerifyingChecksumEvent;
import freenet.support.Logger;

/**
 * A SplitFileStatus subclass which dumps messages to
 * stdout for the CLI client implementation.
 * <p>
 * @author giannij
 */
public class CLISplitFileStatus extends SplitFileStatus {
    private Logger log;

    public CLISplitFileStatus(Logger log) {
        this.log = log;
    }

    public synchronized void dumpStatus() {
        if (header == null) {
            return;
        }

        int denom = header.getBlocksRequired();
        if (inserting) {
            denom = header.getBlockCount() + header.getCheckBlockCount();
        }
        else if (reinsertions > 0) {
            // Handles healing insertions while requesting.
            denom = reinsertions;
        }

        if ((statusCode == STARTING) ||
            (statusCode == ENCODING) ||
            (statusCode == DECODING)) {
            // No block stats
            log.log(this, statusString(statusCode) + " [" + (header.getSegmentNum() + 1) +
                        "/" + (header.getSegments()) + "]", Logger.NORMAL);
        }
        else if (statusCode == VERIFYING_CHECKSUM) {
            // Print out the checksum too
            log.log(this, statusString(statusCode) + " [" + (header.getSegmentNum() + 1) +
                        "/" + (header.getSegments()) + "]: " + checksum, Logger.NORMAL);
        }
        else {
            String fullStats = " (" + processed +  "/" + denom + "):[" + (header.getSegmentNum() + 1) +
                "/" + (header.getSegments()) + "] queued: " + queued +
                " running: " + running;
            log.log(this, statusString(statusCode) + fullStats, Logger.NORMAL);
        }

    }

    public synchronized void receive(ClientEvent ce) {
        if (!(ce instanceof SplitFileEvent)) {
            return;
        }
        super.receive(ce);
        switch (ce.getCode()) {
        case SegmentRequestStartedEvent.code: 
            // Will get a SegmentEncodingEvent immediately after.
            //case SegmentInsertStartedEvent.code: 
        case BlockFinishedEvent.code: 
        case SegmentDecodingEvent.code:
        case SegmentEncodingEvent.code:
        case SegmentHealingStartedEvent.code:
        case VerifyingChecksumEvent.code:
            dumpStatus();
            break;
        default: 
            // NOP
        }
    }
}





