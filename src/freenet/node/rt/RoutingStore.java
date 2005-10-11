package freenet.node.rt;

import java.util.Enumeration;

import freenet.Identity;
import freenet.node.NodeReference;

/**
 * The RoutingStore collects NodeReferences and other information about
 * nodes on the network, for routing purposes.  The information is
 * keyed by each node's PK Identity. This class is expected to be
 * thread-safe.
 * @author tavin
 */
public interface RoutingStore {

	/**
	 * @return  the number of unique nodes in the database
	 * Must be thread safe, unlike the rest of the class
	 */
	int size();

	/**
	 * Completely erases all information about a node.
	 * @return  true, if a matching node was removed
	 */
	boolean remove(Identity id);

	/**
	 * @return  true, if there is information about the node
	 *          stored in the database
	 */
	boolean contains(Identity id);

	/**
	 * @return  an enumeration of RoutingMemory objects
	 *          for the nodes currently in the table
	 */
	Enumeration elements();

	/**
	 * @return  the RoutingMemory associated with the given Identity,
	 *          or null if not found
	 */
	RoutingMemory getNode(Identity id);

	/**
	 * Creates a RoutingMemory or updates one with a new NodeReference.
	 * @return  the RoutingMemory associated with the Identity
	 *          for the given NodeReference
	 */
	RoutingMemory putNode(Identity id, NodeReference nr);
}
