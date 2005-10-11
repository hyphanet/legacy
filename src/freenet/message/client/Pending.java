package freenet.message.client;

import freenet.FieldSet;

/**
 * This is the FCP message for inserts waiting for transfer & StoreData.
 */
public class Pending extends ClientMessage {

    public static final String messageName = "Pending";
    
    public Pending(long id, FieldSet fs) {
        super(id, fs);
        close = false;
    }

    public String getMessageName() {
        return messageName;
    }
}
