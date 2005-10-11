package freenet.node.rt;

import freenet.FieldSet;
import freenet.Key;
import freenet.Identity;
import freenet.message.StoreData;
import freenet.node.NodeReference;

/**
 * @author tavin
 */
public interface RoutingTable {
    
    /**
     * @param ref the noderef available. May be unsigned, which is why
     * we don't immediately reference it. If we return true, verify it
     * and then reference(null, nr) the signed version.
     * @return if true, we want the caller to .reference(null, nr) any
     * nodereferences it gets, regardless of source.
     * This does not take a NodeReferenceEstimatorPair because this way we can avoid verifying it
     * if not necessary.
     */
    boolean wantUnkeyedReference(NodeReference ref);
    
    /**
     * Associates a NodeReference with a key.
     *
     * @param k  The key to store the reference under. Null means no key, not
     * supported by all impls.
     * @param i   The identity of the node to associate with the key.
     * @param nr  The contact details for the node to associate with the key.
     * Null is valid, and means that we don't know them yet.
     */
    void reference(Key k, Identity i, NodeReference nr, FieldSet fs);
    
    /**
     * @return  true, if the routing table references a node
     *          with the given Identity
     */
    boolean references(Identity id);
    
    /**
     * Update a reference in the routing table, if there is one.
     * If there isn't one, and the reference is suitable (has a 
     * contact address, is signed, etc), and the routing table is 
     * short on refs, and the reference is signed, we may poach the
     * reference.
     */
    void updateReference(NodeReference ref);
    
    /**
     * @return  returns the NodeReference for the given Identity
     * null if no such noderef is present
     */
    NodeReference getNodeReference(Identity id);
    
    /**
     * Returns a Routing object which can be used to get
     * a sequence of NodeReference objects in order of
     * routing preference, and which accepts feedback
     * about the results of routing. Different 
     * implementations will use or ignore different
     * parameters (all should use the key though).
     *
     * @param k     the key to find routes for
     * @param htl   the hops to live of the message we are about to send
     * @param size  the size of the file we are trying to obtain or send
     * @param routeToNewestNodeOnly if true, don't route normally,
     * route to the node with the least usage so far
     * @param wasLocal whether the request was locally generated, for
     * stats purposes
     * @param willSendRequests whether the caller will send a request,
     * or try to, as a result of each return from getNextRoute().
     * Please set this correctly, it is used for rate limiting.
     * @return      the generated Routing object
     */
    Routing route(Key k, int htl, long size,
		  boolean isInsert, boolean isAnnouncement,
		  boolean routeToNewestNodeOnly,
		  boolean wasLocal, boolean willSendRequests);
    
    /**
     * NOTE:  It is important to hold the sync-lock
     *        while using the RoutingStore.
     * @see #semaphore()
     * @return  the underlying RoutingStore
     */
    RoutingStore getRoutingStore();

    /**
     * Actions on the routing table are naturally synchronized.
     * This returns the synchronization lock so that it can be
     * held between method calls.
     * @return  an object to synchronize on when doing multiple
     *          correlated operations on the routing table
     */
    Object semaphore();
    
    /**
     * Returns diagnostic information about the current
     * state of the RoutingTable implementation.
     *
     * @param startingUp if true, the node is starting up,
     * so don't grumble about various things that might not be
     * ready.
     * @return The diagnostic info. This can be null.
     */
    RTDiagSnapshot getSnapshot(boolean startingUp);
    
    /**
     * Returns the total number of references in the
     * RoutingTable
     */
    int getKeyCount();

    /**
     * Returns the total number of references found when 
     * initializing
     */
    long initialRefsCount();
    
    // NGRouting helpers
    /**
     * Report a successful connection
     */
    void reportConnectionSuccess(Identity id, long time);
    
    /**
     * Report a failed connection
     */
    void reportConnectionFailure(Identity id, long time);

	/**
	 * Serialize the estimator for the given node to a FieldSet for transport between nodes 
	 * @param identity the identity of the node
	 */
	FieldSet estimatorToFieldSet(Identity identity);

	/**
	 * @param nr
	 * @param sd
	 * @return
	 */
	boolean shouldReference(NodeReference nr, StoreData sd);  //TODO: the RT shouldn't know about StoreData's, bad design

	/**
	 * Update the minimum request interval on a given node.
	 * @param id the Identity of the node.
	 * @param d the minimum request interval reported by the message.
	 */
	public void updateMinRequestInterval(Identity id, double d);

    /**
     * Remove a node.
     * @param id the node to be removed
     */
    void remove(Identity id);
    
    /**
     * Count the total number of nodes in the routing table.
     * @author amphibian
     */
    int countNodes();
}



