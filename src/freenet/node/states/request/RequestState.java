package freenet.node.states.request;

import freenet.CommunicationException;
import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.Message;
import freenet.MessageSendCallback;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.rt.Routing;
import freenet.node.rt.TerminableRouting;
import freenet.support.Logger;

/** This class is basically just a data struct with all the variables
  * needed to process a request chain, and some utility methods.
  * @author tavin
  */
public abstract class RequestState extends State {

	// these must be set in all constructors

	/** the hops to live remaining at this node */
	int hopsToLive;
	final int origHopsToLive;

	/** the key for this request chain */
	final Key searchKey;

	/** The Peer that routed the request to us (if applicable) */
	/** Null if and only if we originated the request */
	final Identity origPeer;

	/** the FeedbackToken used to communicate back to the initiator */
	final FeedbackToken ft;

	/** the RequestInitiator scheduled to start or restart the request */
	/** subclasses MUST NOT respond to RequestInitiators that are not == ri */
	RequestInitiator ri;

	// things that happen during processing

	/** The last identity this request was sent to */
	Identity lastPeer;

	/** The NodeReferences to route to for this key */
	Routing routes;

	boolean terminatedRouting = false;

	boolean logDEBUG;
	boolean logMINOR;

		int unreachable = 0, // send failures
		restarted = 0, // automatic restarts
	rejected = 0; // no. of QueryRejected
	/** Used for starting a new chain.
	  */
	RequestState(
		long id,
		int htl,
		Key key,
		Identity orig,
		FeedbackToken ft,
		RequestInitiator ri) {
		super(id);
		if(htl > Node.maxHopsToLive) htl = Node.maxHopsToLive;
		hopsToLive = htl;
		searchKey = key;
		if (key == null)
			throw new NullPointerException();
		origPeer = orig;
		this.ft = ft;
		this.ri = ri;
		this.origHopsToLive = htl;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		logMINOR = Core.logger.shouldLog(Logger.MINOR, this);
	}

	/** If one RequestState is derived from another, we must maintain
	  * all state variables.
	  */
	RequestState(RequestState ancestor) {
		super(ancestor.id());
		hopsToLive = ancestor.hopsToLive;
		searchKey = ancestor.searchKey;
		origPeer = ancestor.origPeer;
		this.ft = ancestor.ft;
		this.ri = ancestor.ri;
		lastPeer = ancestor.lastPeer;
		routes = ancestor.routes;
		unreachable = ancestor.unreachable;
		restarted = ancestor.restarted;
		rejected = ancestor.rejected;
		origHopsToLive = ancestor.origHopsToLive;
		terminatedRouting = ancestor.terminatedRouting;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	}

	/** @return  whether the message came from the original peer
	  *          (also returns true if origPeer and mo.peerIdentity() are null) 
	  */
	final boolean fromOrigPeer(Message mo) {
		return origPeer == null
			? mo.peerIdentity() == null
			: origPeer.equals(mo.peerIdentity());
	}

	/** @return  whether the message came from the last attempted peer
	  */
	final boolean fromLastPeer(Message mo) {
		if (logDEBUG)
			Core.logger.log(
				this,
				"Checking whether "
					+ mo
					+ " ("
					+ mo.peerIdentity()
					+ ") is from "
					+ lastPeer
					+ " (",
				Logger.DEBUG);
		return fromLastPeer(mo.peerIdentity());
	}

	final boolean fromLastPeer(Identity i) {
		return lastPeer != null && lastPeer.equals(i);
	}
	
	/** Schedules the RequestInitiator on the ticker.
	  * @param n       the node to schedule on
	  * @param millis  in how many millisseconds to restart
	  */
	final void scheduleRestart(Node n, long millis) {
		if (logDEBUG)
			Core.logger.log(
				this,
				"Rescheduling the restart to timeout in "
					+ millis
					+ " millis on chain "
					+ Long.toHexString(id),
				new Exception("debug"),
				Logger.DEBUG);
		if (ri != null)
			ri.cancel();
		if (logDEBUG)
			Core.logger.log(
				this,
				"Cancelled " + ri + " on " + this,
				Logger.DEBUG);
		ri = new RequestInitiator(this, System.currentTimeMillis() + millis);
		if (logDEBUG)
			Core.logger.log(
				this,
				"Created " + ri + " on " + this,
				Logger.DEBUG);
		n.schedule(millis, ri);
		if (logDEBUG)
			Core.logger.log(
				this,
				"Scheduled " + ri + " in " + millis + " on " + this,
				Logger.DEBUG);
	}

	/** Cancel the RequestInitiator that has been scheduled
	  * to restart this chain.
	  */
	final void cancelRestart() {
		if (ri != null) {
			ri.cancel();
			ri = null;
		}
	}

	final void fail(Node n) {
		fail(n, null, null);
	}

	final void fail(Node n, String reason) {
		fail(n, reason, null);
	}

	/**
	 * Send back a QueryRejected to the previous node. This eats 
	 * any SendFailedException that might happen.
	 * This also cancels the restart if possible.
	 */
	void fail(Node n, String reason, FieldSet otherFields) {
		if (logDEBUG)
			Core.logger.log(
				this,
				"failing: " + reason + " for " + this,
				Logger.DEBUG);
		cancelRestart();
		try {
			if (reason == null)
				reason = "(no reason given)";
			MessageSendCallback cb =
				new RequestSendCallback(
					"QueryRejected (" + reason + ")",
					n,
					this);
			ft.queryRejected(
				n,
				hopsToLive,
				reason,
				otherFields,
				unreachable,
				restarted,
				rejected,
				routes == null ? 0 : routes.countBackedOff(),
				cb);
		} catch (CommunicationException e) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"I couldn't even fail right :-(",
					e,
					Logger.DEBUG);
		}
	}

	public String toString() {
		return getClass().getName() 
			+ "@" 
			+ hashCode()
			+ ": key="
			+ searchKey
			+ ", hopsToLive="
			+ hopsToLive
			+ ", id="
			+ Long.toHexString(id)
			+ ", routes="
			+ routes
			+ ", ft="
			+ ft
			+ ", orig="
			+ origPeer
			+ ", last="
			+ lastPeer
			+ (wasInsert() ? "(onceInsert)" : "(neverInsert)");
	}

	public void terminateRouting(
		boolean success,
		boolean routingRelated,
		boolean endOfRoute) {
		if (!terminatedRouting) {
			terminatedRouting = true;
			if (routes != null) {
				routes.terminate(success, routingRelated, endOfRoute);
				// Don't null routes. We need it for the storedata.
				if (routingRelated && !endOfRoute) {
					Core.diagnostics.occurrenceContinuous(
						"finalHTL",
						hopsToLive);
				}
			} else {
				TerminableRouting.staticReallyTerminate(
					success,
					routingRelated,
					endOfRoute,
					origPeer == null,
					searchKey);
			}
			routes = null;
		}
	}
	
	protected abstract boolean isInsert();

    /**
     * Queue timeout, assuming the request is nonlocal.
     */
    protected int remoteQueueTimeout() {
        return Core.queueTimeout((int)searchKey.getExpectedDataLength(), isInsert(), false);
    }
    
    public static int remoteQueueTimeout(int size, boolean isInsert) {
        return Core.queueTimeout(size, isInsert, false);
    }

	/** The number of hops assumed at the end of the request.
	 * 95% of all requests will finish with 10 extra hops or less.
	 */ 
    public static final int TIMEOUT_EXTRA_HTL = 10;
    
	/**
     * @return the timeout for the current HTL
     */
    public static long hopTimeHTL(int htl, int queueTime) {
        // Add extra if needed
        if(htl == Node.maxHopsToLive) htl += RequestState.TIMEOUT_EXTRA_HTL;
        // Kludge to avoid having to decrease the timeout on a live network:
        else htl += 4;
        return Core.hopTime(htl, queueTime);
    }
    
    /**
     * @return timeout for the original HTL, used in some message
     * sends.
     */
    public long origHopTimeHTL(int queueTime) {
        return hopTimeHTL(origHopsToLive, queueTime);
    }

    /**
     * @return whether this request is or has ever been an
     * insert
     */
    abstract public boolean wasInsert();
}
