package freenet.client.events;

/**
 * @author giannij
 */

import freenet.client.ClientEvent;
import freenet.message.client.FEC.SegmentHeader;

public class BlockRestartedEvent extends BlockEventWithReason {
    public static final int code = 0x44;
    
    public BlockRestartedEvent(SegmentHeader header, boolean downloading, int index, boolean isData, int htl,
                              ClientEvent reason) {
        super(header, downloading, index, isData, htl, reason);
    }

    public final String getDescription() { 
        String seconds = "";
        if ((reason() != null) && (reason() instanceof RestartedEvent)) {
            seconds = ", waiting up to " + Integer.toString(((RestartedEvent)reason()).getTime()) +
                " seconds.";
        }
        String text = isRequesting() ? "request for" : "insert of";
        return formatMsg("Restarted " + text ) + seconds; 
    }
    public final int getCode() { return code; }
}
