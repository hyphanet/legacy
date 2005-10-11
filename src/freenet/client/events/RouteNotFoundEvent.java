package freenet.client.events;

/**
 * @author <a href="mailto: rrkapitz@stud.informatik.uni-erlangen.de">Ruediger Kapitza</a>
 * @version
 */

import freenet.client.ClientEvent;

public class RouteNotFoundEvent implements ClientEvent{
    
    public static final int code = 0x09;
    
    private final String desc;
    private final int unreachable, restarted, rejected, backedOff;
    
    public RouteNotFoundEvent(String reason, int unreachable,
                              int restarted, int rejected, int backedOff) {
        if (reason == null)
            desc = "RouteNotFound (no reason given)";
        else
            desc = "RouteNotFound, reason: "+reason;
        this.unreachable = unreachable;
        this.restarted = restarted;
        this.rejected = rejected;
        this.backedOff = backedOff;
    }
    
    public final String getDescription() {
        return desc;
    }
    
    public final int getUnreachable() {
        return unreachable;
    }

    public final int getRestarted() {
        return restarted;
    }

    public final int getRejected() {
        return rejected;
    }
    
    public final int getBackedOff() {
        return backedOff;
    }
    
    public final int getCode() {
        return code;
    }
    
}// RouteNotFoundEvent
