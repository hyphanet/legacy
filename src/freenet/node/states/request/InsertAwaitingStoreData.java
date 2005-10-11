package freenet.node.states.request;

import java.io.IOException;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Message;
import freenet.message.Accepted;
import freenet.message.DataReply;
import freenet.message.InsertReply;
import freenet.message.InsertRequest;
import freenet.message.QueryAborted;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.StoreData;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.node.StateTransition;
import freenet.node.ds.KeyCollisionException;
import freenet.support.Logger;

/**
 * AwaitingStoreData subclass for inserts
 * @author amphibian
 */
public class InsertAwaitingStoreData extends AwaitingStoreData {

    // he he .. and here we are again :)
    private TransferInsert xferIns;
    private SendFinished feedbackSender;

    public final String getName() {
        return "Awaiting StoreData for an insert";
    }

    /**
     * @param ancestor the previous state
     * @param nosd the scheduled NoStoreData message
     */
    public InsertAwaitingStoreData(TransferInsert ancestor, NoStoreData nosd) {
        super(ancestor, nosd);
        this.xferIns = ancestor;
        if(xferIns == null) throw new NullPointerException();
        xferIns.iAWSDSlave = true;
        // Don't need to reset this as it is not inherited.
        // If restart, will not go directly to the same TI.. because it will
        // go to RequestDone, TransferInsertPending or ReceivngReply.
    }

    // We have to duplicate all the receivedMessage()
    // Because the RTTI that calls them doesn't know about
    // inheritance! :<
    
    // it could happen...
    public State receivedMessage(Node n, Accepted a) {
        return this;
    }

    // ignore.
    public State receivedMessage(Node n, QueryRestarted qr) throws StateException {
		if(!xferIns.shouldAcceptFromRoutee(qr)) return this;

		nosd.cancel();
		Core.logger.log(this, "Got "+qr+" on "+this, Logger.MINOR);
		// They could still be routing. We have been accepted and sent DataInsert, but we have not necessarily
		// received InsertReply. So include the queue time.
		long timeout = xferIns.storeDataTime();
		try {
            relayRestarted(n, timeout, true);
        } catch (RequestAbortException e) {
            // Ugh
            Core.logger.log(this, "Failed to send QueryRestarted on "+this, Logger.MINOR);
            deleteFile(n);
            terminateRouting(false, false, false);
            return new RequestDone(this);
        }
		scheduleNoSD(n, timeout);

        return this;
    }

    private void scheduleNoSD(Node n, long millis) {
   		if (logDEBUG)
   			Core.logger.log(
   				this,
   				"Rescheduling the NoStoreData to timeout in "
   					+ millis
   					+ " millis on chain "
   					+ Long.toHexString(id),
   				new Exception("debug"),
   				Logger.DEBUG);
   		if (nosd != null)
   			nosd.cancel();
   		if (logDEBUG)
   			Core.logger.log(
   				this,
   				"Cancelled " + nosd + " on " + this,
   				Logger.DEBUG);
   		n.schedule(millis, nosd);
   		if (logDEBUG)
   			Core.logger.log(
   				this,
   				"Scheduled " + nosd + " in " + millis + " on " + this,
   				Logger.DEBUG);
    }

    /**
     * Forward the QueryRestarted.
     */
    private void relayRestarted(Node n, long timeout, boolean sendAsync) throws RequestAbortException {
		if (sendAsync)
			feedbackSender = new SendFinished(n, id, "QueryRestarted from IAWSD");
		// We don't care if it takes forever, nothing is waiting for it
		try {
			ft.restarted(n, timeout, sendAsync ? feedbackSender : null, "relaying in IAWSD");
		} catch (CommunicationException e) {
			Core.logger.log(
				this,
				"Couldn't restart because relaying QueryRestarted failed: "
					+ e
					+ " for "
					+ this,
				Logger.MINOR);
			terminateRouting(false, false, false);
			if(feedbackSender != null) feedbackSender.cancel();
			cancelRestart();
			throw new RequestAbortException(new RequestDone(this));
		}
	}

    public State receivedMessage(Node n, QueryAborted qa) {
        if(origPeer != null && fromOrigPeer(qa)) {
            if(xferIns.routes != null)
                xferIns.routes.terminateNoDiagnostic();
            nosd.cancel();
            return new RequestDone(this);
        } else {
            Core.logger.log(this, "QueryAborted from wrong peer: "+qa+" for "+this, Logger.ERROR);
        }
        return this;
    }
    
    // ignore.
    public State receivedMessage(Node n, RequestInitiator r) {
        return this;
    }

    public State receivedMessage(Node n, InsertRequest r) {
    	// Probably just a loop
		Message m =
			new QueryRejected(
				id,
				hopsToLive,
				"Looped request",
				r.otherFields);
		RequestSendCallback cb =
			new RequestSendCallback(
				"QueryRejected (looped " + "request) for " + this,
				n,
				this);
		n.sendMessageAsync(m, r.getSourceID(), Core.hopTime(1,0), cb);
		r.drop(n);
		return this;
    }
    
    public State receivedMessage(Node n, QueryRejected qr)
    throws StateException {
        if(!xferIns.shouldAcceptFromRoutee(qr)) return this;
        State s;
        try {
            s = xferIns.receivedMessage(n, qr);
        } catch (StateTransition e) {
        if (nosd != null) nosd.cancel();
            throw e;
        }
        if(s == xferIns) return this;
        // Otherwise something has changed...
        return s;
    }

    public State receivedMessage(Node n, NoStoreData noStoreData)
    throws StateException {
        if(noStoreData != this.nosd) {
            String err = "Not our NoStoreData: "+noStoreData+", expected "+nosd;
            Core.logger.log(this, err, Logger.ERROR);
            throw new BadStateException(err);
        }
        return xferIns.receivedNoStoreData(n, nosd);
    }
    
    public State receivedMessage(Node n, StoreData sd) throws BadStateException {
        if (!fromLastPeer(sd)) { 
            throw new BadStateException("StoreData from the wrong peer!"); 
        }
        if(logDEBUG)
            Core.logger.log(this, "Got "+sd+" on "+this, Logger.DEBUG);
        // FIXME: is this the right way to do it?
        // Clients expect at least one Pending...
        if(!xferIns.approved) try {
            xferIns.insertReply(n, Core.hopTime(1,0));
        } catch (RequestAbortException e) {
            // Ignore
        }
        return super.receivedMessage(n, sd);
    }
    
    public State receivedMessage(Node n, DataReply dr) throws StateException {
        try {
            xferIns.receivedMessage(n, dr);
        } catch (StateTransition e) {
        if(nosd != null) nosd.cancel();
            throw e;
        }
        return this; // will throw a transition if need to change state
    }
    
    public State receivedMessage(Node n, InsertReply ir) throws StateException {
        try {
            xferIns.receivedMessage(n, ir);
        } catch (StateTransition e) {
            if(nosd != null) nosd.cancel();
            throw e;
        }
        return this; // above will throw a transition if needed
    }
    
    public State receivedMessage(Node n, SendFinished sf) {
        if(sf == feedbackSender) {
            if(sf.getSuccess()) {
                // Cool
                return this;
            } else {
                // Uh oh
                Core.logger.log(this, "Failed to send "+sf+" on "+this, Logger.MINOR);
                deleteFile(n);
                terminateRouting(false, false, false);
                return new RequestDone(this);
            }
        }
        return super.receivedMessage(n, sf);
    }
    
    protected boolean isInsert() {
        return true;
    }
    
    /**
     * Delete the file 
     */
    protected void deleteFile(Node n) {
        Core.logger.log(this, "Deleting file by cancelling ReceivingData: "+this,
                Logger.DEBUG);
        xferIns.receivingData.cancel();
    }

    /**
     * Keep the file 
     */
    protected void keepFile(Node n) {
        Core.logger.log(this, "Keeping file: "+this, Logger.DEBUG);
        try {
            xferIns.receivingData.commit();
        } catch (KeyCollisionException e) {
            // Who cares?
        } catch (IOException e) {
            // Hrrm
            Core.logger.log(this, "Caught "+e+" committing "+this,
                    e, Logger.ERROR);
        }
        super.keepFile(n);
    }
}
