package freenet.node.states.FNP;

import freenet.CommunicationException;
import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Message;
import freenet.MessageSendCallback;
import freenet.Storables;
import freenet.TrailerWriter;
import freenet.Version;
import freenet.message.DataNotFound;
import freenet.message.DataReply;
import freenet.message.InsertReply;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.StoreData;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.states.request.FeedbackToken;
import freenet.node.states.request.RequestState;
import freenet.support.Logger;

/** Relays results from the request states back to a remote node.
  * @author tavin
  */
final class FNPFeedbackToken implements FeedbackToken {

    // id of the associated state chain
    private final long id;
    // the node who asked us for this
    private final Identity origPeer;
    // the original hopsToLive
    private final int origHopsToLive;
    private final int queueTimeout;

    FNPFeedbackToken(long id, Identity origPeer, int hopsToLive, int queueTimeout) {
        this.id      = id;
        this.origPeer = origPeer;
	this.origHopsToLive = hopsToLive;
	this.queueTimeout = queueTimeout;
    }
    
    public final void queryRejected(Node n, int htl, String reason, 
				    FieldSet fs, int unreachable, 
				    int restarted, int rejected,
				    int backedOff, MessageSendCallback cb) {
	Message m = new QueryRejected(id, htl, reason, fs);
	n.sendMessageAsync(m, origPeer, Core.hopTime(2, 0), // mid-request so slightly more significant 
	        cb);
    }
    
    public final void restarted(Node n, long millis, MessageSendCallback cb, String reason) 
	throws CommunicationException {
	Message m = new QueryRestarted(id);
	// If we can't send it within the request timeout, it's not much use
	if(cb == null)
	    n.sendMessage(m, origPeer, millis);
	else
	    n.sendMessageAsync(m, origPeer, millis, cb);
    }
    
    public final void dataNotFound(Node n, long timeOfQuery, 
				   MessageSendCallback cb) 
	throws CommunicationException {
	Message m = new DataNotFound(id, timeOfQuery);
	if(cb == null)
	    n.sendMessage(m, origPeer, Core.hopTime(origHopsToLive+RequestState.TIMEOUT_EXTRA_HTL+1, queueTimeout));
	else
	    n.sendMessageAsync(m, origPeer, Core.hopTime(origHopsToLive+RequestState.TIMEOUT_EXTRA_HTL+1, queueTimeout), cb);
    }
    
    public final TrailerWriter dataFound(Node n, Storables storables, long ctLength)
	throws CommunicationException {
        FieldSet fs = new FieldSet();
        storables.addTo(fs);
   return n.sendMessage(new DataReply(id, fs, ctLength), 
        	 origPeer, Core.hopTime(origHopsToLive+RequestState.TIMEOUT_EXTRA_HTL, queueTimeout));
    }
    
    public final void insertReply(Node n, long millis) 
	throws CommunicationException {
        n.sendMessage(new InsertReply(id), origPeer, 
                RequestState.hopTimeHTL(origHopsToLive, 0));
    }
    
    public final void storeData(Node n, NodeReference nr, FieldSet estimator, 
								long rate, int hopsSinceReset, MessageSendCallback cb)
	throws CommunicationException {
        if (nr != null) {
            // experimental way of helping to keep buggy nodes out of the network
            String vers = nr.getVersion();
            if (vers != null && !Version.checkGoodVersion(vers)) {
                Core.logger.log(this, "Automatically resetting DataSource "+
			     "due to old version: "+ vers, Logger.DEBUG);
                nr = null;
            }
        }
	if(Core.getRandSource().nextFloat()< Node.probIncHopsSinceReset)
	    hopsSinceReset++;
        // LATER... 
        // Use n.loadStats.resetProbability() to determine the reset
        // probability.
        if (nr == null || n.loadStats.shouldReset()) {
            nr = n.getNodeReference();
            // Send our request rate estimate too.
            rate = (long)(n.loadStats.localQueryTraffic() + 0.5);
	    hopsSinceReset = 0;
        }
	Message m = new StoreData(id, nr, estimator, rate, hopsSinceReset);
	if(Core.logger.shouldLog(Logger.DEBUG, this))
	    Core.logger.log(this, "Sending "+m+" in "+this+".storeData(...)",
	            Logger.DEBUG);
	if(cb == null)
	    n.sendMessage(m, origPeer, 2*RequestState.hopTimeHTL(origHopsToLive+1, 0));
	else
	    n.sendMessageAsync(m, origPeer, 2*RequestState.hopTimeHTL(origHopsToLive+1, 0), cb);
    }
}



