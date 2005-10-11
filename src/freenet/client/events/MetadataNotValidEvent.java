package freenet.client.events;
import freenet.client.*;

/** This happens when invalid metadata is encountered. */
public class MetadataNotValidEvent implements ClientEvent {
    
    public static final int code = 0xA1;

    public String getDescription() {
        return "Invalid metadata encountered.";
    }

    public int getCode() {
        return code;
    }
}
