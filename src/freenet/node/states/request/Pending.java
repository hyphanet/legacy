package freenet.node.states.request;

import java.io.IOException;

import freenet.CommunicationException;
import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.Message;
import freenet.MessageObject;
import freenet.Presentation;
import freenet.SendFailedException;
import freenet.Storables;
import freenet.TrailerWriter;
import freenet.fs.dir.BufferException;
import freenet.message.Accepted;
import freenet.message.DataReply;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.Request;
import freenet.message.StoreData;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.ds.KeyCollisionException;
import freenet.node.ds.KeyInputStream;
import freenet.node.ds.StoreIOException;
import freenet.node.states.data.DataReceived;
import freenet.node.states.data.DataSent;
import freenet.node.states.data.ReceiveData;
import freenet.node.states.data.SendData;
import freenet.support.Logger;
import freenet.support.io.DataNotValidIOException;

/**
 * This is the abstract superclass for states pertaining to Insert and
 * Data Requests that are being processed.
 */
public abstract class Pending extends RequestState {

	/** the auxiliary state that runs the data receive */
	ReceiveData receivingData;

	/** the auxiliary state that runs the data send */
	SendData sendingData;

	/** may need to cache it here */
	DataReceived dataReceived;

	/** may need to cache it here */
	DataSent dataSent;

	/** may need to cache it here */
	StoreData storeData;

	/** got Accepted yet? */
	boolean accepted = false;

	final boolean routeToNewestNodes;

	boolean ignoreDS = false;

	/** Time marked when routing so that we can register time taken for NGrouting. DO NOT RESET ON QUERYRESTARTED, as it is part of the node routing metric. */
	volatile long routedTime = -1;

	/** Time marked when Accepted got. Not sure why we need this but routedTime used to be the same thing */
	volatile long acceptedTime = -1;

	/** Marked which messages that cause a routing, so that we can see how 
	    long until the message is actually routed */
	volatile long receivedTime = -1;

	/** Marked when we got the DataReply or InsertReply - AFTER the Storables verify */
	volatile long replyTime = -1;

	/** Time the message entered Pending
	 */
	volatile long gotTime = -1;

	volatile long gotRouteTime = -1;

	volatile long searchDataRoutingTime = -1;

	SendFinished feedbackSender = null;
	SendFinished outwardSender = null;
	Identity outwardIdentityLastUsed = null;

	// date --utc --date '1970-01-01 UTC 978307199 seconds' '+%F %H:%M:%S UTC'
	// 2000-12-31 23:59:59 UTC
	// date --utc --date '2000-12-31 23:59:59 UTC' +%s
	// 978307199
	private static final long last20thCenturyMillisecond = 978307199999L;

	public String toString() {
		return super.toString()
			+ ", routedTime="
			+ routedTime
			+ ", replyTime="
			+ replyTime
			+ ", outwardSender="
			+ outwardSender;
	}

	/** Pending states may be created from scratch.
	 * @param routeToNewestNodes if true, rather than routing the key as normal,
	 * route to nodes in reverse order of experience. Used to ensure that new nodes get
	 * queries.
	 */
	Pending(
		long id,
		int htl,
		Key key,
		Identity orig,
		FeedbackToken ft,
		RequestInitiator ri,
		boolean routeToNewestNodes,
		boolean ignoreDS) {
		super(id, htl, key, orig, ft, ri);
		this.routeToNewestNodes = routeToNewestNodes;
		this.ignoreDS = ignoreDS;

		if (logDEBUG)
			Core.logger.log(
				this,
				"Created new Pending from scratch: " + this +
				", routeToNewestNodes="+routeToNewestNodes,
				new Exception("debug"), Logger.DEBUG);
	}

	/** We don't retain the state variables above
	  * in a transition back to Pending (a restart).
	  * Except for routedTime. We need that for the
	  * ngrouting stats.
	  */
	Pending(RequestState ancestor) {
		super(ancestor);
		if (ancestor instanceof Pending) {
			Pending p = (Pending) ancestor;
			routedTime = p.routedTime;
			replyTime = p.replyTime;
			feedbackSender = p.feedbackSender;
			outwardSender = p.outwardSender;
			outwardIdentityLastUsed = p.outwardIdentityLastUsed;
			routeToNewestNodes = p.routeToNewestNodes;
			ignoreDS = p.ignoreDS;
		} else {
			routeToNewestNodes = false;
			ignoreDS = false;
		}
		if (logDEBUG)
			Core.logger.log(
				this,
				"Created new Pending: " + this +" from " + ancestor,
				new Exception("debug"),
				Logger.DEBUG);
	}

	/** State implementation.
	  */
	public void lost(Node n) {
		Core.diagnostics.occurrenceCounting("lostRequestState", 1);
		Core.logger.log(
			this,
			"Lost a Pending state " + this +" !",
			new Exception("debug"),
			Logger.NORMAL);
		terminateRouting(false, false, false);
		fail(n, "Request state lost due to overflow for " + this);
	}

	/** @return  A request Message that could be routed upstream
	  *          (note that unrecognized fields are lost)
	  */
	abstract Request createRequest(FieldSet otherFields, Identity identity);

	//=== message handling =====================================================

	void receivedStoreData(Node n, StoreData sd) throws BadStateException {
		if (!shouldAcceptFromRoutee(sd)) return;
		// just hold on to it, when the Pending state is ready to finish, it
		// can check for it and go to AwaitingStoreData if it's still null
		storeData = sd;
	}

	/** Return false if we should not accept a response from
	 * a given identity. Will also decide what to log and whether
	 * to throw. If it returns false, caller should probably return.
	 */
	protected boolean shouldAcceptFromRoutee(Message m) throws BadStateException {
		Identity i = m.peerIdentity();
		if (!fromLastPeer(i)) {
			if(routes == null || !routes.haveRoutedTo(i))
				throw new BadStateException(m+" from the wrong peer!");
			else {
				Core.logger.log(this, "Got "+m+" from previous route "+i+" should be "+lastPeer,
						Logger.MINOR);
				return false;
			}
		}
		return true;
	}
	
	void receivedQueryRestarted(Node n, QueryRestarted qr)
		throws BadStateException, RequestAbortException {
		if(!shouldAcceptFromRoutee(qr)) return;
		acceptedTime = -1; // this time is no longer meaningful
		receivedTime = -2;

		cancelRestart();
		long timeout = origHopTimeHTL(remoteQueueTimeout());
		relayRestarted(n, timeout, true, "Relaying in "+getClass().getName());
		scheduleRestart(n, timeout);
	}

	/**
	 * Note the EndOfRouteException and RequestAbortException.
	 * Must be called by subclass as part of the implementation
	 * for receivedMessage(Node n, QueryRejected qr)
	 */
	void receivedQueryRejected(Node n, QueryRejected qr)
		throws BadStateException, RequestAbortException, EndOfRouteException {
		if(!shouldAcceptFromRoutee(qr)) return;

		if (logDEBUG)
			Core.logger.log(this, "Got " + qr + " on " + this, Logger.DEBUG);
		relayRestarted(n, hopTimeHTL(hopsToLive, remoteQueueTimeout()), true, "QR: "+qr.getReason()+" in "+getClass().getName());
		gotTime = System.currentTimeMillis();
		if (receivedTime >= -1) {
			receivedTime = qr.getReceivedTime(); // measure routing time again
			if (logDEBUG)
				Core.logger.log(
					this,
					"Remeasured receivedTime: " + receivedTime + " for " + this,
					Logger.DEBUG);
		}
		if (receivedTime <= last20thCenturyMillisecond) {
			if (receivedTime > -1)
				Core.logger.log(
					this,
					"qr.getReceivedTime() returned a 20th century time "
						+ receivedTime
						+ ") for "
						+ this,
					Logger.NORMAL);
			receivedTime = 0;
		} else {
			if (logDEBUG)
				Core.logger.log(
					this,
					"QueryRejected gotTime-recievedTime: "
						+ (gotTime - receivedTime)
						+ " for "
						+ this,
					new Exception("debug"),
					Logger.DEBUG);
		}

		// 	logFailure(n, gotTime);

		++rejected;

		if (accepted)
			hopsToLive = Math.min(hopsToLive - 1, qr.getHopsToLive());
		else
			hopsToLive--;

		if (logDEBUG)
			Core.logger.log(
				this,
				"Rejected count: "
					+ rejected
					+ ", restarted: "
					+ restarted
					+ ", unreachable: "
					+ unreachable
					+ ", current hopsToLive: "
					+ hopsToLive
					+ " for "
					+ this,
				Logger.DEBUG);

		if (routes == null) {
			Core.logger.log(
				this,
				"Already terminated in " + this +".receivedQueryRejected!",
				Logger.NORMAL);
		} else {
			routes.queryRejected(qr.getAttenuation(), accepted);
		}
		long toldRTTime = System.currentTimeMillis();
		if (logDEBUG)
			Core.logger.log(
				this,
				"Time to inform RT: " + toldRTTime + " for " + this,
				Logger.DEBUG);

		// FIXME - do we want to do a QueryRestarted if the sanity check fails?

		cancelRestart();
		searchData(n); // check for data in case of
		// a parallel request succeeding
		long preGotRouteTime = System.currentTimeMillis();
		if (logDEBUG)
			Core.logger.log(
				this,
				"Time to cancel restart and search data "
					+ "(unsuccessfully): "
					+ (preGotRouteTime - toldRTTime)
					+ " for "
					+ this,
				Logger.DEBUG);
		Request newReq = createRequest(qr.otherFields, n.identity);
		gotRouteTime = System.currentTimeMillis();
		if (logDEBUG)
			Core.logger.log(
				this,
				"Got request in "
					+ (gotRouteTime - preGotRouteTime)
					+ " for "
					+ this,
				Logger.DEBUG);
		sendOn(n, newReq, false);
	}

	/**
	 * Note the EndOfRouteException and RequestAbortException.
	 * Must be called by subclass as part of the implementation
	 * for receivedMessage(Node n, *RequestInitiator ri)
	 */
	void receivedRequestInitiator(Node n, RequestInitiator ri)
		throws BadStateException, RequestAbortException, EndOfRouteException {
		// it must be my own RequestInitiator
		if (this.ri == null || this.ri != ri) {
			throw new BadStateException("Not my request initiator: " + ri);
		}

		gotTime = System.currentTimeMillis();
		// 	logFailure(n, gotTime);

		if (routedTime > 0) {
			if (routes == null)
				Core.logger.log(
					this,
					"routes NULL in " + this +".receivedRequestInitiator",
					new Exception("debug"),
					Logger.ERROR);
			else {
				if (accepted)
					routes.searchFailed();
				else
					routes.earlyTimeout();
			}
		}
		// FIXME: does this mean anything?
		if (receivedTime >= -1) {
			receivedTime = ri.startTime();
			if(logDEBUG)
			    Core.logger.log(this, "Setting receivedTime to ri.startTime: "+
			            receivedTime+" from "+ri+" on "+this, ri.initException, Logger.DEBUG);
			// message receive time or the 
			// scheduled restart time.
		}
		if (receivedTime > 0 && 
		        receivedTime <= last20thCenturyMillisecond) {
				Core.logger.log(
					this,
					"ri.startTime() returned a 20th century time: " 
					+ receivedTime + " on " + this, Logger.NORMAL);
			receivedTime = 0;
		} else {
			long x = gotTime - receivedTime;
			Core.diagnostics.occurrenceContinuous("preRoutingTime", x);
			if (x > 500 && logDEBUG)
				Core.logger.log(
					this,
					"Got long preRoutingTime ("
						+ x
						+ " = "
						+ gotTime
						+ "-"
						+ receivedTime
						+ ") for "
						+ this,
					new Exception("debug"),
					Logger.DEBUG);
		}
		long regotTime = System.currentTimeMillis();
		Core.diagnostics.occurrenceContinuous("regotTime", regotTime - gotTime);
		// is this the first time?
		if (routes == null) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Starting Pending chain for " + this,
					Logger.DEBUG);
			// check for data in cache
			searchData(n);
			long searchDataTime = System.currentTimeMillis();
			searchDataRoutingTime = searchDataTime - regotTime;
			if (searchDataTime - regotTime > 1000)
				if (logMINOR)
					Core.logger.log(
						this,
						"searchDataRoutingTime took "
							+ (searchDataTime - regotTime)
							+ "ms on "
							+ this
							+ "!",
						Logger.MINOR);
			if (logDEBUG)
				Core.logger.log(
					this,
					"About to route() at "
						+ System.currentTimeMillis()
						+ " for "
						+ this,
					Logger.DEBUG);
			routes =
				n.rt.route(
					searchKey,
					hopsToLive,
					searchKey.getExpectedTransmissionLength(),
					isInsert(),
					false, // not announcement
					routeToNewestNodes,
					origPeer == null,
					true); // will send requests
			gotRouteTime = System.currentTimeMillis();
			Core.diagnostics.occurrenceContinuous(
				"getRouteTime",
				gotRouteTime - searchDataTime);
			if ((gotRouteTime - searchDataTime) > 5000 &&
			       (gotRouteTime - Core.beganTime) > 600*1000 &&
			       n.estimatedLoad(true) < 1.0) {
			    /** LOGGING HEURISTIC:
			     * Definitely got a problem if above satisfied!
			     * If we've only been up for 10 minutes or less, then
			     * this is probably only due to the startup CPU spike.
			     * Which is partly caused by us accepting too many
			     * requests and rate limiting needing time to compensate.
			     * If load is over 1.0 then we may simply be really
			     * overloaded. Rate limiting will compensate soon.
			     * If we have low load and high uptime, then we have
			     * a MAJOR problem.
			     */
				Core.logger.log(
					this,
					"getting routing object took "
						+ (gotRouteTime - searchDataTime)
						+ " for "
						+ this,
					Logger.NORMAL);
			}
			if (logDEBUG)
				Core.logger.log(
					this,
					"Routing: " + routes + " for " + this,
					Logger.DEBUG);
		} else {
			++restarted;

			if (logDEBUG) {
				Core.logger.log(
					this,
					"Restarting Pending chain " + this,
					new Exception("debug"),
					Logger.DEBUG);
				n.ticker().getMessageHandler().printChainInfo(
					id,
					Core.logStream);
				Core.logger.log(
					this,
					"Restarting Pending chain " + this +" his was:",
					ri.initException,
					Logger.DEBUG);
			}

			Core.diagnostics.occurrenceBinomial(
				"restartedRequestAccepted",
				1,
				accepted ? 1 : 0);

			// Don't allow indefinite restarts.
			//
			// Note:
			// sendOn will throw an EndOfRouteException below
			// if hopsToLive hits 0.

			if (--hopsToLive > 0) {
				// send QueryRestarted to initiating chain
			    // Timeout is based on downstream so can be from current HTL
				relayRestarted(n, hopTimeHTL(hopsToLive, remoteQueueTimeout()), true, "timeout in "+getClass().getName());
				// +1 for Accepted
			}

			receivedTime = -2;

			// check for data in cache in case of a parallel request
			searchData(n);
			gotRouteTime = System.currentTimeMillis();
		}

		// note the null; unknown fields are not restored after 
		// restarts since they are not saved. This means that 
		// Requests should actually not carry unknown fields... 
		sendOn(n, createRequest(null, n.identity), true);
	}

	/**
	 * Because method selection on superclasses doesn't work,
	 * implementation must call this from a method with the actual
	 * message (ie, receivedMessage(Node n, DataRequest dr)
	 */
	void receivedRequest(Node n, Request r) {
		// This is a loop, no real problem here.
		if (logDEBUG)
			Core.logger.log(this, "Backtracking", Logger.DEBUG);
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

	/** @returns true if we handled the message, false if it was not from
	 * the current node.
	 */ 
	boolean receivedAccepted(Node n, Accepted a) throws BadStateException {
		if (!shouldAcceptFromRoutee(a)) return false;
		if (!accepted) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Got Accepted in Pending, chain " + this,
					new Exception("debug"),
					Logger.DEBUG);
			// Wait for DataReply
			scheduleRestart(n, hopTimeHTL());
			acceptedTime = System.currentTimeMillis();
			accepted = true;
			routes.routeAccepted();
			if (outwardSender != null) {
				if (logDEBUG)
					Core
						.logger
						.log(
							this,
							"Got Accepted before got SendFinished "
								+ outwardSender
								+ " on "
								+ this,
							Logger.DEBUG /* FIXME */
					);
				outwardSender.mustHaveSucceeded();
				outwardSender.cancel();
				outwardSender = null;
			}
		}
		return true;
	}

    State receivedDataReply(Node n, DataReply dr) throws BadStateException {
		if (!fromLastPeer(dr)) {
			if (routes != null && routes.haveRoutedTo(dr.peerIdentity())) {
				ReceiveData rd;
				try {
					rd = dr.cacheData(n, this.searchKey, false);
					rd.schedule(n);
				} catch (KeyCollisionException e1) {
					// No problem
					Core.logger.log(this, "Got late DataReply, data was already in store", 
							Logger.MINOR);
					dr.drop(n);
					return this;
				} catch (DataNotValidIOException e1) {
					// Hmmm
					Core.logger.log(this, "Caught "+e1+" trying to cache DataReply "+dr,
							Logger.NORMAL);
					dr.drop(n);
					return this;
				} catch (IOException e1) {
					Core.logger.log(this, "Caught "+e1+" trying to cache late DataReply "+dr,
							Logger.NORMAL);
					dr.drop(n);
					return this;
				}
				rd.setSilent();
				return this;
			} else {
			    dr.drop(n);
				throw new BadStateException("DataReply from wrong peer!");
		}
		}
		if (logDEBUG) {
			Core.logger.log(
				this,
				"Got DataReply(" + dr + ") for " + this,
				Logger.DEBUG);
			Core.logger.log(
				this,
				"Pending receivedDataReply for " + this,
				new Exception("debug"),
				Logger.DEBUG);
		}

		accepted = true; // more or less..

		if (acceptedTime <= 0)
			routes.routeAccepted();
		cancelRestart();

		try {
			receivingData = dr.cacheData(n, searchKey, ignoreDS);
			replyTime = System.currentTimeMillis();
			// replyTime must be set AFTER verifying Storables
			n.ft.remove(searchKey); // remove if in FT.
		} catch (KeyCollisionException e) {
			// oh well, try to go to SendingReply
			if (logDEBUG)
				Core.logger.log(
					this,
					"Got KeyCollisionException on " + this,
					Logger.DEBUG);
			replyTime = -4;
			dr.drop(n);
			try {
				searchData(n);
			} catch (RequestAbortException rae) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"RAE in got KCE on " + this,
						rae,
						Logger.DEBUG);
				return rae.state;
			}
			// damn, already gone
			fail(n, "I/O error replying with data");
			Core.logger.log(
				this,
				"Failed to find data after key collision on "
					+ this
					+ " while caching DataReply",
				Logger.NORMAL);
			terminateRouting(false, false, false);
			// cache failure? anyway, node not routing
			return new RequestDone(this);
		} catch (StoreIOException e) {
			// Our fault
			terminateRouting(false, false, false);
			dr.drop(n);
			Core.logger.log(
				this,
				"I/O error storing DataReply: " + e + " (" + this +")",
				e,
				Logger.ERROR);
			fail(n, "I/O error storing DataReply");
			return new RequestDone(this);
		} catch (IOException e) {
			// Their fault
			dr.drop(n);
			routes.verityFailed();
			// The transfer has not started yet
			if (e instanceof DataNotValidIOException) {
				DataNotValidIOException de = (DataNotValidIOException) e;
				Core.logger.log(
					this,
					"Got DNV: "
						+ Presentation.getCBdescription(de.getCode())
						+ " for "
						+ this,
					Logger.MINOR);
			}
			scheduleRestart(n, 0); // schedule restart now
			replyTime = -3;
			return this; // back to pending
		}

		try {
			KeyInputStream kin = receivingData.getKeyInputStream();
			kin.setParent(
				id,
				n.ticker().getMessageHandler(),
				"Transfering reply.");

			boolean worked = false;
			try {
				sendingData = sendData(n, kin);
			    sendingData.schedule(n);
			    worked = true;
			} finally {
				if (!worked)
					kin.close();
			}
		} catch (IOException e) {
			// couldn't get the KeyInputStream
			fail(n, "I/O error replying with data");
			if (e instanceof BufferException) {
				Core.logger.log(
					this,
					"Failed to get KeyInputStream: " + e + " for " + this,
					Logger.NORMAL);
			} else {
				Core.logger.log(
					this,
					"I/O error getting KeyInputStream for " + this,
					e,
					Logger.ERROR);
			}
			// Our fault, or upstream's fault
			// Routing is still running and valid
			return new ReceivingReply(this);
		} catch (CommunicationException e) {
			// Our fault, or upstream's fault
			// So don't tell the Routing
			Core.logger.log(
				this,
				"Error replying to peer: " + e + " for " + this,
				Logger.MINOR);
			return new ReceivingReply(this);
		} finally {
			receivingData.schedule(n);
		}

		return new TransferReply(this);
	}

	State receivedQueueSendFinished(Node n, QueueSendFinished qsf) {
	    outwardIdentityLastUsed = qsf.nodeSentTo;
	    lastPeer = qsf.nodeSentTo;
	    // Schedule sendfinished timeout in 1 hop time
	    routedTime = System.currentTimeMillis();
	    n.schedule(Core.hopTime(1,0), outwardSender = (SendFinished) (qsf.cb));
	    return this;
	}

	State receivedQueueSendFailed(Node n, QueueSendFailed qsf) throws RequestAbortException {
	    // :(
	    if(logMINOR) Core.logger.log(this, "Failed to send queued request: "+qsf+" on "+this,
	            Logger.MINOR);
	    this.terminateRouting(false, true, true);
		if (logDEBUG)
			Core.logger.log(
				this,
				"Ran out of routes for "
					+ this
					+ " after queueing",
				Logger.DEBUG);
		fail(n, "No route found", qsf.r.otherFields);
		if (logDEBUG)
			Core.logger.log(
				this,
				"rt exhaused for " + this,
				Logger.DEBUG);
		terminateRouting(false, origHopsToLive > 0, true);
		throw new RequestAbortException(new RequestDone(this));
	}
	
	//=== support methods ======================================================

	private void sendOn(Node n, Request r, boolean isFirst)
		throws EndOfRouteException {

		loggedResult = false;

		accepted = false; // so we can check it later

		if (hopsToLive <= 0) {
			terminateRouting(false, origHopsToLive != 0, true);
			throw new EndOfRouteException("Reached last node in route");
		}

		// FIXME: is this necessary?
		if (routeToNewestNodes && routedTime > 0) {
			terminateRouting(false, false, false);
			throw new EndOfRouteException("Only routing to one node as requested");
		}

		// Now queue the request with the QueueManager
		// First calculate the appropriate timeout
		long timeout = queueTimeout();
		outwardSender = new SendFinished(n, id, r.toString());
		lastPeer = null;
		n.queueManager.queue(id, searchKey, routes, timeout, outwardSender, r, (int)Core.hopTime(1,0), origPeer);
	}

	/**
     * @return the queue timeout for this request
     */
    protected int queueTimeout() {
	    return Core.queueTimeout((int)searchKey.getExpectedDataLength(), isInsert(), origPeer == null && routedTime == -1);
    }
    
    /**
     * Queue timeout, assuming the request is nonlocal.
     */
    protected int remoteQueueTimeout() {
        return Core.queueTimeout((int)searchKey.getExpectedDataLength(), isInsert(), false);
    }

    long hopTimeHTL() {
        return hopTimeHTL(hopsToLive, remoteQueueTimeout());
    }

    public boolean canRunFast(Node n, MessageObject mo) {
		if (mo instanceof SendFinished) {
			SendFinished sf = (SendFinished) mo;
			if (sf == feedbackSender)
				return true;
			if (sf == outwardSender) {
				if (sf.getSuccess())
					return true;
				else
					return false;
			}
			return true;
		}
		return false;
	}

	public void receivedSendFinished(Node n, SendFinished sf)
		throws RequestAbortException, EndOfRouteException {
		long enteredTime = System.currentTimeMillis();
		// If not finished, timed out
		if (sf == feedbackSender) {
			if (logDEBUG)
				Core
					.logger
					.log(
						this,
						"Got a feedback SendFinished " + sf + " for " + this,
						Logger.DEBUG /* FIXME! */
				);
			feedbackSender = null;
			// Our feedback sender
			// If success, cool
			// If failure, throw RAE to RequestDone
			if (!sf.getSuccess()) {
				Exception e = sf.failCause();
				if (e instanceof CommunicationException) {
					Core.logger.log(
						this,
						"Couldn't send QueryRestarted back to "
							+ ft
							+ ":"
							+ origPeer
							+ ", killing request ("
							+ sf
							+ ")",
						Logger.MINOR);
				} else {
					if (Core.logger.shouldLog(Logger.MINOR, this))
						Core.logger.log(
							this,
							"Got strange Exception "
								+ e
								+ " sending "
								+ "QueryRestarted to "
								+ ft
								+ ":"
								+ origPeer
								+ " ("
								+ sf
								+ ")",
							Logger.MINOR);
				}
				// Either way...
				terminateRouting(false, false, false);
				throw new RequestAbortException(new RequestDone(this));
			}
		} else if (sf == outwardSender) {
			if (logDEBUG)
				Core
					.logger
					.log(
						this,
						"Got outbound SendFinished " + sf + " for " + this,
						Logger.DEBUG /* FIXME! */
				);
			// Our DataRequest send
			outwardSender = null;
			Core.diagnostics.occurrenceBinomial("outboundAggregateRequests", 1, sf.getSuccess() ? 1 : 0);
			if (!sf.getSuccess()) {
				Exception e = sf.failCause();
				if (e == null) {
					n.unsendMessage(outwardIdentityLastUsed, sf);
					outwardIdentityLastUsed = null;
					// Timed out?
					routes.earlyTimeout();
					++restarted; // it did time out
				} else if (e instanceof CommunicationException) {
					++unreachable;
					// we restarted because of an actual I/O error
					// don't care if it's terminal or nonterminal
					// because routing is too time-critical

					// It's not an error in opening the connection
					// It's an error in sending the message
					CommunicationException ce = (CommunicationException) e;
					if (logDEBUG)
						Core.logger.log(
							this,
							"Routing ("
								+ this
								+ ") failure to: "
								+ ce.peerAddress()
								+ " -- "
								+ ce,
							ce,
							Logger.DEBUG);
					// Conn is dead
					routes.earlyTimeout();
				} else {
					Core.logger.log(
						this,
						"DataRequest send caught " + e,
						e,
						Logger.ERROR);
					// Don't inform routes, we have no idea what it is
				}
				// Either way, restart
				cancelRestart();
				searchData(n);
				// Copied from receivedQueryRejected
				long preGotRouteTime = System.currentTimeMillis();
				Request newReq = createRequest(null, n.identity);
				gotRouteTime = System.currentTimeMillis();
				if (logDEBUG)
					Core.logger.log(
						this,
						"Got request in "
							+ (gotRouteTime - preGotRouteTime)
							+ " for "
							+ this,
						Logger.DEBUG);
				sendOn(n, newReq, false);
			} else {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Success: " + sf + " on " + this,
						Logger.DEBUG);
				long selectedTime = System.currentTimeMillis();
				if (logDEBUG)
					Core.logger.log(
						this,
						"Selected branch time on "
							+ this
							+ ".receivedSendFinished("
							+ sf
							+ "): "
							+ (selectedTime - enteredTime),
						Logger.DEBUG);
				sf.cancel();
				long cancelledTime = System.currentTimeMillis();
				if (logDEBUG)
					Core.logger.log(
						this,
						"Cancelled SF on "
							+ this
							+ ".receivedSendFinished("
							+ sf
							+ "): "
							+ (cancelledTime - selectedTime),
						Logger.DEBUG);
				// If we are Accepted already, which is entirely possible, wait for DataReply
				// Otherwise wait 1 hop for Accepted
				long timeout =
					accepted ? hopTimeHTL() : Core.hopTime(1,0);
				if (accepted && logDEBUG)
					Core.logger.log(
						this,
						"Already accepted - waiting "
							+ timeout
							+ "ms for DataReply",
						Logger.DEBUG);
				scheduleRestart(n, timeout); // timeout to get Accepted
				long scheduledTime = System.currentTimeMillis();
				if (logDEBUG)
					Core.logger.log(
						this,
						"Rescheduled on "
							+ this
							+ ".receivedSendFinished("
							+ sf
							+ "): "
							+ (scheduledTime - cancelledTime),
						Logger.DEBUG);
			}
		} else {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Ignored "
						+ sf
						+ " - not recognized ("
						+ this
						+ ") - outwardSender = "
						+ outwardSender
						+ ", feedbackSender = "
						+ feedbackSender,
					Logger.DEBUG);
		}
	}

	final void relayRestarted(Node n, long timeout, boolean sendAsync, String reason)
		throws RequestAbortException {
		if (sendAsync)
			feedbackSender = new SendFinished(n, id, "Restarted");
		// We don't care if it takes forever, nothing is waiting for it
		try {
			ft.restarted(n, timeout, sendAsync ? feedbackSender : null, reason);
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
			if(outwardSender != null) outwardSender.cancel();
			cancelRestart();
			throw new RequestAbortException(new RequestDone(this));
		}
	}

	/** Given an existing KeyInputStream, sets up a SendData state to
	 * transfer the data back to the requester. 
	 */
	SendData sendData(Node n, KeyInputStream doc)
		throws CommunicationException {
		if (logDEBUG)
			Core.logger.log(
				this,
				"Sending data (," + doc + ") for " + this,
				Logger.DEBUG);
		Storables storables = doc.getStorables();
		TrailerWriter out = ft.dataFound(n, storables, doc.length());
		// null means the initiator is not interested in seeing the
		// data (e.g., KeyCollision response in FCP)
		
		// FIXME: Can't we just not create the SendData, and queue a DataSent? 
		
		if(out == null) throw new SendFailedException(null, "Recipient does not want data - dataFound returned null");
		
		SendData sd =
			new SendData(
				Core.getRandSource().nextLong(),
				this.id,
				out,
				doc,
				doc.length(),
				storables.getPartSize(),
				isInsert(),
				n);
		if (logDEBUG)
			Core.logger.log(
				this,
				"Got SendData(" + sd + ") for " + this,
				Logger.DEBUG);
		return sd;
	}

	/** Attempts to retrieve the key from the cache and transition to
	  * SendingReply.  Will transition to null in the event of an
	  * unrecoverable error.  Does nothing if the key is not found.
	  */
	void searchData(Node n) throws RequestAbortException {
		if (ignoreDS)
			return;
		if (logDEBUG)
			Core.logger.log(
				this,
				"searchData() on " + this,
				new Exception("debug"),
				Logger.DEBUG);
		long startTime = System.currentTimeMillis();
		long thrownTime = -1;
		KeyInputStream doc = null;
		try {
			try {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Trying to fetch " + this +" at " + startTime,
						new Exception("debug"),
						Logger.DEBUG);
				doc = n.ds.getData(searchKey);
				long gotDataTime = System.currentTimeMillis();
				if (logDEBUG)
					Core.logger.log(
						this,
						"getData took "
							+ (gotDataTime - startTime)
							+ " millis on "
							+ this,
						Logger.DEBUG);
				if (doc != null) {
					doc.setParent(
						id,
						n.ticker().getMessageHandler(),
						"Replying with data from store");
					sendingData = sendData(n, doc);
					terminateRouting(true, false, false);
					long sendingDataTime = System.currentTimeMillis();
					if (logDEBUG)
						Core.logger.log(
							this,
							"sendData() took "
								+ (sendingDataTime - gotDataTime)
								+ " millis on "
								+ this,
							Logger.DEBUG);
					sendingData.schedule(n);
					long scheduledTime = System.currentTimeMillis();
					if (logDEBUG)
						Core.logger.log(
							this,
							"schedule took "
								+ (scheduledTime - sendingDataTime)
								+ " millis on "
								+ this,
							Logger.DEBUG);
					doc = null;
					thrownTime = System.currentTimeMillis();
					if (origPeer != null)
						Core.diagnostics.occurrenceContinuous(
							"sendingReplyHTL",
							hopsToLive);
					throw new RequestAbortException(new SendingReply(this));
				}
			} catch (IOException e) {
				fail(n, "I/O error replying with data");
				Core.logger.log(
					this,
					"I/O error replying with data on " + this,
					e,
					Logger.MINOR);
				thrownTime = System.currentTimeMillis();
				terminateRouting(false, false, false);
				throw new RequestAbortException(new RequestDone(this));
			} catch (CommunicationException e) {
				Core.logger.log(
					this,
					"Error replying to peer: " + e + " on " + this,
					e,
					Logger.MINOR);
				thrownTime = System.currentTimeMillis();
				terminateRouting(false, false, false);
				throw new RequestAbortException(new RequestDone(this));
			} finally {
				if (doc != null) {
					try {
						doc.close();
					} catch (IOException e) {
						Core.logger.log(
							this,
							"Failed to close KeyInputStream after failing "
								+ "on "
								+ this,
							e,
							Logger.MINOR);
					}
				}
			}
		} catch (RequestAbortException e) {
			long endTime = System.currentTimeMillis();
			long length = endTime - startTime;
			Core.diagnostics.occurrenceContinuous(
				"searchFoundDataTime",
				length);
			throw e;
		}
		long endTime = System.currentTimeMillis();
		long length = endTime - startTime;
		Core.diagnostics.occurrenceContinuous("searchNotFoundDataTime", length);
	}

	// Helpers for NGrouting stats
	long endTransferTime = -1;
	boolean loggedResult = false;
}
