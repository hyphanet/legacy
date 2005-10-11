package freenet.client.events;

/**
 * Base class for SplitFile events.
 * @author giannij
 */

import freenet.client.ClientEvent;
import freenet.message.client.FEC.SegmentHeader;

public abstract class SplitFileEvent implements ClientEvent{
    private SegmentHeader header;

    private boolean requesting = true;

    protected SplitFileEvent(SegmentHeader header, boolean requesting) {
        this.header = header;
        this.requesting = requesting;
    }

    public final SegmentHeader header() { return header; }

    public final static int SUCCEEDED = 1;
    public final static int FAILED = 2;
    public final static int CANCELED = 3;

    public final static String exitCodeToString(int ec) {
        switch (ec) {
        case SUCCEEDED: return "SUCCEEDED";
        case FAILED:    return "FAILED";
        case CANCELED: return "CANCELLED";
        default: return "UNKNOWN EXIT CODE!";
        }
    }
    
    public final boolean isRequesting() { return requesting; }
    public final SegmentHeader getHeader() { return header; }

    public abstract String getDescription();
    public abstract int getCode();
}




