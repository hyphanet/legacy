package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class SegmentRequestStartedEvent extends SplitFileEvent  {
    public static final int code = 0x30;

    private int segmentNr;
    
    public SegmentRequestStartedEvent(SegmentHeader header, boolean downloading,
                                      int segmentNr) {
        super(header, downloading);
        this.segmentNr = segmentNr;
    }
    
    public final int getSegmentNr() { return segmentNr; }

    public final String getDescription() { 
        return "SplitFile segment request started [" 
            + (getHeader().getSegmentNum() + 1) + "/" + getHeader().getSegments() + "]";
    }

    public final int getCode() { return code; }
}
