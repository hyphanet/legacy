package freenet.message.client;

import freenet.FieldSet;

/**
 * This is the FCP KeyCollision message.
 */
public class KeyCollision extends ClientMessage {

    public static final String messageName = "KeyCollision";
    
    public KeyCollision(long id, FieldSet fs) {
        super(id, fs);
    }

    public String getMessageName() {
        return messageName;
    }
}
