package freenet.message.client;

/**
  * This is the general FCP response for unintelligible messages.
  */
public class FormatError extends ClientMessage {

    public static final String messageName = "FormatError";

    public FormatError(long id, String reason) {
        super(id, reason);
    }

    public String getMessageName() {
        return messageName;
    }
}
