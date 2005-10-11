package freenet.message.client;

import freenet.FieldSet;

/**
 * This is the FCP Restarted message.
 */
public class Restarted extends ClientMessage {

    public static final String messageName = "Restarted";
    private final String reason;
    
    public Restarted(long id, long millis, String reason) {
        super(id, new FieldSet());
        otherFields.put("Timeout", Long.toHexString(millis));
        close = false;
        this.reason = reason;
        otherFields.put("Reason", reason);
    }

    public String getMessageName() {
        return messageName;
    }
}
