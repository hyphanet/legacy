package freenet.node.states.announcement;
import freenet.CommunicationException;
import freenet.Core;
import freenet.Key;
import freenet.message.AnnouncementComplete;
import freenet.message.AnnouncementExecute;
import freenet.message.AnnouncementFailed;
import freenet.message.QueryAborted;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.KeyList;
import freenet.support.Logger;
/**
 * The state object for terminating nodes in Announcement chains, 
 * which only need to wait for an reply to the AnouncementExecute.
 */

public class LastNode extends ExecuteHandler {

    public LastNode(AnnouncementState as, NoExecute ne) {
        super(as, ne, bCopy(as.myVal));
	terminateRouting(false, false);
    }

    private static byte[] bCopy(byte[] b) {
        byte[] r = new byte[b.length];
        System.arraycopy(b, 0, r, 0, b.length);
        return r;
    }

    public String getName() {
        return "Last Announcement Node";
    }

    public State receivedMessage(Node n, AnnouncementExecute ae) 
        throws BadStateException {
        checkFollowUp(ae);

        Key k = executeAnnounce(n, ae);

        if (k != null) { 

            sendComplete(n, k);

            // insert into Routing table
            if(announcee.isSigned())
                n.reference(k, null, announcee, null);
            // Build an absurdly optimistic initially specialized estimator
            
            return new AnnouncementDone(this);
            
        } else {

            AnnouncementFailed af = 
                new AnnouncementFailed(id,
                                       AnnouncementFailed.CORRUPT_EXECUTE);
            try {
                n.sendMessage(af, origRec, getTime(origHopsToLive));
            } catch (CommunicationException sfe) {
                Core.logger.log(this, "Failed to send AnnouncementFailed",
                             sfe, Logger.MINOR);
            }

            return new AnnouncementDone(this);
        }

    }

    public void sendComplete(Node n, Key k) {
        // find closest previous entry
        
        KeyList keys = new KeyList(n.ds.findClosestKeys(k, true, depth));
        
        /*
        System.err.println("LastNode - sending this key list:");
        try {
            keys.writeTo(System.err);
        }
        catch (java.io.IOException e) {
            e.printStackTrace();
        }
        */
                
        AnnouncementComplete ac = new AnnouncementComplete(id, keys);
        try {
            sendMessage(n, origRec, ac, keys, getTime(origHopsToLive));
        } catch (CommunicationException e) {
            Core.logger.log(this, "Failed to send AnnouncementComplete",
                         e, Logger.MINOR);
        }
    }

    public State receivedMessage(Node n, NoExecute noExecute) {

        Core.logger.log(this, "Did not receive AnnouncementExecute message, " +
                     "dropping chain", Logger.MINOR);

        return new AnnouncementDone(this);

    }

    public State receivedMessage(Node n, QueryAborted qa) 
        throws BadStateException {
        checkFollowUp(qa);

        this.ne.cancel();

        Core.logger.log(this, "Announcement query aborted by previous node",
                     Logger.DEBUG);
       
        return new AnnouncementDone(this);
    }

}




