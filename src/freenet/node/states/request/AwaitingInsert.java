package freenet.node.states.request;

import freenet.*;
import freenet.node.*;
import freenet.node.states.data.*;
import freenet.node.ds.KeyCollisionException;
import freenet.message.*;
import freenet.support.Logger;
import java.io.IOException;

/**
 * The states for inserts at the terminal node that are waiting for a 
 * DataInsert or NoInsert message.
 */
public class AwaitingInsert extends RequestState {

    /** the auxiliary chain that will read the data into the store */
    ReceiveData receivingData;

    /** the MO that times out the DataInsert we are waiting on */
    NoInsert ni;
    
    /** the SendFinished for the QueryRestarted we sent before going to AI, if any */
    SendFinished sfFeedback;
    
    boolean ignoreDS = false; 

    AwaitingInsert(InsertPending ancestor) {
        super(ancestor);
        ni = ancestor.ni;
        ignoreDS = ancestor.ignoreDS;
        sfFeedback = ancestor.feedbackSender;
    }

    public final String getName() {
        return "Awaiting Insert";
    }

    public final void lost(Node n) {
        Core.diagnostics.occurrenceCounting("lostRequestState", 1);
        if(ni != null) ni.cancel();
        fail(n, "State lost while waiting for your DataInsert");
    }
    
    public State receivedMessage(Node n, DataInsert dim) throws BadStateException {
        if (!fromOrigPeer(dim)) {
            throw new BadStateException("DataInsert from the wrong peer!");
        }
        if(ni != null) ni.cancel();
        try {
            receivingData = dim.cacheData(n, searchKey, ignoreDS);
        } catch (KeyCollisionException e) {
            // we've already sent the Accepted so we should try..
            dim.eatData(n);
            
            scheduleRestart(n, 0);
            // this bloody well ought to work
            return new DataPending(this);
        } catch (IOException e) {
            fail(n, "I/O error receiving insert");
            Core.logger.log(this, "Failed to cache insert on chain " 
			 + Long.toHexString(id), e, Logger.ERROR);
	    terminateRouting(false, false, false);
            return new RequestDone(this);
        }
        receivingData.schedule(n);
        return new ReceivingInsert(this);
    }
    
    public State receivedMessage(Node n, NoInsert noInsert) throws StateException {
        if (this.ni != noInsert) {
            throw new BadStateException("Not my NoInsert: "+noInsert);
        }
        Core.logger.log(this, "Did not receive expected DataInsert on chain " 
                           + Long.toHexString(id), Logger.MINOR);
        fail(n, "DataInsert never received");
	terminateRouting(false, false, false);
        return new RequestDone(this);
    }
    
    public State receivedMessage(Node n, SendFinished sf) {
        if(sf == sfFeedback) {
            if(sf.getSuccess()) {
                // Cool
                return this;
            } else {
                // Uh oh
                fail(n, "Send failed for feedback sender: "+sf);
                terminateRouting(false, false, false);
                return new RequestDone(this);
            }
        } else {
            Core.logger.log(this, "Got unknown "+sf+" on "+this, Logger.ERROR);
            return this;
        }
    }
    
    public State receivedMessage(Node n, QueryAborted q) {
	Core.logger.log(this, "Aborted AwaitingInsert", Logger.DEBUG);
	if(ni != null) ni.cancel();
	terminateRouting(false, false, false);
	return new RequestDone(this);
    }

    public State receivedMessage(Node n, InsertRequest req) {
    	// Probably just a loop
		Message m =
			new QueryRejected(
				id,
				hopsToLive,
				"Looped request",
				req.otherFields);
		RequestSendCallback cb =
			new RequestSendCallback(
				"QueryRejected (looped " + "request) for " + this,
				n,
				this);
		n.sendMessageAsync(m, req.getSourceID(), Core.hopTime(1,0), cb);
		req.drop(n);
		return this;
    }
    
    public State receivedMessage(Node n, InsertReply ir) {
        // Ignore
        Core.logger.log(this, "Received "+ir+" on "+this, Logger.MINOR);
        return this;
    }
    
    public boolean wasInsert() {
        return true;
    }

    protected boolean isInsert() {
        return true;
    }
}

