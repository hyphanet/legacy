package freenet.client.events;

/**
 * Insert pending notification for blocks.
 * @author giannij
 */

import freenet.client.ClientEvent;
import freenet.message.client.FEC.SegmentHeader;

public class BlockPendingEvent extends BlockEventWithReason {
    public static final int code = 0x47;

    public BlockPendingEvent(SegmentHeader header, boolean downloading, int index, boolean isData, int htl,
                             ClientEvent reason) {
        super(header, downloading, index, isData, htl, reason);
    }
    public final String getDescription() { 
        String info = "";
        if ((reason() != null) && (reason() instanceof PendingEvent)) {
            PendingEvent pe = (PendingEvent)reason();
            info = " Waiting up to "
               + pe.getTime() + " seconds for the StoreData.";
        }
        String text = isRequesting() ? "request for" : "insert of";
        return formatMsg("Pending " + text) + info; 
    }
    public final int getCode() { return code; }
}


