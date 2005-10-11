package freenet.message.client;

import freenet.FieldSet;

/**
 * This is the FCP message for successes.
 */
public class Success extends ClientMessage {

    public static final String messageName = "Success";
    
    public Success(long id, FieldSet fs) {
        super(id, fs);
    }

    public String getMessageName() {
        return messageName;
    }
}
