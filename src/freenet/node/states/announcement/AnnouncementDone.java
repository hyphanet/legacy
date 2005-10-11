package freenet.node.states.announcement;
import freenet.*;
import freenet.node.*;
import freenet.support.Logger;
import freenet.message.*;
/**
 * To make sure we don't get Announcements we have already rejected.
 *
 * @author oskar
 */

public class AnnouncementDone extends AnnouncementState {

    public AnnouncementDone(AnnouncementState st) {
        super(st);
	terminateRouting(false, false);
    }
    
    public String getName() {
        return "Announcement Done";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof NodeAnnouncement)) {
            if(mo instanceof NodeMessage)
                ((NodeMessage) mo).drop(n);
            throw new BadStateException("This announcement has ended");
        } else {
            NodeAnnouncement na = (NodeAnnouncement) mo;
            QueryRejected rf = new QueryRejected(id, na.getHopsToLive(), 
            		na.otherFields);
            try {
                n.sendMessage(rf, na.getRef(), getTime(depth));
            } catch (CommunicationException e) {
                Core.logger.log(this, "Failed to send back " + rf, Logger.MINOR);
            }
            return this;
        } 
    }

    public int priority() {
        return EXPENDABLE;
    }

    public void lost(Node n) {
        // good
    }
}
