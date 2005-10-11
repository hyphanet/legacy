package freenet.node.states.request;

import freenet.*;
import freenet.node.*;
import freenet.message.*;
import freenet.support.Logger;

/**
 * After a request or insert has successfully completed transferring, we will
 * enter this state to wait for the StoreData (unless we were the terminal node
 * in the chain).
 * 
  * @author tavin
  */
public class AwaitingStoreData extends RequestState {

    protected NoStoreData nosd;
    private final boolean wasInsert;

    /**
     * New AwaitingStoreData with a definite timeout.
     * 
     * @param nosd
     *            the MO that governs the timeout -- should be already
     *            scheduled on the ticker
      */
    AwaitingStoreData(Pending ancestor, NoStoreData nosd) {
        super(ancestor);
        this.nosd = nosd;
        this.wasInsert = ancestor.wasInsert();
    }

    public String getName() {
        return "Awaiting StoreData";
    }
    
    // it could happen...
    public State receivedMessage(Node n, Accepted a) {
        return this;
    }

    // ignore.
    public State receivedMessage(Node n, QueryRestarted qr) throws StateException {
        return this;
    }

    // ignore.
    public State receivedMessage(Node n, RequestInitiator r) {
        return this;
    }
    
    public State receivedMessage(Node n, DataNotFound dnf) {
        // ignore - from another responder
        return this;
    }
    
    public State receivedMessage(Node n, QueryRejected qr) throws StateException {
    	if (!fromLastPeer(qr))
    		throw new BadStateException("QueryRejected from wrong peer!");
    	Core.diagnostics.occurrenceCounting("routingFailedInAWSD", 1);
    	terminateRouting(false, true, false);
    	relayStoreData(n, null);
    	return new RequestDone(this);
    }
    
    public State receivedMessage(Node n, NoStoreData noStoreData)
            throws StateException {
        if (this.nosd != noStoreData) { throw new BadStateException(
                "Not my NoStoreData: " + noStoreData); }
		Core.diagnostics.occurrenceCounting("routingFailedInAWSD", 1);
		Core.diagnostics.occurrenceCounting("routingFailedInAWSD", 1);
		terminateRouting(false, true, false);
        relayStoreData(n, null);
        return new RequestDone(this);
    }
    
    public State receivedMessage(Node n, StoreData sd) throws BadStateException {
        if (!fromLastPeer(sd)) { throw new BadStateException(
                "StoreData from the wrong peer!"); }
	
	// Update global network load estimate stats.			    
        n.loadStats.storeTraffic(sd.getDataSource(), sd.getRequestRate());
        terminateRouting(true, true, false);
        relayStoreData(n, sd);

        return new RequestDone(this);
    }

    public State receivedMessage(Node n, DataReply dr) throws StateException {
    	Core.logger.log(this, "Received "+dr+" on "+this+" :(", 
    			Logger.NORMAL);
    	// Now drop the trailer...
    	dr.closeIn();
    	return this;
    }
    
    public State receivedMessage(Node n, SendFinished sf) {
        Core.logger.log(this, toString() + " received " + sf, Logger.MINOR);
        return this;
    }
    
    public final void lost(Node n) {
        Core.diagnostics.occurrenceCounting("lostAwaitingStoreData", 1);
        // just like timing out with the NoStoreData
		Core.diagnostics.occurrenceCounting("routingFailedInAWSD", 1);
	terminateRouting(false, false, false);
        relayStoreData(n, null);
    }

    protected void relayStoreData(Node n, StoreData sd) {
        if (nosd != null) {
            nosd.cancel();
        }
		NodeReference nr = null;
		FieldSet sourceEstimator = null;
		if(sd == null) {
			nr = null;
		} else {
			nr = sd.getDataSource();
			sourceEstimator = sd.getEstimator();
		}
        if (nr != null && !nr.checkAddresses(n.transports)) {
            if (Core.logger.shouldLog(Logger.MINOR,this))
                    Core.logger.log(this, "Not referencing because addresses "
                            + "of DataSource wrong: " + nr.toString(),
                            Logger.MINOR);
            nr = null;
        }
        if(nr != null) {
        	// If it is in the RT, substitute that node's sourceEstimator
        	FieldSet fs = n.rt.estimatorToFieldSet(nr.getIdentity());
        	if(fs != null) sourceEstimator = fs;
        }
        long rate = -1;
	if(sd != null)
	    Core.diagnostics.occurrenceContinuous("incomingHopsSinceReset", 
					       sd.getHopsSinceReset());
        if (n.shouldReference(nr, sd)) {
	    Core.diagnostics.occurrenceCounting("prefAccepted", 1);
            Core.logger.log(this, "PCaching: Referencing " + searchKey,
                    Logger.DEBUG);
            if (!nr.isSigned()) {
                Core.logger.log(this, "Rejecting unsigned reference " + nr,
                        Logger.NORMAL);
            } else
                n.reference(searchKey, null, nr, sd.getEstimator());
            rate = sd.getRequestRate();
        } else if(nr != null) {
	    Core.diagnostics.occurrenceCounting("prefRejected", 1);
	    Core.logger.log(this, "PCaching: Not Referencing "+searchKey,
			 Logger.DEBUG);
	}
	if(n.shouldCache(sd)) {
            Core.logger.log(this, "PCaching: Keeping " + searchKey,
                    Logger.DEBUG);
	    Core.diagnostics.occurrenceCounting("pcacheAccepted", 1);
	    // Keep it
            keepFile(n);
	} else {
	    // Try to delete it
            Core.logger.log(this, "PCaching: Removing " + searchKey,
                    Logger.DEBUG);
	    Core.diagnostics.occurrenceCounting("pcacheRejected", 1);
            deleteFile(n);
	}
        try {
            Core.diagnostics
                    .occurrenceCounting("storeDataAwaitingStoreData", 1);
            ft.storeData(n, nr, sourceEstimator, rate, sd == null ? 0 : sd
                    .getHopsSinceReset(), new RequestSendCallback("StoreData",
                    n, this));
        } catch (CommunicationException e) {
            Core.logger.log(this,
                    "Failed to relay StoreData to peer " + e.peer, e,
                    Logger.MINOR);
        }
        if(logDEBUG)
            Core.logger.log(this, "Called ft.storeData", Logger.DEBUG);
	if (origPeer != null) {
            Core.logger.log(this,
                    "Logging external success on success*Distribution "
                            + "from " + origPeer + " on " + this, Logger.DEBUG);
            if (isInsert()) {
	        if (Node.successInsertDistribution != null) {
                    Node.successInsertDistribution.add(searchKey.getVal());
	        }
	    } else {
	        if (Node.successDataDistribution != null) {
                    Node.successDataDistribution.add(searchKey.getVal());
		}
	    }
            if (Node.inboundRequests != null) {
                Node.inboundRequests.incActive(n.getStringAddress(origPeer));
        }
	}
        if(logDEBUG)
            Core.logger.log(this, "Leaving relayStoreData", Logger.DEBUG);
    }

    /**
     * Delete the file 
     */
    protected void deleteFile(Node n) {
        if (!n.ds.demote(searchKey))
            Core.diagnostics
            .occurrenceCounting("pcacheFailedDelete", 1);
    }

    /**
     * Keep the file 
     */
    protected void keepFile(Node n) {
        // Override in InsertAwaitingStoreData
}

    protected boolean isInsert() {
        return false;
    }

    public boolean wasInsert() {
        return wasInsert;
    }
}
