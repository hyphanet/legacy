package freenet.node.rt;

import freenet.support.StringMap;
import freenet.Key;
import freenet.node.IdRefPair;
import freenet.support.PropertyArray;
/**
 * Interface for reporting runtime diagostics to
 * monitoring clients like NodeStatusServlet.
 **/
public interface RTDiagSnapshot {
    /**
     * @return A list of aggregate properties of the RoutingTable 
     *         implementation. This can return null.
     **/
    StringMap tableData();

     /**
      * @return A list of properties for each node reference in the
      *         RoutingTable implementation.  Polite implementations
      *         should set  "Address" to a String containing the ref's
      *         address and "NodeReference" to the NodeReference object
      *         for each element. This can return null.
      **/
    PropertyArray refData();
    
    /**
     * @return The RoutingTable implementation's keys. This can return null.
     **/
    Key[] keys();

    /**
     * @return an array of pairs of NodeReference and Identity
     */
    IdRefPair[] getIdRefPairs();

    /**
     * @return details of the last several requests, otherwise null.
     */
    RecentRequestHistory.RequestHistoryItem[] recentRequests();
}
