package freenet.message.client;

import freenet.FieldSet;

/**
 * This is the FCP SizeError message.
 */
public class SizeError extends ClientMessage {

    public static final String messageName = "SizeError";
    
    public SizeError(long id, String comment) {
        super(id, new FieldSet());
        otherFields.put("Comment", comment);
    }

    public String getMessageName() {
        return messageName;
    }
}
