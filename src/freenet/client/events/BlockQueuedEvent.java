package freenet.client.events;

/**
 * @author giannij
 */

import freenet.message.client.FEC.SegmentHeader;

public class BlockQueuedEvent extends BlockEvent  {
    public static final int code = 0x41;
    
    public BlockQueuedEvent(SegmentHeader header, boolean downloading, 
                            int index, boolean isData, int htl) {
        super(header, downloading, index, isData, htl);
    }

    public final String getDescription() { return formatMsg("Queued"); }
    public final int getCode() { return code; }
}
