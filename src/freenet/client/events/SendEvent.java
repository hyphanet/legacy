package freenet.client.events;
import freenet.Message;
import freenet.Peer;

/**
 * The SendEvent is generated when the library sends a message
 * to the Freenet node.
 *
 * @author oskar
 **/
public class SendEvent extends ConnectionEvent {
    public static final int code = 0x01;
    
    public SendEvent(Peer target, Message m, String comment) {
        super(target,m,comment);
    }

    public String getDescription() {
        return "A " + messageName + " message was sent to " + peer + 
            (comment.length()==0 ? "." : (" - " + comment));
    }

    /**
     * Returns the name of the message sent to the node
     */    
    public String getMessageName() {
        return messageName;
    }

    public int getCode() {
        return code;
    }
}
