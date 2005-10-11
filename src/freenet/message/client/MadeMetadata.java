package freenet.message.client;

import freenet.FieldSet;

/**
 * FCP message to tell client that FEC encoding 
 * completed successfully.
 */
public class MadeMetadata extends ClientMessage {

    public static final String messageName = "MadeMetadata";
    
    // To wire
    public MadeMetadata(long id, long size) {
        super(id, new FieldSet());
        otherFields.put("DataLength", Long.toString(size,16));
        close = false;
    }

    public String getMessageName() {
        return messageName;
    }
}



