package freenet.node.rt;

import freenet.Identity;
import freenet.PeerHandler;

/**
 * Interface to sort a list of nodes according to some sort order
 * defined by the class implementing this interface.
 * One example client is NGRoutingTable.
 * @author amphibian
 */
public interface NodeSorter {
    
    /** Order an array of Identity's. Tolerant of nulls and may 
     * return nulls if items are not recognized by the sorter.
     */
    public void order(Identity[] id);

    /**
    /** Order an array of PeerHandler's (by their Identity's,
     * but we order the PH's so they don't have to be looked up
     * again). Tolerant of nulls and may return nulls if items
     * are not recognized by the sorter.
     * @param ignoreNewbies if true, don't treat newbie nodes
     * specially in the order.
     */
    public void order(PeerHandler[] ph, boolean ignoreNewbies);

    /**
     * Determine whether a node is currently considered to be newbie.
     */
    public boolean isNewbie(Identity identity, boolean isConnected);
}
