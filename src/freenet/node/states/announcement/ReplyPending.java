package freenet.node.states.announcement;

import freenet.CommunicationException;
import freenet.Core;
import freenet.MessageObject;
import freenet.message.Accepted;
import freenet.message.AnnouncementFailed;
import freenet.message.AnnouncementReply;
import freenet.message.NodeAnnouncement;
import freenet.message.QueryAborted;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.support.Logger;

/**
 * Waiting for a Reply to the message
 */

public class ReplyPending extends AnnouncementState {

    private NoReply nr;

    private boolean accepted;

    public ReplyPending(NewAnnouncement na, NoReply nr) {
        super(na);
        this.nr = nr;
    }

    public String getName() {
        return "Announcement Reply Pending";
    }

    public State received(Node n, MessageObject mo) throws StateException {
        if (mo instanceof NodeAnnouncement) {
            NodeAnnouncement na = (NodeAnnouncement) mo;
            QueryRejected rf = new QueryRejected(id, na.getHopsToLive(), 
            		na.otherFields);
            try {
                n.sendMessage(rf, na.getRef(), getTime(depth));
            } catch (CommunicationException e) {
                Core.logger.log(this, "Failed to send back "+rf+": "+e,
                             Logger.MINOR);
            }
            return this;
        }

        return super.received(n, mo);
        
    }

    public State receivedMessage(Node n, QueryAborted qa) throws BadStateException {
        checkFollowUp(qa);

        try {
            n.sendMessage(qa, lastAddr, getTime(origHopsToLive));
        } catch (CommunicationException e) {
            Core.logger.log(this, "Failed to forward " + qa +": "+e,
                         Logger.MINOR);
        }
	
	terminateRouting(false, false);
        return new AnnouncementDone(this);
    }

    public State receivedMessage(Node n, Accepted a) throws BadStateException {
        checkReply(a);

        if (accepted) 
            throw new BadStateException("Received a second Accepted");
        
        routes.routeSucceeded();  
        // FIXME: this is probably too early..
        
        nr.cancel();
        nr = new NoReply(id);

        n.schedule(getTime(hopsToLive),nr);
        accepted = true;
        
        return this;
    }

    public State receivedMessage(Node n, NoReply noReply) throws BadStateException {

        if (noReply != this.nr) {
            throw new BadStateException("Received the wrong NoReply - probably"
                                        + " accepted NoReply lingering");
        } else {
            Core.logger.log(this,
                "Restarting Announcement chain "+Long.toHexString(id),
                Logger.MINOR);

            routes.searchFailed();
            if (!sendQueryRestarted(n, new QueryRestarted(id))) {
		terminateRouting(false, false);
                return new AnnouncementDone(this);
            }
            try {
                n.sendMessage(new QueryAborted(id), lastAddr,
			      getTime(origHopsToLive));
            } catch (CommunicationException e) {
                // so that is why he didn't reply....
            }

            
            NodeAnnouncement na = new NodeAnnouncement(id, hopsToLive, depth, 
                                                       n.getNodeReference(), announcee, 
                                                       commitVal); 
       
            return (new NewAnnouncement(this)).sendOn(n, na);         
        }
    }

    public State receivedMessage(Node n, QueryRejected qr) 
        throws BadStateException {
        checkReply(qr);

	if(Core.logger.shouldLog(Logger.DEBUG,this))
	    Core.logger.log(this, "Received QueryRejected on "+Long.toHexString(id)+
			 " ("+qr.hopsToLive+","+qr.getReason()+")", Logger.DEBUG);
        
        nr.cancel();

        if (!sendQueryRestarted(n, new QueryRestarted(id))) {
	    terminateRouting(false, false);
            return new AnnouncementDone(this);
        }
            
        NodeAnnouncement na = new NodeAnnouncement(id, hopsToLive, depth, 
                                                   n.getNodeReference(), announcee, 
                                                   commitVal, qr.otherFields);
        return (new NewAnnouncement(this)).sendOn(n, na);         
    }

    public State receivedMessage(Node n, QueryRestarted qr) 
        throws BadStateException {
        checkReply(qr);

        nr.cancel();
        
        nr = new NoReply(id);
        n.schedule(getTime(hopsToLive), nr);

        if (!sendQueryRestarted(n, qr)) {
	    terminateRouting(false, false);
            return new AnnouncementDone(this);
	} else
            return this;
    }

    public State receivedMessage(Node n, AnnouncementFailed af) 
        throws BadStateException {
        checkReply(af);

        nr.cancel();
        Core.logger.log(this, "Announcement failed for reason " + af.getReason()
                     + "at later node", Logger.DEBUG);
        try {
            n.sendMessage(af, origRec, getTime(origHopsToLive));
        } catch (CommunicationException e) {
            Core.logger.log(this, "Failed to send AnnouncementFailed",
                         e, Logger.MINOR);
        }
	terminateRouting(false, false);
        return new AnnouncementDone(this);
    }

    public State receivedMessage(Node n, AnnouncementReply ar) throws BadStateException {
        checkReply(ar);

        nr.cancel();
        
        byte[] returnVal = ar.getReturnValue();
        
        for (int i = 0 ; i < myVal.length && i < returnVal.length; i++) {
            returnVal[i] ^= myVal[i];
        }
	
        // note side effect on message
	
        try {
            n.sendMessage(ar, origRec, getTime(origHopsToLive));
        } catch (CommunicationException e) {
            Core.logger.log(this, "Failed to send AnnouncementReply", e,
                         Logger.MINOR);
        }
	
        NoExecute ne = new NoExecute(id);
        n.schedule(getTime(depth), ne);
        return new ExecutePending(this, returnVal, ne);
        //        return null;
    }

    private boolean sendQueryRestarted(Node n, QueryRestarted qr) {
        try {
            totalRestarts++;
            if (totalRestarts > MAX_RESTARTS) {
                Core.logger.log(this, "Failing announcement after too many " +
                             " restarts.", Logger.MINOR);
                try {
                    n.sendMessage(new AnnouncementFailed(id,
                                       AnnouncementFailed.TOO_MANY_RESTARTS),
                                  origRec, getTime(origHopsToLive));
                } finally {
                    n.sendMessage(new QueryAborted(id), lastAddr,
				  getTime(origHopsToLive));
                }
                return false;
            }
            
            n.sendMessage(qr, origRec, getTime(origHopsToLive));

            return true;
        } catch (CommunicationException e) {
            Core.logger.log(this, "Failed to restart query",
                         e, Logger.MINOR);
            return false;
        }
    }
}







