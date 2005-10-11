package freenet.client.events;
import freenet.client.*;
import freenet.*;
/**
 * A ReceiveEvent is generated when a message is received from the
 * Freenet node.  
 *
 * @author oskar
 **/

public class ReceiveEvent extends ConnectionEvent {
    public static final int code = 0x02;
    
    public ReceiveEvent(Peer source, ClientMessageObject m, String comment) {
        super(source,m,comment);
    }
    
    public String getDescription() {
        return "A " + messageName + " message was received from " + peer + 
            (comment.length()==0 ? "." : (" - " + comment));
    }
    
    public int getCode() {
        return code;
    }

    /**
     * Returns the name of the message received
     */
    public String getMessageName() {
        return messageName;
    }
}
