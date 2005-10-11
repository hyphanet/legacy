package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class SegmentInsertStartedEvent extends SplitFileEvent  {
    public static final int code = 0x36;

    public SegmentInsertStartedEvent(SegmentHeader header, boolean downloading) {
        super(header, downloading);
    }

    public final String getDescription() { 
        return "SplitFile segment insert started [" 
            + (getHeader().getSegmentNum() + 1) + "/" + getHeader().getSegments() + "]";
    }

    public final int getCode() { return code; }
}
