package freenet.node.states.request;

import freenet.*;
import freenet.node.*;
import freenet.node.states.data.*;
import freenet.node.ds.KeyCollisionException;
import freenet.message.*;
import freenet.support.Logger;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Vector;
import java.io.IOException;

/**
 * The state a request is in when transferring data from upstream
 * to the requesting peer, and caching it.
 */
public class TransferReply extends DataPending {

    protected static long instances;
    protected static HashSet transferRepliesRunning =
        new HashSet(); // stores TransferReply.toString()
    protected static final Object instancesLock = new Object();
    protected final String myInitialToString; // for transferRepliesRunning
    
    // message queue
    private Vector mq = new Vector();
    
    TransferReply(Pending ancestor) {
        super(ancestor);
        this.receivingData = ancestor.receivingData;
        this.sendingData   = ancestor.sendingData;
        this.storeData     = ancestor.storeData;
        this.accepted      = ancestor.accepted;
        long i;
        if(logDEBUG) {
            myInitialToString = toString();
            synchronized(instancesLock) {
                instances++;
                i = instances;
                transferRepliesRunning.add(myInitialToString);
            }
            Core.logger.log(this, "Created "+this+" from "+ancestor+": instances now "+
                    i, Logger.DEBUG);
        } else {
            myInitialToString = null;
        }
    }
    
    public final String getName() {
        return "Transferring Reply";
    }

    private final State transition(State s, boolean doQueue) throws StateTransition {
        NodeMessageObject[] q = new NodeMessageObject[mq.size()];
        mq.copyInto(q);
        throw new StateTransition(s, q, doQueue);
    }

    /**
     * Sends CB_RESTARTED down the send pipe and causes the receive pipe
     * to go into a data-eating state.  Unless it was too late, they will
     * not schedule their DataStateReply (tiny chance of a harmless BSE
     * in the log, no doubt users will report it as a bug..)
     */
    private final void cancelStreams() {
        // Commented out by 2002-07-13 by Oskar. I consider this dangerous
        // code, and also redundant - one might as well wait for the the 
        // CB on the data. 
        //receivingData.cancel();                         // shut
        //sendingData.abort(Presentation.CB_RESTARTED);   // up
    }


    //=== message handling =====================================================
    
    public State receivedMessage(Node n, QueryRestarted qr) throws StateException {
        if (!shouldAcceptFromRoutee(qr)) return this;
        cancelStreams();
        mq.addElement(qr);
	// We will still get it, from that node... so it's part of the timeline, keep counting for ngrouting stats
        return transition(new DataPending(this), true);
    }
    
    public State receivedMessage(Node n, SendFinished sf) {
        // Doesn't matter here
        mq.addElement(sf);
        return this;
    }
    
    public State receivedMessage(Node n, QueueSendFinished qsf) {
        // Doesn't matter - obviously we sent the request!
        return this;
    }
    
    public State receivedMessage(Node n, QueueSendFailed qsf) {
        // Hmmm
        Core.logger.log(this, "Received "+qsf+" on "+this, Logger.NORMAL);
        return this;
    }
    
    public State receivedMessage(Node n, QueryRejected qr) throws StateException {
        if (!shouldAcceptFromRoutee(qr)) return this;
        cancelStreams();
        mq.addElement(qr);
        return transition(new DataPending(this), true);
	// The Pending.receivedQueryRejected will log the ngrouting time stats
	// And tell Routing
    }
    
    // could belong after a restart
    public State receivedMessage(Node n, DataNotFound dnf) {
        mq.addElement(dnf);
	// Will be handled later, including Routing
        return this;
    }
    
    // could belong after a restart
    public State receivedMessage(Node n, DataReply dr) {
        mq.addElement(dr);
        return this;
    }
    
    public State receivedMessage(Node n, Accepted a) {
        // who cares ;)
        return this;
    }

    public State receivedMessage(Node n, StoreData sd) throws StateException {
        super.receivedStoreData(n, sd);
        return this;
    }

    public State receivedMessage(Node n, DataRequest dr) {
        super.receivedRequest(n, dr);
        return this;
    }
    

    private State checkTransition(Node n) throws StateTransition {
        if (dataReceived == null || dataSent == null) {
            return this;
        }
        // already completed receiving and sending
        if (dataReceived.getCB() == Presentation.CB_OK) {
	    if(logDEBUG) Core.logger.log(this, "CB_OK: committing "+
				      this, Logger.DEBUG);
            try {
                receivingData.commit();  // make the key available
            } catch (KeyCollisionException e) {
                // Yay, we got the data
                transition(new RequestDone(this), false);
            } catch (IOException e) {
                fail(n, "Cache failed");
                Core.logger.log(this, "Cache failed on commit for "+this, e, 
			     Logger.ERROR);
// 		logSuccess(n);
		// Routing already terminated
                transition(new RequestDone(this), false);
            }
            
// 	    logSuccess(n);
	    if(logDEBUG) Core.logger.log(this, "Going to AWSD for "+this, 
				      new Exception("debug"), Logger.DEBUG);
            State awsd;
            if (storeData == null) {
                NoStoreData nosd = new NoStoreData(this);
                // Don't use Core.storeDataTime, they are not transferring, this is a request.
				long timeout = 2 * hopTimeHTL(hopsToLive, remoteQueueTimeout());
                n.schedule(timeout, nosd);
                awsd = new AwaitingStoreData(this, nosd);
            } else {
                mq.addElement(storeData);
                awsd = new AwaitingStoreData(this, null);
            }
            return transition(awsd, true);
        } else {
	    if(logDEBUG)
		Core.logger.log(this, "dataReceived control bytes not CB_OK, "+
			     "going to RequestDone for "+this, Logger.DEBUG);
	    terminateRouting(false, false, false);
// 	    logFailedTransfer(n);
	    return transition(new RequestDone(this), false);
	}
    }
    
    public State receivedMessage(Node n, DataReceived dr) throws StateException {
        if (receivingData != dr.source()) {
            throw new BadStateException("Not my DataReceived: "+dr+" for "+this);
        }
        dataReceived = dr;
        int cb = dr.getCB();
	endTransferTime = System.currentTimeMillis();
        switch (cb) {
            
            case Presentation.CB_OK:
                Core.logger.log(this, "Data received successfully! for "+this,
			     Logger.MINOR);
		if(replyTime <= 0)
		    Core.logger.log(this, "replyTime = "+replyTime+" for "+this,
				 Logger.NORMAL);
		routes.transferSucceeded(replyTime - routedTime, hopsToLive,
					 receivingData.length(),
					 endTransferTime - replyTime);
                break;
		
            case Presentation.CB_CACHE_FAILED:
                Core.logger.log(this, "Cache failed while receiving data! for "+this,
			     Logger.ERROR);
		terminateRouting(false, false, false);
		Core.diagnostics.occurrenceCounting("sendFailedCacheFailed",1);
                // the repercussions will strike in the DataSent..
		// Ignore for NGRouting, our fault
                break;
		
	    case Presentation.CB_BAD_DATA:
		Core.logger.log(this, "Upstream node sent bad data! for "+this,
			     Logger.NORMAL);
                // the null check is not really needed but I hate NPEs..
	    	reportTransferFailed();
                // do the restart with the DataSent
                Core.diagnostics.occurrenceCounting("sendFailedCorruptData",1);
                break;
		
	    case Presentation.CB_RECV_CONN_DIED:
		Core.logger.log(this, "Upstream node connection died for "+
			     this, Logger.NORMAL);
	    reportTransferFailed();
		Core.diagnostics.occurrenceCounting("recvConnDiedInTransfer", 1);
		break;
		
            case Presentation.CB_RESTARTED:
                Core.logger.log(this, "Upstream node restarted for "+this,
                        Logger.MINOR);
                // pick it up with the DataSent
            	Core.diagnostics.occurrenceCounting("sendFailedSourceRestarted",1);
                break;
		
        case Presentation.CB_ABORTED:
        	// Only for stats - not valid
        	Core.logger.log(this, "Failed to send because source sent CB_ABORTED on "+this,
        			Logger.NORMAL);
        	Core.diagnostics.occurrenceCounting("sendFailedSourceAborted",1);
            break;
            
	    default:
                if (lastPeer != null) {
                	reportTransferFailed();
		    Core.diagnostics.occurrenceCounting("sendFailedUnknownCB",1);
		    Core.logger.log(this, "Aborted send due to unknown CB: "+Presentation.getCBname(cb)+" for "+this,
		    		Logger.NORMAL);
		}
        Core.logger.log(this,
                "Failed to receive data with CB "+
                Presentation.getCBdescription(cb)+", for "+this,
                Logger.MINOR);
        if(dataSent == null)
            sendingData.abort(Presentation.CB_RESTARTED, false);
        }
	
        return checkTransition(n);
    }

    // FIXME: get rid
    private void reportTransferFailed() {
		long length = receivingData.length();
		length = Key.
		getTransmissionLength(length, 
					  Key.getPartSize(length));
		routes.transferFailed(replyTime - routedTime,
				  hopsToLive, length,
				  endTransferTime - replyTime);
    }
    
    public State receivedMessage(Node n, DataSent ds) throws StateException {
	// Routing doesn't care
        if (sendingData != ds.source()) {
            throw new BadStateException("Not my DataSent: "+ds+" for "+this);
        }
        dataSent = ds;
        int cb = ds.getCB();
        switch (cb) {
            
	case Presentation.CB_OK:
	    Core.logger.log(this, "Data sent successfully! for "+this, Logger.MINOR);
	    break;
	    
	case Presentation.CB_RESTARTED:
	    Core.logger.log(this, "Send failed, stream restarted for "+this, 
			 Logger.MINOR);
	    // we are expecting a QueryRestarted from the next node
	    // but we schedule a restart here in case it never comes
	    scheduleRestart(n, Core.hopTime(2,0)); 
	    // do Queue starting from pending
	    transition(new DataPending(this), true);
	    
	case Presentation.CB_CACHE_FAILED:
	    Core.logger.log(this, "Send failed, cache broken! for "+this, Logger.ERROR);
	    fail(n, "Cache failed");
	    checkTransition(n); // will rollback and then transition to RequestDone
	    
	case Presentation.CB_SEND_CONN_DIED:
	    Core.logger.log(this, "Send failed, connection died for "+this, 
			 Logger.NORMAL);
	    // ALWAYS COMPLETE THE RECEIVE
	    // Basic anonymity property: attacker cannot prove the existance of
	    // a datum without propagating it.
	    Core.diagnostics.occurrenceCounting("sendConnDiedInTransfer", 1);
		checkTransition(n);
	    break;
	    // we only care about the DataReceived now..
	    
	case Presentation.CB_RECEIVER_KILLED:
	    Core.logger.log(this, "Send terminated by receiver: "+this,
	            Logger.MINOR);
	    // See above: we must complete the receive for plausible deniability
	    checkTransition(n);
	    break;

	case Presentation.CB_SEND_TIMEOUT:
	    Core.logger.log(this, "Send timeout: "+this,
	            Logger.MINOR);
	    // See above: we must complete the receive for plausible deniability
	    checkTransition(n);
	    break;
	    
	default:
	    Core.logger.log(this,
			 "Failed to send data with CB "+Presentation.getCBdescription(cb)
			 +", for "+this,
			 Logger.NORMAL);
	    // As above: if we have completed the receive as well, go to AWSD
	    // or RequestDone depending on success. Otherwise wait for the dataReceived.
        }
        
        return checkTransition(n);
    }
    
    protected void finalize() {
        long i;
        if(logDEBUG) {
            StringBuffer sb = new StringBuffer();
            synchronized(instancesLock) {
                instances--;
                i = instances;
                transferRepliesRunning.remove(myInitialToString);
                for(Iterator it=transferRepliesRunning.iterator();it.hasNext();) {
                    sb.append(it.next().toString());
                    sb.append('\n');
                }
            }
            Core.logger.log(this, "Destroying "+this+": instances now "+
                    i+": details:\n"+sb.toString(), Logger.DEBUG);
        }
    }
}



