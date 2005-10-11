package freenet.node.states.announcement;
import freenet.node.EventMessageObject;

/**
 * Message object scheduled to warn if no AnnouncementComplete is received.
 * 
 * @author oskar
 */
public class NoComplete extends EventMessageObject {

    public NoComplete(long id) {
        super(id, true);
    }

}
