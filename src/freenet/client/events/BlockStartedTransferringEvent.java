package freenet.client.events;

/**
 * @author giannij
 */

import freenet.client.ClientEvent;
import freenet.client.events.StreamEvent;
import freenet.message.client.FEC.SegmentHeader;

public class BlockStartedTransferringEvent extends BlockEventWithReason  {
    public static final int code = 0x49;
    
    public BlockStartedTransferringEvent(SegmentHeader header, boolean downloading,
					 int index, boolean isData, int htl,
					 ClientEvent reason) {
        super(header, downloading, index, isData, htl, reason);
    }
    
    public final String getDescription() { 
        String progress = "";
        if (reason() != null && reason() instanceof StreamEvent) {
            progress = " " + Long.toString( ((StreamEvent)reason()).getProgress()) + " bytes";
        }
        return formatMsg("Transferring started event for") + progress;
    }
    public final int getCode() { return code; }
    
}



