package freenet.node.states.announcement;
import freenet.node.EventMessageObject;

/**
 * Message object scheduled to warn if no AnnouncementExecute is received.
 * 
 * @author oskar
 */
public class NoExecute extends EventMessageObject {

    public NoExecute(long id) {
        super(id, true);
    }

}
