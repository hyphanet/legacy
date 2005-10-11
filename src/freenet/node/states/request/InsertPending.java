package freenet.node.states.request;

import java.io.IOException;

import freenet.CommunicationException;
import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.Presentation;
import freenet.TrailerWriter;
import freenet.fs.dir.BufferException;
import freenet.message.Accepted;
import freenet.message.DataInsert;
import freenet.message.DataReply;
import freenet.message.InsertReply;
import freenet.message.InsertRequest;
import freenet.message.QueryAborted;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.Request;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.node.StateTransition;
import freenet.node.ds.KeyCollisionException;
import freenet.node.ds.KeyInputStream;
import freenet.node.states.data.SendData;
import freenet.support.Logger;

/**
 * The state that an insert is in when newly begun, before the DataInsert
 * has been received.
 */
public class InsertPending extends Pending {

    NoInsert ni;
    DataInsert dim;    // cache it for sending upstream
    
    long dimRecvTime,  // when we got the DataInsert
         checkTime,    // when we got the Accepted/InsertReply
	 startedSendTime, // when we started the SendData
	 insertReplyTime = -1; // when we got the InsertReply

    boolean approved = false;  // got an InsertReply?
    private static final long NO_INSERT_TIMEOUT = Core.hopTime(7,0);
    
    
    public String toString() {
		String s = super.toString();
		return s + ", " + (approved ? "approved" : "not-approved") + ", insertReplyTime=" + insertReplyTime +
			", dim="+dim;
	}
    
    /**
     * For new requests.
     */
    public InsertPending(long id, int htl, Key key, Identity orig,
                         FeedbackToken ft, RequestInitiator ri,
						 boolean routeToNewestNodes,
						 boolean ignoreDS) {
        super(id, htl, key, orig, ft, ri, routeToNewestNodes, ignoreDS);
    }

    /**
     * For restarts from InsertPending.
     */
    InsertPending(InsertPending ancestor) {
		super(ancestor);
		ni = ancestor.ni;
		insertReplyTime = ancestor.insertReplyTime;
	}

    // Override fail() to clean up..
    void fail(Node n, String reason, FieldSet otherFields) {
    	if(receivingData != null)
    		receivingData.cancel(); // clean up tempfile
    	super.fail(n, reason, otherFields);
    }

    public String getName() {
        return "InsertRequest Pending";
    }

    final Request createRequest(FieldSet otherFields, Identity identity) {
        return new InsertRequest(id, hopsToLive, searchKey, identity, otherFields);
    }

    
    //=== message handling =====================================================
    
    // these two cause the state to be recreated
    
    public State receivedMessage(Node n, QueryRejected qr) throws StateException {
        try {
            super.receivedQueryRejected(n, qr);
        } catch (EndOfRouteException e) {
	    // Could possibly restart via AwaitingInsert
            return endRoute(n);
        } catch (RequestAbortException rae) {
            cancelNoInsert();
            // we are either going to SendingReply or RequestDone with no route found
            return rae.state;
        }
        return new InsertPending(this);
    }

    public State receivedMessage(Node n, RequestInitiator ri) throws StateException {
		if (this.ri == null || ri != this.ri) {
			throw new BadStateException("Not my request initiator: " + ri + " for " + this);
		}
		if (ni == null) {
			ni = new NoInsert(this);
			n.schedule(NO_INSERT_TIMEOUT, ni);
		}
		try {
			super.receivedRequestInitiator(n, ri);
		} catch (EndOfRouteException e) {
			// see above
			terminateRouting(false, origHopsToLive > 0, true);
			return endRoute(n);
		} catch (RequestAbortException rae) {
			cancelNoInsert();
			// this is going to RequestDone with no route found
			// Should be terminate()d at throw time, not at catch time
			return rae.state;
		}
		return new InsertPending(this);
	}

    // the rest don't

    public State receivedMessage(Node n, InsertRequest ir) {
        super.receivedRequest(n, ir);
        return this;
    }

    public State receivedMessage(Node n, QueryRestarted qr) throws StateException {
        try {
            super.receivedQueryRestarted(n, qr);
        }
        catch (RequestAbortException rae) {
            cancelNoInsert();
            // going to RequestDone with SendFailedException
            return rae.state;
        }
        return this;
    }

    public State receivedMessage(Node n, NoInsert noInsert) throws StateException {
		if (this.ni != noInsert) {
			throw new BadStateException("Not my NoInsert: " + noInsert + " for " + this);
		}
		fail(n, "DataInsert never received");
		queryAborted(n);
		// Not our fault, this is from the requester, so don't tell Routing
		terminateRouting(false, false, false);
		return new RequestDone(this);
	}

    public State receivedMessage(Node n, QueryAborted qf) throws StateException {
		if (!fromOrigPeer(qf)) {
			throw new BadStateException("QueryAborted from the wrong peer! for " + this);
		}
		cancelNoInsert();
		queryAborted(n, qf);
		terminateRouting(false, false, false);
		return new RequestDone(this);
	}

    public State receivedMessage(Node n, DataInsert dim) throws StateException {
        if (!fromOrigPeer(dim)) {
            throw new BadStateException("DataInsert from the wrong peer! for "+this);
        }
        dimRecvTime = System.currentTimeMillis();

        cancelNoInsert();

        this.dim = dim;
        
        try {
            // Always ignore datastore.
            // We want inserts to continue despite a key collision.
            // They can always flood via requests: stopping on a key collision doesn't gain us anything.
            receivingData = dim.cacheData(n, searchKey, /*ignoreDS*/true);
        } catch (KeyCollisionException e) {
            // Impossible
            Core.logger.log(this, "Caught "+e+" in "+this+" processing "+dim+" - impossible!", Logger.ERROR);
	    return new RequestDone(this);
        } catch (IOException e) {
            dim.drop(n);
            fail(n, "I/O error receiving insert");
            Core.logger.log(this, "Failed to cache insert for " + this,
			 e, Logger.ERROR);
            queryAborted(n);
	    terminateRouting(false, false, false);
            return new RequestDone(this);
        }
        
        try {
            if (accepted) {  // got the Accepted, or InsertReply
		if(logDEBUG) Core.logger.log(this, "Got DataInsert " + 
					  ((dimRecvTime - checkTime) / 1000)+
					  "s after " + 
					  (approved ? "InsertReply" : "Accepted")+
					  " for "+this, Logger.DEBUG);
		
                relayInsert(n);
                return new TransferInsert(this);
            } else {
                KeyInputStream doc = receivingData.getKeyInputStream();
                doc.setParent(id, n.ticker().getMessageHandler(),
                              "Set to TransferInsertPending for "+
			      searchKey);
                return new TransferInsertPending(this, doc);
            }
        } catch (RequestAbortException e) {
            // immediate restart, or failure
            return e.state;
        } catch (IOException e) {
            receivingData.cancel();
            fail(n, "I/O error receiving insert");
            if (e instanceof BufferException)
                Core.logger.log(this, "Failed to cache insert: "+e+" for "+this,
			     Logger.MINOR);
            else
                Core.logger.log(this, "Failed to cache insert for "+this,
			     e, Logger.ERROR);
            queryAborted(n);
	    terminateRouting(false, false, false);
            return new RequestDone(this);
        } finally {
            receivingData.schedule(n);
        }
    }
    
    public State receivedMessage(Node n, DataReply dr) throws StateException {
        State ret = super.receivedDataReply(n, dr);
	// super will deal with Routing
        if (this != ret) cancelNoInsert();
        return ret;
    }
    
    public State receivedMessage(Node n, Accepted a) throws StateException {
        if (!accepted)
            checkTime = System.currentTimeMillis();
        super.receivedAccepted(n, a);
        return this;
    }
    
    public State receivedMessage(Node n, InsertReply ir) throws StateException {
		if (!fromLastPeer(ir)) {
			throw new BadStateException("InsertReply from wrong peer!");
		}
		insertReplyTime = System.currentTimeMillis();
		if (!approved) {
			if (routedTime > 0)
				Core.diagnostics.occurrenceContinuous("hopTime", (System.currentTimeMillis() - routedTime) / hopsToLive);

			checkTime = System.currentTimeMillis();
			cancelRestart();
			accepted = true; // essentially..
			approved = true;
			routes.routeAccepted();
			try {
				// FIXME: subtract time to send the insertReply from the insertReplyTime
				// Or make insertReply asynchronous (with feedback...)
				insertReply(n, Core.storeDataTime(origHopsToLive, searchKey.getExpectedDataLength(), remoteQueueTimeout()));
			} catch (RequestAbortException rae) {
				cancelNoInsert();
				// send failed to originator..
				return rae.state;
			}
		}
		return this;
	}
    
    public State receivedMessage(Node n, SendFinished sf) throws StateTransition {
    	// Subclass implementations may throw
		try {
			super.receivedSendFinished(n, sf);
		} catch (RequestAbortException rae) {
		    cancelNoInsert();
			return rae.state;
		} catch (EndOfRouteException e) {
			return endRoute(n);
		}
		return this;
	}
    
	public State receivedMessage(Node n, QueueSendFailed qsf) {
	    // FIXME: can we assume it's always fatal and forego the exception?
	    try {
            super.receivedQueueSendFailed(n, qsf);
        } catch (RequestAbortException rae) {
            return rae.state;
        }
        return this;
	}
	
	public State receivedMessage(Node n, QueueSendFinished qsf) {
	    super.receivedQueueSendFinished(n, qsf);
	    return this;
	}
    
    //=== support methods ======================================================
    
    final void cancelNoInsert() {
        if (ni != null) {
            if(logDEBUG)
                Core.logger.log(this, "cancelling "+ni, Logger.DEBUG);
            ni.cancel();
            ni = null;
        }
    }

    private final State endRoute(Node n) {
		cancelRestart();
		// 	logFailure(n);
		terminateRouting(false, false, false); // just in case
		// Nobody to route it to, so terminate any routing

		try {
			insertReply(n, endRouteTimeout());
		} catch (RequestAbortException rae) {
			cancelNoInsert();
			return rae.state;
		}
		return endRouteState(n);
	}
    
    protected long endRouteTimeout() {
        return NO_INSERT_TIMEOUT;
    }

    /**
     * @return the state we will return from endRoute.
     * Intentionally not final. Should be overridden in transfer
     * states, does not know about transfers or DIM!
     */
    protected State endRouteState(Node n) {
		AwaitingInsert ai = new AwaitingInsert(this);
		if(this.dim != null)
            try {
                return ai.receivedMessage(n, dim);
            } catch (BadStateException e) {
                Core.logger.log(this, "Caught "+e+" ending route for "+this, Logger.ERROR);
                return new RequestDone(this);
            }
        else
		    return ai;
	}
    
    protected State publicEndRoute(Node n) {
		return endRoute(n);
	} // FIXME!

	final void queryAborted(Node n) {
	    cancelRestart();
		queryAborted(n, new QueryAborted(id));
	}

	final void queryAborted(Node n, QueryAborted qf) {
		if (lastPeer != null) {
			try {
				n.sendMessage(qf, lastPeer, super.origHopTimeHTL(remoteQueueTimeout()));
			} catch (CommunicationException e) {
				Core.logger.log(this, "Failed to send QueryAborted upstream for " + this, e, Logger.MINOR);
			}
		}
	}
    
    final void insertReply(Node n, long timeout) throws RequestAbortException {
        try {
            ft.insertReply(n, timeout);
        } catch (CommunicationException e) {
            if (receivingData != null) receivingData.cancel();
            queryAborted(n);
            Core.logger.log(this,
			 "Failed to send back InsertReply, dropping for "+
			 this, e, Logger.MINOR);
	    terminateRouting(false, false, false);
            throw new RequestAbortException(new RequestDone(this));
        }
    }
    
    /** Schedules the SendData state to relay the data upstream
     * @throws RequestAbortException  if there is a failure
     */
    void relayInsert(Node n) throws RequestAbortException {
        try {
            KeyInputStream doc = receivingData.getKeyInputStream();
            doc.setParent(id, n.ticker().getMessageHandler(),
                          "InsertPending.relayInsert");
            try {
                relayInsert(n, doc);
            } catch (RequestAbortException e) {
                doc.close();
                throw e;
            }
        } catch (IOException e) {
            receivingData.cancel();
            fail(n, "I/O error receiving insert");
            queryAborted(n);
            int r = receivingData.result();
            if(r == -1 || r == Presentation.CB_OK)
                Core.logger.log(this, "Failed to read data from store for "+

			     this+":"+e, e, Logger.NORMAL);
            // Failed to receive insert data is fatal
	    terminateRouting(false, false, false);
            throw new RequestAbortException(new RequestDone(this));
        }
    }
    
    void relayInsert(Node n, KeyInputStream doc) throws RequestAbortException {
		TrailerWriter out;
		startedSendTime = System.currentTimeMillis();
		try {
			receivedTime = -2; // receivedTime on the next message will be meaningless
			out = n.sendMessage(dim, lastPeer, NO_INSERT_TIMEOUT);
			if (logDEBUG)
				Core.logger.log(this, "Sent message for " + this +" on " + lastPeer, Logger.DEBUG);
		} catch (CommunicationException e) {
			// restart right away
			scheduleRestart(n, 0);
			throw new RequestAbortException(this);
		}
		if (logDEBUG)
			Core.logger.log(this, "Relaying insert pending " + searchKey + " without RAE for " + this, 
			        new Exception("debug"), Logger.DEBUG);
		sendingData = new SendData(Core.getRandSource().nextLong(), this.id, out, doc, 
		        doc.length(), doc.getStorables().getPartSize(), true, n);
		sendingData.schedule(n);
		if (logDEBUG)
			Core.logger.log(this, "Scheduled " + sendingData + " for " + this, Logger.DEBUG);
	}

	protected boolean isInsert() {
		return true;
	}

    public boolean wasInsert() {
        return true;
    }
    
    protected long receiveInsertTimeout() {
        return searchKey.getExpectedTransmissionLength()*4+Core.hopTime(4,0);
    }
}
