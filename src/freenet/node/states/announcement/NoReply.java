package freenet.node.states.announcement;
import freenet.node.EventMessageObject;

/**
 * Message object scheduled to warn if no AnnouncementReply is received.
 * 
 * @author oskar
 */
public class NoReply extends EventMessageObject {

    public NoReply(long id) {
        super(id, true);
    }

}


