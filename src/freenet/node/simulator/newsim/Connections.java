package freenet.node.simulator.newsim;

import java.util.HashMap;

import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;

/**
 * Class which contains the current list of connections for a node, along with
 * the various LRU structures and the estimators for the connections.
 */
public class Connections {

    /* LRU lists of Route's; first item = most recently used, 
     * last item = least recently used */
    private final DoublyLinkedList lruInexperienced;
    private final DoublyLinkedList lruExperienced;
    /** Map from Node to Route */
    final HashMap routes;
    /** Cache of Routes as an array */
    Route[] routesCached;
    /** Our Node */
    final Node node;
    
    public Connections(Node n) {
        lruExperienced = new DoublyLinkedListImpl();
        lruInexperienced = new DoublyLinkedListImpl();
        node = n;
        routes = new HashMap();
    }

    /**
     * Force a connection to the specified node.
     */
    public void forceConnect(Node n2) {
        if(!connect(n2, true)) {
            throw new IllegalStateException("Can't forcibly connect "+this+" to "+n2);
        }
    }

    public final boolean connect(Node n2, boolean force) {
        return connect(n2, force, false);
    }
    
    /**
     * Inner connect method. Can refuse to connect.
     * @param n2 The node to connect to.
     * @param force If true, try very very hard to connect to the node.
     * @param confirmed If true, then we are being called by connect, not by an
     * outside caller, and the first node is able to connect to us and does not
     * want a callback.
     * @return True if we connect successfully.
     */
    private boolean connect(Node n2, boolean force, boolean confirmed) {
        // First check the obvious stuff
        node.sim.totalAttemptedConnections++;
        if(n2 == node) return true; // already connected to self
        if(isConnected(n2)) return true; // already connected
        if(isFull()) {
            // We may not be able to accept a connection
            Node drop = droppableNode(force);
            if(drop == null) return false;
            if(!confirmed) {
                if(n2.conns.connect(node, force, true)) {
                    delete(drop);
                } else return false;
            } else delete(drop);
        }
        // Now add the node to the various data structures
        addConnectedNode(n2);
        node.sim.totalConnections++;
        return true;
    }

    /**
     * Drop a node.
     */
    private void delete(Node n) {
        Route r = (Route) routes.remove(n);
        if(r == null) throw new IllegalStateException("Deleting node not present: "+n+" in "+this);
        if(r.isExperienced())
            lruExperienced.remove(r);
        else
            lruInexperienced.remove(r);
        routesCached = null;
    }

    /**
     * Find a node to drop
     * @param force If true, can drop any node. If false, be fussy in accordance
     * with the config.
     * @return a node to drop, or null.
     */
    private Node droppableNode(boolean force) {
        if((!force) && !canDropAnyNode()) return null;
        // Now find a node we can drop
        if(node.sim.myConfig.dontDropInexperiencedNodes) {
            // Need to find an experienced node
            Route r = (Route) lruExperienced.tail();
            return r.connectedTo;
        }
        Route r = (Route) lruExperienced.tail();
        Node n = r.connectedTo;
        if(n == null) n = ((Route) lruInexperienced.tail()).connectedTo;
        return n;
    }

    private boolean canDropAnyNode() {
        if(node.sim.myConfig.minExperiencedNodes <= 0) return true;
        return lruExperienced.size() >= node.sim.myConfig.minExperiencedNodes;
    }

    /**
     * @return
     */
    public final boolean isFull() {
        return size() >= node.sim.myConfig.maxRTSize;
    }

    public final int size() {
        return routes.size();
    }

    /**
     * Add a node to the routing table. Don't drop any nodes, don't refuse to add
     * it, just add it.
     */
    private void addConnectedNode(Node n2) {
        Route r = new Route(this, n2, node.sim.r, node.sim.kef, 
                node.sim.myConfig.requestHTL, node.sim.myConfig.requestHTL*10);
        routes.put(n2, r);
        // They start inexperienced
        lruInexperienced.unshift(r);
        routesCached = null;
    }

    /**
     * @param n2
     * @return
     */
    public boolean isConnected(Node n2) {
        return routes.containsKey(n2);
    }

    /**
     * 
     */
    public void recalculateRouteTiebreakerValues() {
        Route[] routes = routesAsArrayCached();
        for(int i=0;i<routes.length;i++) {
            routes[i].tiebreaker = node.sim.r.nextInt();
        }
    }

    /**
     * @return
     */
    public Route[] routesAsArrayCached() {
        if(routesCached != null) return routesCached;
        return routesCached = (Route[]) routes.values().toArray(new Route[routes.size()]);
    }

    /**
     * Move specified route from lruInexperienced to lruExperienced if needed.
     */
    public void checkList(Route route) {
        if(route.onExperiencedList) return;
        if(route.isExperienced()) {
            route.onExperiencedList = true;
            lruInexperienced.remove(route);
            lruExperienced.unshift(route);
        }
    }

    /**
     * Whichever list it's on, promote it
     * @param nextRoute
     */
    public void promote(Route nextRoute) {
        if(nextRoute.onExperiencedList) {
            Route r = (Route) lruExperienced.remove(nextRoute);
            if(r == null) throw new IllegalStateException("Not in lruExperienced!: "+nextRoute+" for "+this);
            lruExperienced.unshift(nextRoute);
        } else {
            Route r = (Route) lruInexperienced.remove(nextRoute);
            if(r == null) throw new IllegalStateException("Not in lruInexperienced!: "+nextRoute+" for "+this);
            lruInexperienced.unshift(nextRoute);
        }
    }

    /**
     * @return
     */
    public int confirmedSize() {
        int size = routes.size();
        int asize = lruExperienced.size() + lruInexperienced.size();
        if(size != asize) {
            throw new IllegalStateException("Size of routes: "+size+" but size of LRUs: "+asize);
        }
        return size;
    }

}
