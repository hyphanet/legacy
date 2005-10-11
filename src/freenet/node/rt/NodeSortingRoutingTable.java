package freenet.node.rt;

import freenet.Identity;

/**
 * Combiner interface.
 */
public interface NodeSortingRoutingTable 
	extends RoutingTable, NodeSorter {

	/**
	 * @return maximum probability of success for any
	 * estimator which isn't newbie.
	 */
	double maxPSuccess();

    /**
     * @param identity
     * @param now
     * @return
     */
    long timeTillCanSendRequest(Identity identity, long now);
}
