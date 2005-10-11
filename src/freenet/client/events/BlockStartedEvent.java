package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class BlockStartedEvent extends BlockEvent  {
    public static final int code = 0x42;
    
    public BlockStartedEvent(SegmentHeader header, boolean downloading, int index, boolean isData, int htl) {
        super(header, downloading, index, isData, htl);
    }

    public final String getDescription() {
        String text = isRequesting() ? "requesting" : "inserting";
        return formatMsg("Started " + text); 
    }
    public final int getCode() { return code; }
}
