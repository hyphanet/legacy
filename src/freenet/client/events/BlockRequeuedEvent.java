package freenet.client.events;

/**
 * @author giannij
 */

import freenet.client.ClientEvent;
import freenet.message.client.FEC.SegmentHeader;

public class BlockRequeuedEvent extends BlockEventWithReason {
    public static final int code = 0x43;

    public BlockRequeuedEvent(SegmentHeader header, boolean downloading, int index, boolean isData, int htl,
                              ClientEvent reason) {
        super(header, downloading, index, isData, htl, reason);
    }
    public final String getDescription() { 
        String text = isRequesting() ? "request for" : "insert of";
        return formatMsg("Requeued " + text); 
    }
    public final int getCode() { return code; }
}

