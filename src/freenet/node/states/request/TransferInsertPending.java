package freenet.node.states.request;

import java.io.IOException;

import freenet.Core;
import freenet.Presentation;
import freenet.message.Accepted;
import freenet.message.DataReply;
import freenet.message.InsertReply;
import freenet.message.InsertRequest;
import freenet.message.QueryAborted;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.node.StateTransition;
import freenet.node.ds.KeyInputStream;
import freenet.node.states.data.DataReceived;
import freenet.node.states.data.DataSent;
import freenet.node.states.data.ReceiveData;
import freenet.support.Logger;

/**
 * The state an insert is in once the DataInsert has been received,
 * but while we are still waiting for the Accepted from upstream to
 * begin transferring the insert.  This is the state that inserts
 * restarted from TransferInsert return to.
 */
public class TransferInsertPending extends InsertPending {

    // hold it to keep circular buffer from lapping too soon
    private KeyInputStream doc;

    
    TransferInsertPending(InsertPending ancestor) {
        super(ancestor);
        dim = ancestor.dim;
        dimRecvTime   = ancestor.dimRecvTime;
        receivingData = ancestor.receivingData;
        dataReceived  = ancestor.dataReceived; 
    }

    TransferInsertPending(InsertPending ancestor, KeyInputStream doc) {
        this(ancestor);
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	   Core.logger.log(this, "Creating TransferInsertPending with stream "
			   +"for "+searchKey, Logger.DEBUG);
        this.doc = doc;
    }


    public String getName() {
        return "InsertRequest Pending Transfer";
    }

    
    //=== message handling =====================================================
    
    // these two cause the state to be recreated
    
    public State receivedMessage(Node n, QueryRejected qr) throws StateException {
	if(Core.logger.shouldLog(Logger.DEBUG,this)) {
	   Core.logger.log(this, "TransferInsertPending ("+searchKey+
			   ") got QueryRejected", new Exception("debug"),
			   Logger.DEBUG);
	   Core.logger.log(this, "TransferInsertPending ("+searchKey+
			   ") got QR, from: ", qr.initException,
			   Logger.DEBUG);
	}
    	if(!shouldAcceptFromRoutee(qr)) return this;
    	if(dataReceived != null && dataReceived.getCB() != Presentation.CB_OK) {
			routes.queryRejected(qr.getAttenuation(), accepted);
			cancelRestart();
			if(dataSent != null)
				Core.logger.log(this, "WTF? dataSent not null but still here: dataSent="+
						dataSent+", dataReceived="+dataReceived+" for "+this, Logger.ERROR);
			// Wait for the dataSent
			return this;
    	}
        try {
            super.receivedQueryRejected(n, qr);
        } catch (EndOfRouteException e) {
	    if(Core.logger.shouldLog(Logger.DEBUG,this))
	       Core.logger.log(this, "end of route exception "+searchKey,
			       Logger.DEBUG);
            return endRoute(n);
        } catch (RequestAbortException rae) {
            // we are either going to SendingReply or RequestDone with no route found
	    // So don't terminate()!
	    if(Core.logger.shouldLog(Logger.DEBUG,this))
		Core.logger.log(this, "request abort exception "+searchKey+
				" going to "+rae.state, rae, Logger.DEBUG);
            if (receivingData.result() == -1)
                receivingData.cancel();
            return rae.state;
        } finally {
            cleanDoc(); // if nobody is reading, nobody will
        }
	
        return new TransferInsertPending(this);
    }
    
    public State receivedMessage(Node n, RequestInitiator ri) throws StateException {
	if (this.ri == null || this.ri != ri) {
	    throw new BadStateException("Not my request initiator: "+ri);
	}
        try {
            super.receivedRequestInitiator(n, ri);
        } catch (EndOfRouteException e) {
            return endRoute(n);
        } catch (RequestAbortException rae) {
            // this is going to RequestDone with no route found
            receivingData.cancel();
            return rae.state;
        } finally {
            cleanDoc(); // if nobody is reading, nobody will
        }

        return new TransferInsertPending(this);
    }

    public State receivedMessage(Node n, QueueSendFinished qsf) {
        Core.logger.log(this, "Got "+qsf+" on "+this, Logger.MINOR);
        return super.receivedMessage(n, qsf);
    }

    public State receivedMessage(Node n, QueueSendFailed qsf) {
        Core.logger.log(this, "Got "+qsf+" on "+this, Logger.MINOR);
        try {
            super.receivedQueueSendFailed(n, qsf);
        } catch (RequestAbortException rae) {
            // this is going to RequestDone with no route found
            receivingData.cancel();
            return rae.state;
        }
        return this;
    }
    
    // the rest don't

    public State receivedMessage(Node n, InsertRequest ir) {
        super.receivedRequest(n, ir);
        return this;
    }

    public State receivedMessage(Node n, QueryRestarted qr) throws StateException {
        try {
            super.receivedQueryRestarted(n, qr);
        } catch (RequestAbortException rae) {
            // going to RequestDone with SendFailedException
            receivingData.cancel();
            return rae.state;
        }
        return this;
    }
    
    public State receivedMessage(Node n, SendFinished sf) throws StateTransition {
	// If it's feedbackSender, we may want to terminate
	// If it's outwardSender, we may want to go to SendingReply
	try {
	    super.receivedSendFinished(n, sf);
	} catch (RequestAbortException rae) {
            // going to RequestDone with SendFailedException
	    // Lost contact with origin node
            receivingData.cancel();
	    if(Core.logger.shouldLog(Logger.DEBUG,this))
		Core.logger.log(this, "Got RAE: "+rae, rae,
				Logger.DEBUG);
	    return rae.state;
	} catch (EndOfRouteException e) {
	    if(Core.logger.shouldLog(Logger.DEBUG,this))
		Core.logger.log(this, "end of route exception "+searchKey,
				Logger.DEBUG);
            return endRoute(n);
	}
	return this;
    }
    
    // if we didn't make it to TransferInsert yet...
    public State receivedMessage(Node n, DataReceived dr) throws StateException {
	// Routing does not care
	if(logDEBUG)
	    Core.logger.log(this, "TransferInsertPending ("+searchKey+") got "+
			 "DataReceived", Logger.DEBUG);
        if (receivingData != dr.source()) {
            throw new BadStateException("Not my DataReceived: "+dr);
        }
        dataReceived = dr;
        int cb = dr.getCB();
        switch (cb) {
            case Presentation.CB_OK:
                Core.logger.log(this, "Insert data received successfully!", Logger.MINOR);
		// logSuccess with the commit, elsewhere, after the Accepted
                break;

            case Presentation.CB_CACHE_FAILED:
            case Presentation.CB_BAD_DATA:
                fail(n, cb == Presentation.CB_CACHE_FAILED ? "Cache failed"
                                                           : "You sent bad data");
                // fall through
            default:
                try {
                    Core.logger.log(this,
                                 "Failed to receive insert with CB "+Presentation.getCBdescription(cb)
                                 +", on chain "+Long.toHexString(id),
                                 (cb == Presentation.CB_CACHE_FAILED ? Logger.ERROR : Logger.MINOR));
                    queryAborted(n);
                } finally {
                    cleanDoc(); // if nobody is reading, nobody will
                }
		terminateRouting(false, false, false);
                return new RequestDone(this);
        }
        return this;  // still waiting for Accepted
    }

    public State receivedMessage(Node n, DataSent ds) throws StateException {
        if(sendingData != ds.source())
            throw new BadStateException("Not our DataSent: "+ds);
        Core.logger.log(this, toString()+" received: "+ds, Logger.MINOR);
        dataSent = ds;
        int cb = ds.getCB();
        switch(cb) {
            case Presentation.CB_ABORTED:
                Core.logger.log(this, "Got DataSent: CB_ABORTED on "+this,
                        Logger.DEBUG);
                // Something broke in TransferInsert, we cancelled the send, it took a while to finish
                // No problem
                break;
            default:
                Core.logger.log(this, toString()+" received "+ds+": cb="+
                        Presentation.getCBname(cb), Logger.NORMAL);
        }
        return this;
    }
    
    public State receivedMessage(Node n, QueryAborted qf) throws StateException {
	// Routing does not care
        if (!fromOrigPeer(qf)) {
            throw new BadStateException("QueryAborted from the wrong peer!");
        }
// 	logFailure(n);
        queryAborted(n, qf);
        receivingData.cancel();
	
        cleanDoc(); // if nobody is reading, nobody will
	terminateRouting(false, false, false);
        return new RequestDone(this);
    }
    
    public State receivedMessage(Node n, DataReply dr) throws StateException {
        State ret;
        try {
            ReceiveData recv = this.receivingData;
	    // Will note reply time if successful
            ret = super.receivedDataReply(n, dr);  // could BSE out
            recv.cancel();  // terminating insert chain
        } finally {
            cleanDoc(); // if nobody is reading, nobody will
        }
        return ret;
    }
    
    public State receivedMessage(Node n, Accepted a) throws StateException {
        if(!super.receivedAccepted(n, a)) return this;
        try {
            Core.logger.log(this, "Got Accepted " + 
			 ((System.currentTimeMillis() - dimRecvTime) / 1000)
			 +"s after DataInsert", Logger.DEBUG);
            if (doc == null)
                relayInsert(n);
            else {
                relayInsert(n, doc);
		doc = null; // Don't close it, don't hold on to it
	    }
        } catch (RequestAbortException rae) {
            // immediate restart, or failure
            return rae.state;
        }
        return new TransferInsert(this);
    }
    
    public State receivedMessage(Node n, InsertReply ir) throws StateException {
		if(!shouldAcceptFromRoutee(ir)) return this;
	insertReplyTime = System.currentTimeMillis();
        if (!approved) {
            cancelRestart();
            accepted = true;
	    acceptedTime = insertReplyTime;
            approved = true;
            try {
                Core.logger.log(this, "Got InsertReply " + 
			     ((System.currentTimeMillis() - dimRecvTime) / 1000)
			     +"s after DataInsert", Logger.DEBUG);
                insertReply(n, Core.storeDataTime(origHopsToLive, searchKey.getExpectedDataLength(), remoteQueueTimeout()));
                if (doc == null)
                    relayInsert(n);
                else {
                    relayInsert(n, doc);
		    doc = null; // Don't close it, don't hold on to it
		}
            } catch (RequestAbortException rae) {
                return rae.state;
            }
        }
        return new TransferInsert(this);
    }
    
    private final State endRoute(Node n) throws StateTransition {
// 	logFailure(n);
        try {
            // TransferInsertPending runs out of HTL
            // Still transferring
            // Timeout for client: 250 bytes/sec = 4 ms/byte + 1 hop time for local processing.
            insertReply(n, receiveInsertTimeout());
        } catch (RequestAbortException rae) {
	    if(logDEBUG)
		Core.logger.log(this, "Got "+rae+" in endRoute()",
				rae, Logger.DEBUG);
            return rae.state;
        }
        State s = new ReceivingInsert(this);
	if(logDEBUG)
	    Core.logger.log(this, "Going to "+s+" ("+this+")",
			    Logger.DEBUG);
        if (dataReceived != null)
            throw new StateTransition(s, dataReceived, true);
        else
            return s;
    }
    
    // close the doc if necessary before leaving state.
    private final void cleanDoc() {
	if(logDEBUG) {
	    Core.logger.log(this, "TransferInsertPending("+searchKey+
			    ").cleanDoc()", Logger.DEBUG);
	    if (sendingData != null) Core.logger.log(this, "sending data",
						     Logger.DEBUG);
	}
	if (doc != null) {
	    if (sendingData == null) {
            	try {
		    if(logDEBUG)
		     	Core.logger.log(this, "TransferInsertPending("+searchKey+
				     	") really closing", Logger.DEBUG);
		    doc.close();
		    doc = null;
            	} catch (IOException e) {}
	    } else {
		doc = null;
		if(logDEBUG)
		    Core.logger.log(this, "TransferInsertPending("+searchKey+
				    ") really closing while sending", 
				    Logger.DEBUG);
	    }
	}
    }

    protected void finalize() {
	if(logDEBUG)
	    Core.logger.log(this, "finalizing TransferInsertPending for "+
			    this, Logger.DEBUG);
	cleanDoc();
    }
}    

