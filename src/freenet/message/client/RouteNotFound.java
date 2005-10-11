package freenet.message.client;

/**
 * This is the FCP RouteNotFound message.
 */
public class RouteNotFound extends ClientMessage {

    public static final String messageName = "RouteNotFound";
    
    public RouteNotFound(long id, String reason, int unreachable,
                         int restarted, int rejected, int backedOff) {
        super(id, reason);
        if (unreachable > 0)
            otherFields.put("Unreachable", Integer.toHexString(unreachable));
        if (restarted > 0)
            otherFields.put("Restarted", Integer.toHexString(restarted));
        if (rejected > 0)
            otherFields.put("Rejected", Integer.toHexString(rejected));
        if (backedOff > 0)
            otherFields.put("BackedOff", Integer.toHexString(backedOff));
    }

    public final String getMessageName() {
        return "RouteNotFound";
    }
}
