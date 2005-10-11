package freenet.node.states.announcing;

import freenet.*;
import freenet.node.*;
import freenet.support.Logger;
import freenet.crypt.*;

public abstract class AnnouncingState extends State implements AggregatedState {

    
    static final Digest ctx = SHA1.getInstance();
        
    final int hopsToLive;
    final NodeReference target;
    
    byte[] myVal;

    
    AnnouncingState(long id, int hopsToLive, NodeReference target) {
        super(id);
        this.hopsToLive = hopsToLive;
        this.target = target;
    }

    AnnouncingState(AnnouncingState as) {
        super(as.id());
        hopsToLive = as.hopsToLive;
        target = as.target;
        myVal = as.myVal;
    }

    protected void signalSuccess(Node n) {
        n.schedule(0, new Completed(id, target.getIdentity(), 
                                    hopsToLive));
    }
    
    protected void signalFailed(Node n, boolean terminal) {
        n.schedule(0, new Completed(id, target.getIdentity(), 
                                    hopsToLive, terminal, "Failed"));
    }
    
    protected void signalFailed(Node n, boolean terminal, 
				String reasonString) {
	n.schedule(0, new Completed(id, target.getIdentity(), 
				    hopsToLive, terminal, 
				    reasonString));
    }
    
    public State received(Node n, MessageObject mo) throws StateException {
        // customary source check
        if (mo instanceof Message &&
            !target.getIdentity().equals(((Message) mo).peerIdentity())) {
            throw new BadStateException("Reply from wrong node: "+mo);
        }
        return super.received(n, mo);
    }

    public void lost(Node n) {
        // Shouldn't happen
        Core.logger.log(this, "Lost "+this, Logger.NORMAL);
    }
    
    /**
     * 99% confidence interval
     */
    public static final long getTime(long htl) {
        return (long) (Core.hopTimeExpected * htl 
                       + 2.33 * Math.sqrt(htl) * Core.hopTimeDeviation);
    }
}


