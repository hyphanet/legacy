package freenet.message.client;

/**
 * This is the FCP DataNotFound message.
 */
public class DataNotFound extends ClientMessage {

    public static final String messageName = "DataNotFound";
    
    public DataNotFound(long id) {
        super(id);
    }

    public String getMessageName() {
        return messageName;
    }
}
