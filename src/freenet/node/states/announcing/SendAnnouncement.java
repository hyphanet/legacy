package freenet.node.states.announcing;

import freenet.CommunicationException;
import freenet.Core;
import freenet.MessageObject;
import freenet.message.NodeAnnouncement;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.NodeMessageObject;
import freenet.node.NodeReference;
import freenet.node.State;
import freenet.node.states.announcement.NoReply;
import freenet.support.Logger;

/**
 * The state that an announcement starts off with at Alice. 
 *
 * @author oskar
 */

public class SendAnnouncement extends AnnouncingState {
    
    /**
     * @param n       The node to schedule with.
     * @param id      Aggregate chain ID
     * @param target  The node to send the announcement to.
     * @param htl     The number of hops that the announcement should make,
     *                make sure it is not more than what is tolerated by the 
     *                network.
     */
    static State makeTry(Node n, long id, NodeReference target, int htl) {

        if (htl > Node.maxHopsToLive) {
            Core.logger.log(SendAnnouncement.class,
                "Reducing announcement HTL from "+htl
                +" to max of "+Node.maxHopsToLive, Logger.DEBUG);
            htl = Node.maxHopsToLive;
        }
        
        SendAnnouncement sa = new SendAnnouncement(id, htl, target);
        sa.schedule(n, 0);
        return sa;
    }

    private class SendAnnouncementMessage implements NodeMessageObject {
        public long id() {
            return id;
        }
        public boolean isExternal() {
            return true;
        }
        public void drop(Node n) {
            // hmm..
        }
        public State getInitialState() {
            return SendAnnouncement.this;
        }
        private boolean belongsTo(SendAnnouncement sa) {
            return sa == SendAnnouncement.this;
        }

        public String toString() {
            return "Announce to: " + target;
        }
    }



    private SendAnnouncement(long id, int htl, NodeReference target) {
        super(id, htl, target);
    }

    public String getName() {
        return "Send My Node Announcement";
    }

    private void schedule(Node n, long time) {
        
        n.schedule(time, new SendAnnouncementMessage());
    }
    
    public boolean receives(MessageObject mo) {
        return mo instanceof SendAnnouncementMessage
            && ((SendAnnouncementMessage) mo).belongsTo(this);
    }

    public State receivedMessage(Node n, SendAnnouncementMessage mo)
                                            throws BadStateException {
        if (!mo.belongsTo(this))
            throw new BadStateException("Not my SendAnnouncementMessage");
                                                
        // Our random commit value
        myVal = new byte[20];
        Core.getRandSource().nextBytes(myVal);

        byte[] commitVal;
        
        synchronized (ctx) {
            ctx.update(myVal);
            commitVal = ctx.digest();
        }
        
        try {
            // Put into the RT so that we can queue a message to send to it
            // Will also start a connection opener to it
            n.reference(null, null, target, null);
            n.sendMessage(new NodeAnnouncement(id, hopsToLive, 0, n.getNodeReference(),
                                               n.getNodeReference(), commitVal), target,
			  getTime(2));
	    
            
            // Count outbound requests.
            Core.diagnostics.occurrenceBinomial("outboundAggregateRequests", 1, 1);
            if (Core.outboundRequests != null) {
                Core.outboundRequests.incTotal(target.firstPhysicalToString());
            }
	    
            // Keep track of outbound requests for rate limiting.
	    if(Node.outboundRequestLimit != null)
		Node.outboundRequestLimit.inc();
	    
            NoReply nr = new NoReply(id);
            n.schedule(getTime(2), nr);
            return new ExecuteAnnouncement(this, nr);
        } catch (CommunicationException e) {
            Core.diagnostics.occurrenceBinomial("outboundAggregateRequests", 1, 0);
            Core.logger.log(this, "Sending NodeAnnouncement failed: "+e,
                         Logger.MINOR);
                         //e, Logger.MINOR);

            n.schedule(0, new Completed(id, target.getIdentity(), hopsToLive,
                                        false, e.toString()));
            
	    // Don't reduce HTL because it doesn't impact *sending* the message
	    return null;
        }
    }
}
