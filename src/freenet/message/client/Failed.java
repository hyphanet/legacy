package freenet.message.client;

/**
 * This is the FCP message for failures.
 */
public class Failed extends ClientMessage {

    public static final String messageName = "Failed";

    public Failed(long id, String reason) {
        super(id, reason);
    }

    public String getMessageName() {
        return messageName;
    }
}
