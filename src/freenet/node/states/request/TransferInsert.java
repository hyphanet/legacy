package freenet.node.states.request;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import freenet.CommunicationException;
import freenet.Core;
import freenet.Presentation;
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
import freenet.node.NodeMessageObject;
import freenet.node.State;
import freenet.node.StateException;
import freenet.node.StateTransition;
import freenet.node.states.data.DataReceived;
import freenet.node.states.data.DataSent;
import freenet.support.Logger;

/**
 * The state of an insert after both DataInsert and Accepted.
 * Data is being transferred upstream, but we may still be
 * waiting for an InsertReply or DataReply.  Since we already
 * have the DataInsert, if we restart we'll go back to
 * TransferInsertPending, not InsertPending.
 */
public class TransferInsert extends InsertPending {
	
	protected static long instances;
	protected static LinkedList transferInsertsRunning =
		new LinkedList(); // stores TransferInsert.toString()
	protected static final Object instancesLock = new Object();
	protected final String myInitialToString; // for transferRepliesRunning

	/** Are we a slave to an InsertAwaitingStoreData?
     * Don't need to reset this as it is not inherited.
     * If restart, will not go directly to the same TI.. because it will
     * go to RequestDone, TransferInsertPending or ReceivngReply.
     */
	boolean iAWSDSlave = false;
	
	// message queue
	private Vector mq = new Vector();

	TransferInsert(InsertPending ancestor) {
		super(ancestor);
		dim = ancestor.dim;
		dimRecvTime = ancestor.dimRecvTime;
		checkTime = ancestor.checkTime;
		receivingData = ancestor.receivingData;
		dataReceived = ancestor.dataReceived;
		sendingData = ancestor.sendingData;
		accepted = ancestor.accepted;
		approved = ancestor.approved;
		long i;
		if(logDEBUG) {
			myInitialToString = toString()+ " at " +
				new SimpleDateFormat().format(new Date());
			synchronized(instancesLock) {
				instances++;
				i = instances;
				transferInsertsRunning.add(myInitialToString);
			}
			Core.logger.log(this, "Created "+this+" from "+ancestor+": instances now "+
					i, Logger.DEBUG);
		} else {
			myInitialToString = null;
		}
	}

	public final String getName() {
		return "Transferring Insert";
	}

	private final State transition(State s, boolean doQueue)
		throws StateTransition {
		NodeMessageObject[] q = new NodeMessageObject[mq.size()];
		mq.copyInto(q);
		throw new StateTransition(s, q, doQueue);
	}

	//=== message handling =====================================================

	public State receivedMessage(Node n, QueryAborted qf)
		throws StateException {
		if (!fromOrigPeer(qf)) {
			throw new BadStateException(
				"QueryAborted from the wrong peer! for " + this);
		}
		receivingData.cancel();
		sendingData.abort(Presentation.CB_ABORTED, true);
		Core.diagnostics.occurrenceCounting("sendFailedInsertAborted", 1);
		mq.addElement(qf);
		// From the requester, so Routing does not need to know
		return transition(new TransferInsertPending(this), true);
	}

	// this is delicate, because we want to remain in TransferInsert
	// if possible
	public State receivedMessage(Node n, QueryRestarted qr)
		throws StateException {
		try {
		    if(!shouldAcceptFromRoutee(qr)) return this;
		    if(approved) {
		        // Give the benefit of the doubt; target has got so far as to
		        // send us an InsertReply, hopefully they can find another
		        // place to send the data. If not, they'll reject. If they
		        // don't reject, then they'll timeout in IAWSD - but there is
		        // no threat here, as they can just swallow an insert and fake
		        // a StoreData in any case.
		        // Apart from that, post-IR QRs are a PITA, and handling them
		        // differently would require message ordering.
		        return this;
		    }
			super.receivedQueryRestarted(n, qr);
		} catch (RequestAbortException rae) {
			// going to RequestDone with SendFailedException
			receivingData.cancel();
			Core.diagnostics.occurrenceCounting("sendFailedRestartFailed", 1);
			sendingData.abort(Presentation.CB_ABORTED, true);
			queryAborted(n);
			return transition(rae.state, false); // drop queue
		}
		return this;
	}

	public State receivedMessage(Node n, SendFinished sf) {
		// Ignore it, we have incoming data
		if (logDEBUG)
			Core.logger.log(
				this,
				"Ignoring " + sf + " in " + this,
				Logger.DEBUG);
		return this;
	}

	// must have timed out waiting for InsertReply/DataReply
	public State receivedMessage(Node n, RequestInitiator ri)
		throws StateException {
		if (this.ri == null || this.ri != ri) {
		    if(ri.scheduled())
		        throw new BadStateException(
		                "Not my request initiator: " + ri + " for " + this);
		    else {
		        Core.logger.log(this, "Got cancelled request initiator: "+ri,
		                Logger.MINOR);
		        return this;
		    }
		}
		sendingData.abort(Presentation.CB_ABORTED, true);
		Core.diagnostics.occurrenceCounting("sendFailedInsertTimedOut", 1);
		try {
			// Will cause a searchFailed() and a restart
			super.receivedRequestInitiator(n, ri);
		} catch (EndOfRouteException e) {
			return super.publicEndRoute(n);
		} catch (RequestAbortException rae) {
			cancelNoInsert();
			// this is going to RequestDone with no route found
			return rae.state;
		}
		return transition(new TransferInsertPending(this), false);
	}

	/**
	 * Restart after InsertAwaitingStoreData got a NoStoreData.
	 */
	public State receivedNoStoreData(Node n, NoStoreData nosd)
		throws BadStateException, StateTransition {
	    ri = new RequestInitiator(this, System.currentTimeMillis());
		try {
			// Will cause a searchFailed() and a restart
			super.receivedRequestInitiator(n, ri);
		} catch (EndOfRouteException e) {
			return super.publicEndRoute(n);
		} catch (RequestAbortException rae) {
			cancelNoInsert();
			// this is going to RequestDone with no route found
			return rae.state;
		} catch (BadStateException e) {
		    Core.logger.log(this, "AAAARGH: Caught "+e+" in "+this, 
		            Logger.ERROR);
		    throw e;
		}
		return transition(new TransferInsertPending(this), false);
	}
	
	public State receivedMessage(Node n, QueryRejected qr)
		throws StateException {
		if(!shouldAcceptFromRoutee(qr)) return this;
		if(dataSent == null)
		    sendingData.abort(Presentation.CB_ABORTED, true);
		// else already finished
		Core.diagnostics.occurrenceCounting("sendFailedInsertRejected", 1);
		mq.addElement(qr);
		// QR from the node we routed to
		// Will cause a searchFailed in TIP.rQR
		return transition(new TransferInsertPending(this), true);
	}

	public State receivedMessage(Node n, DataReply dr) throws StateException {
		if (!fromLastPeer(dr)) {
		    dr.drop(n);
			throw new BadStateException(
				"DataReply from the wrong peer! for " + this);
		}
		Core.diagnostics.occurrenceCounting("sendFailedCollision", 1);
		sendingData.abort(Presentation.CB_ABORTED, true);
		mq.addElement(dr);
		return transition(new TransferInsertPending(this), true);
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
    
	public State receivedMessage(Node n, InsertReply ir)
		throws StateException {
	    if (!shouldAcceptFromRoutee(ir)) return this;
		if (logDEBUG)
			Core.logger.log(this, "Got " + ir + " for " + this, Logger.DEBUG);
		if (approved) {
			// this could belong after a restart..
			mq.addElement(ir);
			if (logDEBUG)
				Core.logger.log(
					this,
					"Already approved, ignoring for " + this,
					Logger.DEBUG);
			return this;
		}
		if (logDEBUG)
			Core.logger.log(this, "Approving... " + this, Logger.DEBUG);
		approved = true;
		checkTime = System.currentTimeMillis();
		cancelRestart();
		if (logDEBUG)
			Core.logger.log(
				this,
				"Cancelled restart for " + this,
				Logger.DEBUG);
		try {
			insertReply(n, Core.storeDataTime(origHopsToLive, searchKey.getExpectedDataLength(), remoteQueueTimeout()));
		} catch (RequestAbortException rae) {
			// send failed..
			Core.diagnostics.occurrenceCounting("sendFailedRestartFailed", 1);
			sendingData.abort(Presentation.CB_ABORTED, true);
			// QueryAborted already sent
			transition(rae.state, false);
			// Don't care for Routing
		}
		// keep on trucking..
		return checkTransition(n);
	}

	public State receivedMessage(Node n, Accepted a) {
		// we don't care
		return this;
	}

	public State receivedMessage(Node n, StoreData sd) throws StateException {
		super.receivedStoreData(n, sd);
		return this;
	}

	public State receivedMessage(Node n, InsertRequest ir) {
		super.receivedRequest(n, ir);
		return this;
	}

	private State checkTransition(Node n) throws StateTransition {
	    if(iAWSDSlave) return this;
		if (dataReceived == null || dataSent == null) {
			return this;
		}
		if (dataReceived.getCB() == Presentation.CB_OK) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"CB_OK: successfully sent insert: " + this,
					Logger.DEBUG);
			// Don't commit at this stage
			// Because it will cause a collision if we have to restart
			//tryCommit(n);
			if (logDEBUG)
				Core.logger.log(
					this,
					"Going to AWSD for " + this,
					new Exception("debug"),
					Logger.DEBUG);
			super.cancelRestart(); // NoStoreData will cause restart now
			State awsd;
			if (storeData == null) {
			    // They may still be routing as we haven't necessarily received InsertReply.
			    // They may still be transferring too...
				NoStoreData nosd = new NoStoreData(this);
				long timeout = storeDataTime();
				if(logDEBUG)
				    Core.logger.log(this, "noSD = "+nosd+" in "+timeout+"ms for "+this, Logger.DEBUG);
				if(origPeer == null) try {
                    ft.restarted(n, timeout, null, "TransferInsert -> IAWSD");
                } catch (CommunicationException e) {
                }
				n.schedule(timeout, nosd);
				awsd = new InsertAwaitingStoreData(this, nosd);
			} else {
				mq.addElement(storeData);
				awsd = new InsertAwaitingStoreData(this, null);
			}
			return transition(awsd, true);
		} else {
	        terminateRouting(false, false, false); // problem with sender

		    if (logDEBUG)
				Core.logger.log(
					this,
					"dataReceived control bytes not CB_OK, "
						+ "going to RequestDone for "
						+ this,
					Logger.DEBUG);
			//receivingData.cancel();
			// 	    logFailedTransfer(n);
			// Routing dealt with in received(DataReceived)
			return transition(new RequestDone(this), false);
		}
	}

    protected long storeDataTime() {
        // Doesn't matter whether it's approved or not, either way the data
        // must be transferred downstream
		return Core.storeDataTime(hopsToLive + RequestState.TIMEOUT_EXTRA_HTL, 
		        searchKey.getExpectedDataLength(), remoteQueueTimeout());
    }

//	private void tryCommit(Node n) throws StateTransition {
//		try {
//			receivingData.commit(); // make the key available
//		} catch (KeyCollisionException e) {
//			// this is a little bit of a hack.  we jump into a
//			// DataPending state and then handle a restart which
//			// makes us check for the data in the store again
//			Core.logger.log(
//				this,
//				"Going to DataPending after key collision for " + this,
//				Logger.MINOR);
//			scheduleRestart(n, 0);
//			// 	    logSuccess(n);
//			transition(new DataPending(this), false);
//		} catch (IOException e) {
//			fail(n, "Cache failed");
//			Core.logger.log(
//				this,
//				"Cache failed on commit for " + this,
//				e,
//				Logger.ERROR);
//			terminateRouting(false, false, false); // our fault
//			transition(new RequestDone(this), false);
//		}
//	}
//
	public State receivedMessage(Node n, DataReceived dr)
		throws StateException {
		// Routing does not care
		if (receivingData != dr.source()) {
			throw new BadStateException(
				"Not my DataReceived: " + dr + " for " + this);
		}
		if (logDEBUG)
			Core.logger.log(this, "Got " + dr + " for " + this, Logger.DEBUG);
		dataReceived = dr;
		int cb = dr.getCB();
		switch (cb) {

			case Presentation.CB_OK :
				Core.logger.log(
					this,
					"Data received successfully! for " + this,
					Logger.MINOR);

				break;

			case Presentation.CB_CACHE_FAILED :
				Core.logger.log(
					this,
					"Cache failed while receiving data! for " + this,
					Logger.ERROR);
				fail(n, "Cache failed");
				if(dataSent == null)
				    sendingData.abort(Presentation.CB_ABORTED, false);
				Core.diagnostics.occurrenceCounting("sendFailedCacheFailed", 1);
				break;

			case Presentation.CB_BAD_DATA :
				fail(n, "You sent bad data! for " + this);
				Core.logger.log(
					this,
					"TransferInsert source sent bad data! Report if occurs often. Detail:"
						+ this
						+ ", "
						+ dr,
					Logger.NORMAL);
				Core.diagnostics.occurrenceCounting("sendFailedCorruptData", 1);
			case Presentation.CB_ABORTED :
				if (cb == Presentation.CB_ABORTED) {
					Core.logger.log(
						this,
						"TransferInsert source sent CB_ABORTED :( ("
							+ this
							+ ", "
							+ dr
							+ ")",
						Logger.MINOR);
					Core.diagnostics.occurrenceCounting(
						"sendFailedSourceAborted",
						1);
				}
			case Presentation.CB_RECV_CONN_DIED :
				if (cb == Presentation.CB_RECV_CONN_DIED) {
					Core.logger.log(
						this,
						"TransferInsert source connection died! Report if occurs often. Detail:"
							+ this
							+ ", "
							+ dr,
						Logger.NORMAL);
					Core.diagnostics.occurrenceCounting(
						"recvConnDiedInTransfer",
						1);
				}
				if(dataSent == null)
				    sendingData.abort(Presentation.CB_ABORTED, false);
				break;

			case Presentation.CB_RESTARTED :
				Core.logger.log(
					this,
					"Insert source sent CB_RESTARTED ! on " + this,
					Logger.NORMAL);
				Core.diagnostics.occurrenceCounting(
					"sendFailedSourceRestarted",
					1);
				if(dataSent == null)
				    sendingData.abort(Presentation.CB_ABORTED, false);
				break;

			default :
				Core.logger.log(
					this,
					"Insert received unrecognized failure code "
						+ Presentation.getCBname(cb)
						+ " on "
						+ this,
					Logger.NORMAL);
				Core.diagnostics.occurrenceCounting("sendFailedUnknownCB", 1);
				if(dataSent == null)
				    sendingData.abort(Presentation.CB_ABORTED, false);
				fail(n, "You sent " + Presentation.getCBdescription(cb));
				Core.logger.log(
					this,
					"Failed to receive insert data with CB "
						+ Presentation.getCBdescription(cb)
						+ ", for "
						+ this,
					Logger.MINOR);
		}
		return checkTransition(n);
		// If we are finished, transition to AWSD or RD as appropriate
		// If not, wait
	}

	public State receivedMessage(Node n, DataSent ds) throws StateException {
		if (sendingData != ds.source()) {
			throw new BadStateException("Not my DataSent: " + ds);
		}
		if (logDEBUG)
			Core.logger.log(this, "Got " + ds + " for " + this + ": "+sendingData, 
			        Logger.DEBUG);
		endTransferTime = System.currentTimeMillis();
		dataSent = ds;
		int cb = ds.getCB();
		switch (cb) {

			case Presentation.CB_OK :
				Core.logger.log(
					this,
					"Insert transferred successfully! for " + this,
					Logger.MINOR);
				//terminateRouting(true, true, false);
				// FIXME: should we terminateRouting() here? I think we should do that when we get the StoreData...
				
				// The search time is useless because it's a two stage process for inserts, and
				// Accepted can be sent with no effort.
				// The transfer rate is useless because it's probably bound by the source node.
				// FIXME: change the whole insert process to make it accountable to NGRouting.
				// Suggestion: include pull-through in the node level, only credit the node if
				// we can pull it through another node.
				break;
				// receivingData will be caught by checkTransition

			case Presentation.CB_CACHE_FAILED :
				Core.logger.log(
					this,
					"Transfer of insert failed, cache broken! for " + this,
					Logger.ERROR);
				terminateRouting(false, false, false);
				queryAborted(n);
				break;

			case Presentation.CB_SEND_CONN_DIED :
				Core.logger.log(
					this,
					"Send died while transferring insert: CB "
						+ Presentation.getCBdescription(cb)
						+ ", for "
						+ this,
					Logger.MINOR);
				Core.diagnostics.occurrenceCounting(
					"sendConnDiedInTransfer",
					1);
				scheduleRestart(n, 0);
				routes.transferFailed(
					insertReplyTime - routedTime,
					hopsToLive,
					sendingData.length(),
					endTransferTime - insertReplyTime);
				transition(new TransferInsertPending(this), false);
				break; // nop

			case Presentation.CB_RECV_CONN_DIED:
			    Core.logger.log(this, "Transferring insert failed: receive connection died: "+
			            this, Logger.NORMAL);
				// terminal
				terminateRouting(false, false, false);
				break;
				
			case Presentation.CB_ABORTED :
			case Presentation.CB_RESTARTED :
				Core.logger.log(
					this,
					"Send aborted: "
						+ Presentation.getCBdescription(cb)
						+ ", for "
						+ this,
					Logger.MINOR);
				terminateRouting(false, false, false);
				queryAborted(n);
				// Not routed-to node's fault
				break;

			default :
				Core.logger.log(
					this,
					"Failed to send insert data with CB "
						+ Presentation.getCBdescription(cb)
						+ ", for"
						+ this,
					Logger.NORMAL);
				// Precautionary principle
				if(routes != null)
				    routes.transferFailed(
				            insertReplyTime - routedTime,
				            hopsToLive,
				            sendingData.length(),
				            endTransferTime - insertReplyTime);
				else
				    Core.logger.log(this, "routes NULL!",
				            new Exception("debug"), Logger.DEBUG);
				queryAborted(n);
				break;
		}

		return checkTransition(n);
	}
	
    /**
     * @return the state we will return from endRoute.
     */
    protected State endRouteState(Node n) {
        // We have DIM and we are transferring data.
        return new ReceivingInsert(this);
    }
    
    protected long endRouteTimeout() {
        return receiveInsertTimeout();
    }

	protected void finalize() {
		long i;
		if(logDEBUG) {
			StringBuffer sb = new StringBuffer();
			synchronized(instancesLock) {
				instances--;
				i = instances;
				transferInsertsRunning.remove(myInitialToString);
				for(Iterator it=transferInsertsRunning.iterator();it.hasNext();) {
					sb.append(it.next().toString());
					sb.append('\n');
				}
				sb.append("END OF TRANSFER INSERT DUMP\n");
			}
			Core.logger.log(this, "Destroying "+this+": instances now "+
					i+": details:\n"+sb.toString(), Logger.DEBUG);
		}
	}
}
