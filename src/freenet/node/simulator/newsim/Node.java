package freenet.node.simulator.newsim;

import java.util.Enumeration;

import freenet.Key;
import freenet.support.LRUQueue;
import freenet.support.Logger;

/**
 * Simulated Node.
 */
public class Node {

    final Simulator sim;
    final Connections conns;
    final LRUQueue datastore;
    int hits;
    /** The unique ID of the last request to pass through this node. Since we
     * don't simulate overlapping requests, we do not need to keep more than 1.
     */
    int lastID = -1;
    static boolean logDEBUG = true;
    
    public Node(Simulator sim) {
        this.sim = sim;
        conns = new Connections(this);
        datastore = new LRUQueue();
    }

    public boolean isConnectedTo(Node n2) {
        return conns.isConnected(n2);
    }

    public void forceConnectTo(Node n2) {
        conns.forceConnect(n2);
    }

    /**
     * Run an insert.
     * @param k
     * @param prevNode
     * @param ic
     * @return true if the insert succeeded. If false, check the context for why.
     * It might just be a rejection.
     */
    public boolean runInsert(Key k, Node prevNode, InsertContext ic) {
        hits++;
        logDEBUG = sim.logger.shouldLog(Logger.DEBUG, this);
        sim.clock++;
        // Run an insert
        // We do not terminate on collision now; also, we never actually reinsert in
        // the driver program :)
        // FIXME: might conceivably want to terminate on collision
        // But since we don't...
        if(ic.id == lastID) {
            ic.lastFailureCode = BaseContext.REJECTED_LOOP;
        }
        lastID = ic.id;
        ic.stepHTL();
        if(ic.hopsToLive == 0) {
            maybeCache(k, ic);
            // End of route
            return true;
        }
        while(true) {
            // Otherwise we have to route it
            Route nextRoute = route(k, prevNode, ic.id, true);
            Node next = nextRoute.connectedTo;
            
            conns.promote(nextRoute);
            boolean nextHop = next.runInsert(k, this, ic);
            if(nextHop) {
                maybeCache(k, ic);
                return true;
            } else {
                if(ic.lastFailureCode == BaseContext.REJECTED_LOOP) {
                    ic.stepHTL();
                    continue;
                }
                if(ic.lastFailureCode == BaseContext.INSERT_FAILED)
                    return false;
                throw new IllegalStateException("Invalid code: "+ic.lastFailureCodeToString());
            }
        }
    }

    private void maybeCache(Key k, BaseContext ic) {
        if(sim.myConfig.doPCaching && ic instanceof RequestContext) {
            double d = Math.pow(0.8, ((RequestContext)ic).hopsSinceReset);
            double r = sim.r.nextFloat();
            if(r > d) return;
        }
        // FIXME: implement pcaching
        if(datastore.contains(k)) return;
        datastore.push(k);
        while(datastore.size() > sim.myConfig.maxDSSize) {
            datastore.pop();
        }
    }

    /**
     * Route a request for a given key.
     * ASSUMPTION: All keys are exactly the same size.
     * @param k the key we are routing
     * @param prevNode the node we got this from
     * @param requestID the request's unique ID. Don't route to nodes which we have
     * recently routed this ID to.
     * @param logIDOnReturn If true, assume that we actually did route to the 
     * returned node, and log the ID on its Route.
     * @return the node to route the request on to
     */
    private Route route(Key k, Node prevNode, int requestID, boolean logIDOnReturn) {
        conns.recalculateRouteTiebreakerValues();
        Route[] routes = conns.routesAsArrayCached();
        boolean logDEBUG = sim.logger.shouldLog(Logger.DEBUG, this);
        if(logDEBUG)
            sim.logger.log(this, "Routes count: "+routes.length, Logger.DEBUG);
        /** Algorithm:
         * 5% of the time, a request is a probe request.
         * 95% of the time, a request is routed simply by the estimators.
         * Either way, we want the lowest value.
         * Some of the values may be equal.
         * If they are, we have a tiebreaker value which is recalculated on each
         * routing, which is simply a random integer.
         * 
         * EXCLUSIONS:
         * - prevNode, if it's not null
         * - any node we have previously routed this ID to
         * -- we keep the last ID we routed to a given node on the Route object
         */
        boolean probeRequest = (sim.r.nextInt(20) == 0);
        if(logDEBUG && probeRequest)
            sim.logger.log(this, "Probe request", Logger.DEBUG);
        double bestEstimate = Double.MAX_VALUE;
        int bestTiebreaker = Integer.MAX_VALUE;
        Route best = null;
        for(int i=0;i<routes.length;i++) {
            Route r = routes[i];
            if(r == null)
                throw new IllegalStateException("null route");
            if(r.connectedTo == prevNode) continue;
            if(r.lastID == requestID) continue;
            double estimate;
            if(probeRequest) {
                estimate = routes[i].totalHits();
            } else {
                estimate = routes[i].estimate(k);
            }
            int tiebreaker = routes[i].tiebreaker;
            if(estimate < bestEstimate || 
                    (estimate == bestEstimate && tiebreaker < bestTiebreaker)) {
                bestEstimate = estimate;
                bestTiebreaker = tiebreaker;
                best = r;
            }
        }
        if(logDEBUG)
            sim.logger.log(this, "Routing to "+best+" - estimate was "+bestEstimate, Logger.DEBUG);
        if(best == null) return null;
        if(logIDOnReturn) {
            best.lastID = requestID;
        }
        return best;
    }

    /**
     * Run a request.
     * @param k The key we are routing.
     * @param prevNode The previous node (we don't want to route back to it).
     * @param rc The context for the request, on which things like HTL are stored.
     * @return True if the request succeeded, false if it failed. If it failed,
     * the reason will be put on the request context.
     */
    public boolean runRequest(Key k, Node prevNode, RequestContext rc) {
        hits++;
        sim.clock++;
        long startTime = sim.clock;
        if(logDEBUG)
            sim.logger.log(this, "Running request for "+k+" on "+this+" - context "+rc, Logger.DEBUG);
        // First, check whether the key is in the store
        if(datastore.contains(k)) {
            // Return from store
            // No impact on our estimators as we didn't route
            datastore.push(k);
            rc.setDataSource(this);
            return true;
        }
        if(rc.id == lastID) {
            rc.lastFailureCode = BaseContext.REJECTED_LOOP;
        }
        lastID = rc.id;
        rc.stepHTL();
        
        if(rc.hopsToLive == 0) {
            rc.lastFailureCode = BaseContext.DATA_NOT_FOUND;
            return false;
        }
        
        // Lets route
        
        while(true) {
            Route next = route(k, prevNode, rc.id, true);
            Node n = next.connectedTo;
            
            conns.promote(next);
            boolean nextHop = n.runRequest(k, this, rc);
            if(nextHop) {
                // Yay!
                next.succeeded(k, sim.clock - startTime);
                maybeCache(k, rc);
                if(sim.myConfig.doRequestConnections) {
                    Node src = rc.getDataSource();
                    rc.stepDataSource(this);
                    conns.connect(src, false);
                }
                return true;
            } else {
                if(rc.lastFailureCode == BaseContext.REJECTED_LOOP) {
                    rc.stepHTL();
                    continue;
                }
                if(rc.lastFailureCode == BaseContext.INSERT_FAILED) {
                    System.err.println("Impossible error code INSERT_FAILED routing "+k+" on "+this);
                    System.exit(1);
                }
                if(rc.lastFailureCode == BaseContext.DATA_NOT_FOUND) {
                    next.failed(k, sim.clock - startTime);
                    // :(
                    return false;
                }
                throw new IllegalStateException("Invalid code: "+rc.lastFailureCodeToString());
            }
        }
    }

    /**
     * Dump the datastore contents to log
     */
    public void dumpStore(int index) {
        // In order of LRU
        Key[] keys = new Key[datastore.size()];
        Enumeration e = datastore.elements();
        int i=0;
        int[] firstNibble = new int[16];
        while(e.hasMoreElements()) {
            keys[i] = (Key) e.nextElement();
            // hack!
            int fn = Integer.parseInt(""+keys[i].toString().charAt(0),16);
            firstNibble[fn]++;
            i++;
        }
        java.util.Arrays.sort(keys);
//        for(i=0;i<keys.length;i++) {
//            sim.logger.log(this, ""+keys[i]+" on "+this+" ("+index+")", Logger.NORMAL);
//        }
        for(i=0;i<firstNibble.length;i++) {
            sim.logger.log(this, ""+i+": "+firstNibble[i]+" on "+this+" ("+index+")", Logger.NORMAL);
        }
    }
}
