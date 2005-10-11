package freenet.node;

import freenet.Core;
import freenet.support.Logger;
import freenet.support.Schedulable;
import freenet.support.TickerToken;

/**
 * Interface of generic non-message events in the node.
 * 
 * @author oskar
 */
public abstract class EventMessageObject
	implements NodeMessageObject, Schedulable {

	private TickerToken tt;
	protected final long id; // chain id
	protected final boolean external; // is chain external?

	/**
	 * Create an EventMessageObject
	 * 
	 * @param id
	 *            the chain ID, 64 bit identifier for the chain
	 * @param external
	 *            whether this is an external chain - internal and external
	 *            chains are separate even if they have the same ID
	 */
	public EventMessageObject(long id, boolean external) {
		this.id = id;
		this.external = external;
	}

	public String toString() {
		StringBuffer buf = new StringBuffer(super.toString());
		buf.append('@').append(Long.toHexString(id)).append(',').append(
			external);
		return buf.toString();
	}

	public final long id() {
		return id;
	}

	public State getInitialState() throws BadStateException {
		throw new BadStateException(
			"Internal event object received "
				+ "with no states. Chain probably ended.");
	}

	public final boolean isExternal() {
		return external;
	}

	/**
	 * The Ticker calls this to give the token for cancelling. (it's kind of a
	 * "setToken")
	 */
	public final void getToken(TickerToken tt) {
		this.tt = tt;
	}

	/**
	 * @return true if this MO was scheduled on the ticker and hasn't been
	 * cancelled since.
	 */
	public final boolean scheduled() {
		return tt != null;
	}

	/**
	 * Cancels this Event from the last Ticker it was added to.
	 * 
	 * @return True if it was cancelled, false otherwise (unscheduled or
	 *         already executed).
	 */
	public final boolean cancel() {
	    if(Core.logger.shouldLog(Logger.DEBUG, this))
	        Core.logger.log(this, "Cancelling (EMO) "+this, Logger.DEBUG);
	    if(tt == null) return false;
	    boolean b = tt.cancel();
	    tt = null;
	    return b;
	}

	/**
	 * If a RequestObject is dropped, it cancels itself.
	 */
	public void drop(Node n) {
		cancel();
	}
    
}
