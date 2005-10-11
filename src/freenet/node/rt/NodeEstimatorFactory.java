/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.rt;

import java.io.IOException;

import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.node.NodeReference;
import freenet.support.DataObjectPending;

/**
 * Factory interface for NodeEstimators
 */
public interface NodeEstimatorFactory {
	/**
	 * Create a NodeEstimator from scratch. It knows its memory, but it does
	 * tell the RoutingMemory about itself because it does not know the key.
	 * 
	 * @param estimator
	 *            a FieldSet containing estimator data, or null
	 */
	NodeEstimator create(
		RoutingMemory mem,
		Identity id,
		NodeReference ref,
		FieldSet estimator,
		boolean needConnection,
		NodeStats stats);
	NodeEstimator create(
		RoutingMemory mem,
		Identity id, NodeReference ref,
		DataObjectPending e,
		boolean needConnection)
		throws IOException;
	/**
	 * Create a NodeEstimator
	 * 
	 * @param estimator
	 *            a FieldSet containing estimator data, or null
	 */
	NodeEstimator create(
		RoutingMemory mem,
		Identity id,
		NodeReference ref,
		FieldSet estimator,
		Key k,
		boolean needConnection,
		NodeStats stats);
	KeyspaceEstimator createGlobalTimeEstimator(String name);
	KeyspaceEstimator createGlobalRateEstimator(String name);
	/** Set the NGRoutingTable we are attached to */
	void setNGRT(NGRoutingTable ngrt);
	/**
	 * @return an instance of the NodeStats variant used to construct new node
	 *         estimators in the absence of any specific data.
	 */
	NodeStats createStats();
	/**
	 * @return an instance of the NodeStats variant used by this class,
	 *         initialized to the default values we use if we have zero RT
	 *         data.
	 */
	NodeStats defaultStats();
}
