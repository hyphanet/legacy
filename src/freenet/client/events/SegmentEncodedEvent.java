package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class SegmentEncodedEvent extends SplitFileEvent  {
    public static final int code = 0x38; // REDFLAG: reorder.

    private int dataCount;
    private int checkCount;

    public SegmentEncodedEvent(SegmentHeader header, boolean downloading, int numData, int numChecks) {
        super(header, downloading);
        this.dataCount = numData;
        this.checkCount = numChecks;
    }

    public final String getDescription() { 
        return "Encoded SplitFile segment (" 
            + (getHeader().getSegmentNum() + 1 )  + "/" + getHeader().getSegments() + ")" +
            " making " + checkCount + " check blocks from " + dataCount + " data blocks... ";
    }
    
    public final int getDataCount() { return dataCount; }
    public final int getCheckCount() { return checkCount; }

    public final int getCode() { return code; }
}
