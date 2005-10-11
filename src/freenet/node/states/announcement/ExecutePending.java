package freenet.node.states.announcement;
import freenet.CommunicationException;
import freenet.Core;
import freenet.Key;
import freenet.Message;
import freenet.MessageObject;
import freenet.message.AnnouncementExecute;
import freenet.message.AnnouncementFailed;
import freenet.message.QueryAborted;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.support.Logger;

/**
 * Waiting for a Execute on a reply
 */

public class ExecutePending extends ExecuteHandler {


    public ExecutePending(ReplyPending rp, byte[] returnVal, NoExecute ne) {
        super(rp, ne, returnVal);
    }

    public String getName() {
        return "Announcement Execute Pending";
    }

    public State received(Node n, MessageObject mo) throws StateException {
        // we only want messages from origRec
        if (mo instanceof Message &&
            !origRec.equalsIdent(((Message) mo).peerIdentity())) {
            
            throw new BadStateException("Got message " 
                                        + mo.getClass().getName() +
                                        " from wrong peer");
        } 

        return super.received(n, mo);
    }


    public State receivedMessage(Node n, NoExecute noExecute) 
        throws BadStateException {
        
        if (noExecute != this.ne) {
            throw new BadStateException("Got the wrong NoExecute message");
        }
	Core.logger.log(this, "ExecutePending got NoExecute "+ne,
		     Logger.NORMAL);
        try {
            n.sendMessage(new QueryAborted(id), lastAddr, 
			  getTime(depth)); 
        } catch (CommunicationException e) {
            Core.logger.log(this, "Failed to send QueryAborted after no Execute",
                         e, Logger.DEBUG);
        }

        try {
            n.sendMessage(
                new AnnouncementFailed(id, AnnouncementFailed.NO_EXECUTE),
                origRec, getTime(origHopsToLive));
	    
        } catch (CommunicationException e) {
            Core.logger.log(this, 
                         "Failed to send AnnouncementFailed after no Execute",
                         e, Logger.DEBUG);
        }

	terminateRouting(false, false);
        return new AnnouncementDone(this);
    }

    public State receivedMessage(Node n, QueryAborted qr) {

        Core.logger.log(this, "Announcement Aborted by previous node",
                     Logger.DEBUG);

        ne.cancel();
        try {
            n.sendMessage(qr, lastAddr, getTime(depth));
        } catch (CommunicationException e) {
            Core.logger.log(this, "Failed to send QueryAborted after no Execute",
                         e, Logger.DEBUG);
        }
	
	terminateRouting(false, false);
        return new AnnouncementDone(this);
    }

    public State receivedMessage(Node n, AnnouncementExecute ae) 
        throws StateException {
        // We are in business!

        Key k = executeAnnounce(n, ae);

        if (k != null) {
            NoComplete nc = new NoComplete(id);
            CompletePending cp = new CompletePending(this, k, nc);

            try {
                sendMessage(n, lastAddr, ae, ae.getKeys(),
			    getTime(depth));
                n.schedule(getTime(hopsToLive)*3, nc);
		// LONG timeout, announcements should be quite tolerant
                return cp;
            } catch (CommunicationException e) {
                Core.logger.log(this, "Failed to send AnnouncementComplete - "
                             + "pretending like I never got it back.",
                             e, Logger.MINOR);
                // well, we aren't going to get a Complete, so...
                return cp.received(n, nc);
            }
        } else {
	    terminateRouting(false, false);
            QueryAborted qa = new QueryAborted(id);
            AnnouncementFailed af = 
                new AnnouncementFailed(id,
                                       AnnouncementFailed.CORRUPT_EXECUTE);
            try {
                n.sendMessage(qa, lastAddr, getTime(depth));
            } catch (CommunicationException e) {
                Core.logger.log(this, "Failed to send QueryAborted after bad AE.",
                             Logger.MINOR);
            }
            try {
                n.sendMessage(af, origRec, getTime(origHopsToLive));
            } catch (CommunicationException e) {
                Core.logger.log(this, "Failed to send AnnouncementFailed after " 
                             + "bad AE.", Logger.MINOR);
            }

            return new AnnouncementDone(this);
        }
    }
}
