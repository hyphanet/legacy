package freenet.message.client;

/**
 * This is the FCP URIError message.
 */
public class URIError extends ClientMessage {

    public static final String messageName = "URIError";
    
    public URIError(long id, String reason) {
        super(id, reason);
    }

    public String getMessageName() {
        return messageName;
    }
}
