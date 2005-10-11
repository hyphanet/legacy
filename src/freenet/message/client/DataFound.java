package freenet.message.client;

import freenet.FieldSet;

/**
 * This is the FCP DataFound message.
 */
public class DataFound extends ClientMessage {

    public static final String messageName = "DataFound";
    
    public DataFound(long id, FieldSet fs) {
        super(id, fs);
        close = false;
    }

    public String getMessageName() {
        return messageName;
    }
}
