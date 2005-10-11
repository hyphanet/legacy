package freenet.node.states.announcement;

import java.io.IOException;
import java.io.OutputStream;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Message;
import freenet.Peer;
import freenet.SendFailedException;
import freenet.TrailerWriter;
import freenet.TrailerWriterOutputStream;
import freenet.crypt.Digest;
import freenet.crypt.SHA1;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.State;
import freenet.node.rt.Routing;
import freenet.support.KeyList;

/**
 * A base class for the announcement states.
 *
 * @author oskar
 */

public abstract class AnnouncementState extends State {

    static final int MAX_RESTARTS = 100; 
    // 10 hops, each routes to 11 available nodes, 10 of which reject
    
    static final int MAX_ROUTING_TIMES = 5;
    
    static final Digest ctx = SHA1.getInstance();
    
    NodeReference announcee;
    int depth;
    int hopsToLive;
    final int origHopsToLive;
    byte[] commitVal;

    byte[] myVal;
    Peer origRec;
    Peer lastAddr;
    Routing routes;

    int routed;
    int totalRestarts;

    /**
     * Create a new AnnouncementState. 
     * @param id         The identity of the chain.
     * @param announcee  The node that is being announced.
     * @param depth      The depth of the announcement at this node.
     * @param hopsToLive The number of hopsToLive left of the announcement
     *                   at this node.
     * @param commitVal  The binary value that the previous node commits to.
     */
    AnnouncementState(long id, NodeReference announcee,
                      int depth, int hopsToLive, byte[] commitVal) {
        super(id);
        this.announcee = announcee;
        this.depth = depth;
        this.hopsToLive = hopsToLive;
        this.commitVal = commitVal;
        this.routed = 0;
        this.totalRestarts = 0;
	this.origHopsToLive = hopsToLive;
    }

    /**
     * Creates another AnnouncementState duplicating the values of a 
     * previous one.
     * @param  as   The previous AnnouncementState
     */
    AnnouncementState(AnnouncementState as) {
        super(as.id);
        this.announcee = as.announcee;
        this.depth = as.depth;
        this.hopsToLive = as.hopsToLive;
        this.commitVal = as.commitVal;
        this.myVal = as.myVal;
        this.origRec = as.origRec;
        this.lastAddr = as.lastAddr;
        this.routes = as.routes;
	this.origHopsToLive = as.origHopsToLive;
        this.routed = as.routed;
        this.totalRestarts = as.totalRestarts;
    }

    public void lost(Node n) {
    // the message handler will log this
    }

    public final NodeReference getAnnouncee() {
        return announcee;
    }
    
    public final long depth() {
        return depth;
    }

    public final long hopsToLive() {
        return hopsToLive;
    }

    protected void checkReply(Message m) throws BadStateException {
        if(!lastAddr.equalsIdent(m.peerIdentity())) {
            throw new BadStateException("Reply from wrong node: "+m);
        }
    }

    protected void checkFollowUp(Message m) throws BadStateException {
        if(!origRec.equalsIdent(m.peerIdentity())) {
            throw new BadStateException("Follow up from wrong node: "+m);
        }
    }

    // FIXME: Does announcement really need a different interval to
    // everything else?
    // @see Core.hopTime (97.5% interval)
    // I'm not sure that announcing/ uses this...
    /**
     * @return The upper bound of a one sided 99% confidence interval
     *         for the time it should take to get a reply based on the
     *         the hopTimeExpected and hopTimeDeviation values from
     *         the config (and the assumption that the time is normal).
     *         In milliseconds.
     */
    public static final long getTime(long htl) {
        return (long) (Core.hopTimeExpected * htl 
                       + 2.33 * Math.sqrt(htl) * Core.hopTimeDeviation);
    }

    void sendMessage(Node n, Peer p, Message m, KeyList kl,
		     long timeout) 
	throws CommunicationException {
	
        TrailerWriter out = n.sendMessage(m, p, timeout);
        try {
            if (out != null) {
		OutputStream os = new TrailerWriterOutputStream(out);
		kl.writeTo(os);
	    }
        } catch (IOException e) {
            //e.printStackTrace();
            throw new SendFailedException(p.getAddress(), 
                                          "Writing keys failed: "+e);
        } finally {
	    if(out != null) out.close();
        }
    }
    
    public void terminateRouting(boolean success, boolean routingRelated) {
	if(routes != null) {
	    routes.terminate(success, routingRelated, false);
	    routes = null;
	}
    }
}


