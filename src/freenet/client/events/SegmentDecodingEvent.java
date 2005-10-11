package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class SegmentDecodingEvent extends SplitFileEvent  {
    public static final int code = 0x31;

    private int dataCount;
    private int checkCount;
    private int encodeCount;

    // REDFLAG: Why would downloading ever != true?
    public SegmentDecodingEvent(SegmentHeader header, boolean downloading, 
                                int numData, int numChecks, int encodeCount) {
        super(header, downloading);
        this.dataCount = numData;
        this.checkCount = numChecks;
        this.encodeCount = encodeCount;
    }

    public final String getDescription() { 
        return "Decoding SplitFile segment (" 
            + (getHeader().getSegmentNum() + 1 )  + "/" + getHeader().getSegments() + ")" +
            " from " + dataCount + " data blocks and " + checkCount + 
            " check blocks, re-encoding " + encodeCount + " check blocks..." ;
    }
    
    public final int getDataCount() { return dataCount; }
    public final int getCheckCount() { return checkCount; }
    
    /**
     * The number of unretrievable check blocks that 
     * are being reconstructed so they can be
     * re-inserted to heal the network.
     **/
    public final int getEncodeCount() { return encodeCount; }

    public final int getCode() { return code; }
}
