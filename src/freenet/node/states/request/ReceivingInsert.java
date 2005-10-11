package freenet.node.states.request;

import java.io.IOException;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Key;
import freenet.Message;
import freenet.PeerPacketMessage;
import freenet.Presentation;
import freenet.message.QueryAborted;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.Request;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.node.ds.KeyCollisionException;
import freenet.node.states.data.DataReceived;
import freenet.node.states.data.ReceiveData;
import freenet.support.Logger;

/**
 * State for the terminating node on an insert chain that is only 
 * receiving the data, but should generate and send back a StoreData
 * when it is done.
 */

public class ReceivingInsert extends RequestState {
    
    /** the auxiliary chain that is reading the data into the store */
    ReceiveData receivingData;
    
    ReceivingInsert(InsertPending ancestor) {
        super(ancestor);
	receivingData = ancestor.receivingData;
    }
    
    ReceivingInsert(AwaitingInsert ancestor) {
        super(ancestor);
        receivingData = ancestor.receivingData;
	// no routing outwards so no ngrouting logging
    }
    
    public final String getName() {
        return "Receiving Insert";
    }
    
    // There's not much we can do if the state is lost while receiving an insert.
    // The MessageHandler will log it. 
    public final void lost(Node n) {
        Core.diagnostics.occurrenceCounting("lostRequestState", 1);
    }
    
    public State receivedMessage(Node n, QueryAborted qa) throws StateException {
    	if (!fromOrigPeer(qa)) {
    		throw new BadStateException("QueryAborted "+qa+" from wrong peer!");
    	}
    	receivingData.cancel();
    	fail(n, "Aborted query");
    	return new RequestDone(this);
    }
    
    public State receivedMessage(Node n, DataReceived dr) throws BadStateException {
        if (receivingData != dr.source()) {
            throw new BadStateException("Not my DataReceived: "+dr+" ("+
					dr.source()+") vs "+receivingData);
        }
        int cb = dr.getCB();
        switch (cb) {
            case Presentation.CB_OK:
                try {
                    receivingData.commit();  // make the key available
                } catch (KeyCollisionException e) {
                    // this is a little bit of a hack.  we jump into a
                    // DataPending state and then handle a restart which
                    // makes us check for the data in the store again
                    Core.logger.log(this, "Going to DataPending after key collision",
                                 Logger.MINOR);
                    scheduleRestart(n, 0);
                    return new DataPending(this);
                } catch (IOException e) {
                    fail(n, "Cache failed");
                    Core.logger.log(this, "Cache failed on commit", e, Logger.ERROR);
                    return new RequestDone(this);
                }
                Core.logger.log(this, "Data received successfully!", Logger.MINOR);
                try {
                    // return the noderef we would have routed to
                    // (there is still a chance of DataSource resetting)
                    // -1 means we don't know the rate for that ref.
		    Node.diagnostics.occurrenceCounting("storeDataReceivingInsert", 1);
		    long length = receivingData.length();
		    length = Key.
			getTransmissionLength(length, 
					      Key.getPartSize(length));
		    ft.storeData(n, null, null, -1, 0, null);
		    
		    // We are the terminal node; we cache it
		    if (origPeer != null)
		    {
			if (Node.successInsertDistribution != null)
			{
                            Node.successInsertDistribution.add(searchKey.getVal());
			}
			if (Node.inboundRequests != null)
			{
			    Node.inboundRequests.incActive(n.getStringAddress(origPeer));
			}
                    }
                } catch (CommunicationException e) {
                    Core.logger.log(this,
                        "Failed to send back StoreData to peer " + e.peer,
                        e, Logger.MINOR);
                }
                return new RequestDone(this);

            case Presentation.CB_CACHE_FAILED:
                fail(n, "Cache failed");
                // fall through
            	
            case Presentation.CB_RECV_CONN_DIED:
            	if(cb == Presentation.CB_RECV_CONN_DIED) {
            		Core.logger.log(this, "Receiving connection died in "+this,
            				Logger.NORMAL);
            		Core.diagnostics.occurrenceCounting("recvConnDiedInTransfer", 1);
            	}
            default:
                Core.logger.log(this,
                    "Failed to receive insert with CB "+Presentation.getCBdescription(cb)
                    +", on chain "+Long.toHexString(id),
                    (cb == Presentation.CB_CACHE_FAILED ? Logger.ERROR : Logger.MINOR));
                return new RequestDone(this);
        }
    }
    
    public State receivedMessage(Node n, QueryRestarted qr) {
        Core.logger.log(this, "Got "+qr+" on "+this, Logger.MINOR);
        if(!qr.peerIdentity().equals(lastPeer)) {
            if(logDEBUG)
                Core.logger.log(this, "Sending QueryAborted to "+qr.peerIdentity(), Logger.DEBUG);
            qr.getSource().sendMessageAsync(new QueryAborted(id), null, super.origHopTimeHTL(0), PeerPacketMessage.EXPENDABLE);
        }
        return this;
    }
    
    public State receivedMessage(Node n, QueryRejected qr) {
        if(logDEBUG)
            Core.logger.log(this, "Got "+qr+" on "+this, Logger.DEBUG);
        return this;
    }
    
    public State receivedMessage(Node n, SendFinished sf) {
	Core.logger.log(this, "Received "+sf+" in "+this+" - WTF?",
		     Logger.MINOR);
	return this;
    }

	void receivedRequest(Node n, Request r) {
		// This is a loop, no real problem here.
		if (logDEBUG)
			Core.logger.log(this, "Backtracking: got "+r+" on "+this, Logger.DEBUG);
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
	}

    public boolean wasInsert() {
        return true;
    }

    protected boolean isInsert() {
        return true;
    }
}

