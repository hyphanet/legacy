package freenet.node.rt;

import freenet.Identity;

/**
 * Supplies a sequence of NodeReferences in order of routing preference. Feeds
 * information about the results of routing back into the implementation for
 * use in future routing decisions.
 * 
 * <p>
 * The methods of the Routing object must be called in this sequence (for each
 * NodeReference obtained from getNextRoute()):
 * <ol>
 * <li>Either routeConnected() or connectFailed()
 * <li>Either authFailed(), routeAccepted(), or timedOut()
 * <li>Either routeSucceeded(), transferFailed(), verityFailed(), or
 * timedOut()</li>
 * </ol>
 * </p>
 * 
 * @author tavin
 */
public interface Routing {

	/**
	 * @return the next route to try, or null if we are out of possibilities
	 */
	Identity getNextRoute();

	/**
	 * Ignore the last route, for purposes of maxRouteSteps. Called when we try
	 * to route to the originator
	 */
	void ignoreRoute();

	/**
	 * @return the Identity of the last returned NodeReference
	 */
	Identity getLastIdentity();

	// First, call one of these two:

	// Then, call only one of these (or timedOut()):

	/**
	 * Called if the routed node fails authorization (by being unable to prove
	 * its PK credentials).
	 * <p>
	 * Connection-related failures during authorization should call
	 * connectFailed().
	 */
	void authFailed();

	/**
	 * Called when the Accepted is received from the routed node.
	 */
	void routeAccepted();

	// Then call only one of these (or timedOut()):

	/**
	 * Called if there is no failure, but there is not a dataNotFound etc.
	 * Used by announcement code to tell CPAlgoRT that routing didn't fail.
	 * Do not call this if you call dataNotFound etc.  
	 */
	void routeSucceeded();

	/**
	 * Called if the routed node fails during file transfer.
	 */
	void transferFailed(long time, int htl, long size, long etime);

	/**
	 * Called if the routed node sends bad data.
	 */
	void verityFailed();

	/**
	 * Called if the routed node successfully handles the message chain but the
	 * request is answered with a QueryRejected.
	 * @param afterAccepted if true, the QR was received after the request had been 
	 * accepted; if false, the QR was received in lieu of an Accepted.
	 */
	void queryRejected(long attenuation, boolean afterAccepted);

	/**
	 * Called if the node times out waiting for a QueryRejected or Accepted,
	 * whether or not we have succeeded sending the message (since that is not
	 * generally very certain)
	 */
	void earlyTimeout();

	/**
	 * Called if the node sends the message, then times out before OR after
	 * after sending the message
	 */
	void searchFailed();

	/**
	 * Called when we get a DataNotFound
	 */
	void dataNotFound(int htl);

	void transferSucceeded(long time, int htl, long size, long etime);

	/**
	 * Called to terminate the Routing, when it will no longer be called,
	 * because of external circumstances e.g. ran out of HTL
	 */
	void terminate(boolean success, boolean routingRelated, boolean endOfRoute);

	/**
	 * Called to terminate the Routing when we haven't actually tried any
	 * network activity i.e. it's been used to determine which ref to send on.
	 * Will not log ANY statistics.
	 */
	void terminateNoDiagnostic();

	/**
	 * Indicate that if there is a DNF, it is expected, and not to update the
	 * estimators.
	 */
	void setShouldIgnoreDNF();

    /**
     * Return whether we have already routed to a node with the given
     * identity.
     * @param identity
     * @return
     */
    boolean haveRoutedTo(Identity identity);

    void transferStarted();
    
    /**
     * @return the number of nodes skipped so far due to backoff
     */
    int countBackedOff();

    /**
     * Can we route to this node, right now?
     * If returns true will also update rate limiting accordingly, 
     * assuming we HAVE sent a request.
     */
    boolean canRouteTo(Identity identity);
}
