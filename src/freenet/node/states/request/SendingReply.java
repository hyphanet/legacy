package freenet.node.states.request;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Message;
import freenet.Presentation;
import freenet.message.DataInsert;
import freenet.message.DataRequest;
import freenet.message.QueryRejected;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.states.data.DataSent;
import freenet.node.states.data.SendData;
import freenet.support.Logger;

/**
 * This is the State pertaining to Data Requests sending data from the
 * node.
 */

public class SendingReply extends RequestState {

    /** the auxiliary chain that is sending the data */
    SendData sendingData;

    /** the feedback SendFinished */
    SendFinished feedbackSender;

    private final boolean wasInsert;

    SendingReply(Pending ancestor) {
        super(ancestor);
        wasInsert = ancestor.wasInsert();
        sendingData = ancestor.sendingData;
        this.feedbackSender = ancestor.feedbackSender;
	if(logDEBUG) Core.logger.log(this, "Creating SendingReply with "+sendingData+
			" from "+searchKey+" from "+ancestor, 
			new Exception("debug"), Logger.DEBUG);
    }

    /**
     * Returns the name.
     * @return "Sending data"
     */
    public final String getName() {
        return "Sending DataReply";
    }

    public State receivedMessage(Node n, DataSent ds) throws BadStateException {
        if (sendingData != ds.source()) {
            throw new BadStateException("Not my DataSent: "+ds);
        }

        int cb = ds.getCB();
        switch (cb) {
            case Presentation.CB_OK:
                Core.logger.log(this, "Data sent successfully!: "+this, new Exception("debug"),
			     Logger.MINOR);
                try {
                    // return the noderef we would have routed to
                    // (there is still a chance of DataSource resetting)
                    // -1 means we don't know the rate for that ref.
		    Node.diagnostics.occurrenceCounting("storeDataSendingReply",1);
                    ft.storeData(n, null, null, -1, 0,
				 new RequestSendCallback("StoreData", n, this));
		    // We are sending from cache, no need to do pcaching
                } catch (CommunicationException e) {
                    Core.logger.log(this,
                        "Failed to send back StoreData to peer "+e.peer+
				 " for "+this, e, Logger.MINOR);
		    // Not our fault, so still counts
                }
		// Update successful request distribution
		if(origPeer != null) {
		    Core.logger.log(this, "Logging external success on "+
				 "success*Distribution from "+origPeer+" on "+
				 this, Logger.DEBUG);
		    if (Core.successDataDistribution != null) {
                        Core.successDataDistribution.add(searchKey.getVal());
		    }
		    if(Core.inboundRequests != null) 
			Core.inboundRequests.incActive(n.getStringAddress(origPeer));
		}
		Core.logger.log(this, "Finalizing sendingData: "+this, Logger.DEBUG);
		sendingData.finalize();
		return new RequestDone(this);
		
            case Presentation.CB_CACHE_FAILED:
		Core.logger.log(this, "Cache failed: "+this, Logger.DEBUG);
                fail(n, "Cache failed");
                // fall through
            case Presentation.CB_RECEIVER_KILLED:
                if(cb == Presentation.CB_RECEIVER_KILLED)
                    Core.logger.log(this, "Receiver did not want data: "+this, Logger.MINOR);
            case Presentation.CB_SEND_CONN_DIED:
                Core.logger.log(this,
                    "Dropping send on CB "+Presentation.getCBdescription(cb)
			     +", on chain "+Long.toHexString(id)+" for "+this,
			     (cb == Presentation.CB_CACHE_FAILED ? 
			      Logger.ERROR : Logger.MINOR));
            	Core.diagnostics.occurrenceCounting("sendConnDiedInTransfer", 1);
                sendingData.finalize();
                return new RequestDone(this);
                
            // the StoreInputStream we were reading from got restarted
            default:
		Core.logger.log(this, "Stream was restarted in "+
			     "receivedMessage(DataSent): "+this,
			     Logger.DEBUG);
                scheduleRestart(n, 0);
		sendingData.finalize();
                return new DataPending(this);
        }
    }

    /**
     * This is only needed for InsertRequests on key collisions, so really
     * it shouldn't be here - but the safety added is minimal so I'll
     * fix it another day. It just eats the data.
     */
    public State receivedMessage(Node n, DataInsert dim) throws BadStateException {
        if (!fromOrigPeer(dim)) {
            throw new BadStateException("DataInsert from the wrong peer!");
        }
        Core.logger.log(this, "Eating DataInsert during SendingReply: "+this, Logger.DEBUG);
        dim.eatData(n);
        return this;
    }

    public State receivedMessage(Node n, DataRequest dr) {
        // This is a loop, no real problem here.
        if (logDEBUG)
            Core.logger.log(this, "Backtracking", Logger.DEBUG);
        Message m =
            new QueryRejected(
                    id,
                    hopsToLive,
                    "Looped request",
                    dr.otherFields);
        RequestSendCallback cb =
            new RequestSendCallback(
                    "QueryRejected (looped " + "request) for " + this,
                    n,
                    this);
        n.sendMessageAsync(m, dr.getSourceID(), Core.hopTime(1,0), cb);
        dr.drop(n);
        return this;
    }
    
    public State receivedMessage(Node n, SendFinished sf) {
        if(sf == feedbackSender) {
            if(sf.getSuccess()) {
                // Cool
                feedbackSender = null;
                return this;
            } else {
                // Uh oh
                // Cancel send
                Core.logger.log(this, "Cancelling "+this+" after received "+sf, 
                        Logger.MINOR);
                sendingData.abort(Presentation.CB_ABORTED, true);
                return new RequestDone(this);
            }
        } else {
            Core.logger.log(this, "Got "+sf+" on "+this, Logger.NORMAL);
            feedbackSender = null;
            return this;
        }
    }
    
    // There's not much we can do if the state is lost while sending data.
    // The MessageHandler will log it. 
    public final void lost(Node n) {
	Core.logger.log(this, "Lost "+this, Logger.DEBUG);
        Core.diagnostics.occurrenceCounting("lostRequestState", 1);
    }

    public boolean wasInsert() {
        return wasInsert;
    }

    protected boolean isInsert() {
        return false;
    }
}



