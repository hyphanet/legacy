package freenet.node.simulator.whackysim;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Random;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.node.rt.RunningAverage;
import freenet.support.LRUQueue;

/**
 * @author amphibian
 * 
 * A WhackySim node.
 * 
 * Has a number, supports greedy routing (node to node, mesh
 * simulation).
 * 
 * Also supports NGR with an actual datastore. Thus it has an
 * LRU of keys for the datastore, plus a KeyspaceEstimator, which
 * records the number of hops taken to find a key.
 * 
 * Connections are unidirectional. Mainly because this is easier.
 * Previous simulations have nasty bugs relating to bidi connections.
 */
public class Node {


    /**
     * Contains the results of a call to the route function.
     */
    public class RoutingResult {

        public Node best;
        public RouteEstimator est;
        public double bestEstimate;

    }
    // My connections
    Node[] myConns;
    final HashMap estimators;
    final KeyspaceEstimatorFactory kef;
    
    /** My location value for greedy routing */
    final int myValue;
    
    /** Running average of key values used for IAN_CRAZY_ONE */
    final RunningAverage raKeys;
    
    long lastRequestID = -1;
    long totalHits;
    long lastCycleHits;
    long totalSuccesses;
    long lastCycleSuccesses;
    boolean active;
    
    // NGR
    final LRUQueue datastore;
    static final int MAX_DATASTORE_SIZE = 100;
    static final boolean DO_RANDOM_ROUTING = false;
    static final boolean DO_FAKE_PCACHING = false;
    static final boolean DO_THREE_ESTIMATORS = false;
    static final boolean DO_PATHCOUNTING_THREE_ESTIMATORS = true;
    static final boolean DO_NO_PATHCOUNTING = true;
    static final boolean PATHCOUNTING_HALF_VALUES = false;
    static final boolean DO_PATHCOUNTING_NO_TDNF = false;
    static final boolean PROBABILITY_RUNNING_AVERAGE = false;
    static final boolean PRODUCT_OF_TSUCCESS_AND_PFAILURE = true;
    static final boolean PRODUCT_TSUCCESS_INCLUDE_BOTH_AVERAGES = false;
    static final boolean DO_PROB_ESTIMATOR = false;
    static final boolean DO_IAN_CRAZY_ONE = false;
    static final boolean RANDOM_REINSERT = false;
    static final boolean DO_PROBE_REQUESTS = false;
    static final boolean DO_PREFIX = false;
    static final double RANDOM_REINSERT_PROBABILITY = 0.005;
    static final int REQUEST_FAKE_PCACHING_NODES = 5;
    static final int INSERT_FAKE_PCACHING_NODES = 5;
    private final SuccessFailureStats timeCounter;
    
    public Node(int i, KeyspaceEstimatorFactory kef, Random r) {
        myValue = i;
        myConns = new Node[0];
        datastore = new LRUQueue();
        estimators = new HashMap();
        this.kef = kef;
        active = false;
        timeCounter = new SuccessFailureStats();
        raKeys = new KeyAverager(r);
    }

    /**
     * Do greedy routing to a node number.
     * Simplifying assumption: Since all nodes have connections to last and next,
     * we know it won't loop.
     * @param nodes
     * @param target
     * @param r
     * @return The number of hops taken.
     */
    public int greedyRoute(int target, Random r, boolean onlyActive) {
        //System.out.println("Target: "+target+", this: "+myValue+" myConns "+myConns.length);
        if(target == myValue) throw new IllegalStateException("Searching for "+target+" on itself");
        int minDistance = Main.NUMBER_OF_NODES+1;
        Node bestNode = null;
        // Simply find the closest node to the target
        for(int i=0;i<myConns.length;i++) {
            Node n = myConns[i];
            if(onlyActive && !n.active) continue;
            int distance;
            if(Main.GREEDY_ROUTING_INCREASING_ONLY) {
                distance = target - n.myValue;
                if(distance < 0) continue;
            } else
                distance = Main.wrapDistance(n.myValue, target);
            //System.out.println("Node: "+n.myValue+" - distance: "+distance);
            if(distance < minDistance) {
                bestNode = n;
                minDistance = distance;
            }
        }
        if(bestNode.myValue == target) return 1;
        else return bestNode.greedyRoute(target, r, onlyActive) + 1;
    }

    /**
     * Connect to a node.
     * Don't limit number of connections. We are only called by
     * the bootstrap code, which will set us the right number of
     * conns up.
     * @param node The node we are connecting to.
     */
    public void connect(Node node, boolean noEstimators) {
        if(!noEstimators)
            estimators.put(node, newRouteEstimator());
        if(myConns.length == 0) {
            myConns = new Node[] { node };
            return;
        }
        for(int i=0;i<myConns.length;i++) {
            if(myConns[i] == node) return;
        }
        if(node == this) throw new IllegalArgumentException("Connecting to self!");
        Node[] newConns = new Node[myConns.length+1];
        System.arraycopy(myConns, 0, newConns, 0, myConns.length);
        newConns[myConns.length] = node;
        myConns = newConns;
    }

    private RouteEstimator newRouteEstimator() {
        if(DO_THREE_ESTIMATORS)
            return new StandardRouteEstimator(kef, 0);
        else if(DO_PROB_ESTIMATOR)
            return new ProbabilityOnlyRouteEstimator(kef);
        else if(DO_PATHCOUNTING_THREE_ESTIMATORS)
            return new PathCountingStandardRouteEstimator(kef, 0, timeCounter, PATHCOUNTING_HALF_VALUES, PROBABILITY_RUNNING_AVERAGE, DO_PATHCOUNTING_NO_TDNF, PRODUCT_OF_TSUCCESS_AND_PFAILURE, DO_NO_PATHCOUNTING, PRODUCT_TSUCCESS_INCLUDE_BOTH_AVERAGES);
        else if(DO_IAN_CRAZY_ONE || DO_RANDOM_ROUTING)
            return null;
        else
            return new SuccessDistanceOnlyRouteEstimator(kef);
    }

	public int outerRunRequest(Key key, int htl, long requestID, Random r) {
	    int x = runRequest(key, htl, requestID, r, DO_PREFIX);
	    if(x >= 0)
	        timeCounter.reportSuccess(x);
	    else
	        timeCounter.reportDNF(-1-x);
	    return x;
	}

	RoutingResult routingResult = new RoutingResult();
	
    /**
     * Run a request.
     * @param key The key to find.
     * @param prefix If true, we are in the prefix stage.
     * We will not cache the data, and we route randomly, and we
     * don't decrement HTL. 33% chance of un-prefixing at each hop.
     * @return The number of hops it took to find the key. -1
     * if it could not be found in a reasonable time.
     */
    public int runRequest(Key key, int htl, long requestID, Random r, boolean prefix) {
        if(Node.DO_IAN_CRAZY_ONE)
            raKeys.report(key.toDouble());
        totalHits++;
        lastRequestID = requestID;
        // First check the datastore
        if((!prefix) && datastore.contains(key)) {
            totalSuccesses++;
            return 0; // found it
        }
        if(htl == 0) return -1;
        if(prefix) {
            if(r.nextInt(3) == 1) prefix=false;
        } else
            htl--;
        // Otherwise route it
        randomizeMyConns(r);
        route(key, htl, requestID, r, prefix);
        // FIXME: RNFs are fatal
        if(routingResult.best == null) return -1;
        int result = routingResult.best.runRequest(key, htl, requestID, r, prefix);
        if(result < 0) {
            if(routingResult.est != null)
                routingResult.est.failed(key, -1-result);
            return result-1;
        }
        // Otherwise, it succeeded
        // Update estimator
        if(routingResult.est != null)
            routingResult.est.succeeded(key, result);
        // Store data
        if((!prefix) && (!DO_FAKE_PCACHING) || (result <= REQUEST_FAKE_PCACHING_NODES))
            store(key);
        if(RANDOM_REINSERT && r.nextDouble() < RANDOM_REINSERT_PROBABILITY)
            runInsert(key, Main.INSERT_HTL, r.nextLong(), r);
        totalSuccesses++;
        return result+1;
    }

    /**
     * @param key
     * @param htl
     * @param requestID
     * @param r
     */
    private void route(Key key, int htl, long requestID, Random r, boolean inPrefix) {
        Node best = null;
        RouteEstimator est = null;
        double bestEstimate = Double.MAX_VALUE;
        double kval = key.toDouble();
        boolean probeRequest = DO_PROBE_REQUESTS && r.nextInt(20) == 0;
        for(int i=0;i<myConns.length;i++) {
            Node n = myConns[i];
            // First check for loop
            // FIXME: should reduce HTL?
            if(!n.active) {
                //System.out.println("Not active: "+n.myValue);
                continue;
            }
            if(n.lastRequestID == requestID) {
                //System.out.println("Loop: "+n.myValue);
                continue;
            }
            double estimate;
            RouteEstimator e = (RouteEstimator)estimators.get(n);
            if(probeRequest)
                estimate = n.totalHits;
            else if(DO_IAN_CRAZY_ONE)
                estimate = Math.abs(n.raKeys.currentValue() - kval);
            else if(inPrefix || DO_RANDOM_ROUTING)
                estimate = r.nextDouble();
            else {
                estimate = e.estimate(key);
            }
            //System.out.println("Estimate: "+estimate+" for "+n.myValue+", best="+bestEstimate);
            if(estimate < bestEstimate) {
                bestEstimate = estimate;
                best = n;
                est = e;
            }
        }
        routingResult.best = best;
        routingResult.est = est;
        routingResult.bestEstimate = bestEstimate;
    }

    /**
     * Simple randomize-an-array function.
     */
    private void randomizeMyConns(Random r) {
        Node[] newConns = new Node[myConns.length];
        for(int i=0;i<myConns.length;i++) {
            while(true) {
                // Find a not-moved-yet Node in myConns, and move it to newConns.
                int x = r.nextInt(myConns.length);
                Node n = myConns[x];
                if(n != null) {
                    newConns[i] = n;
                    myConns[x] = null;
                    break;
                }
            }
        }
        myConns = newConns;
    }

    /**
     * Do an insert. Very similar to doing a request.
     * @param key
     * @param insert_htl
     * @param id
     */
    public int runInsert(Key key, int htl, long id, Random r) {
        lastRequestID = id;
        if(htl == 0) {
            //System.out.println("Success inserting "+key+" on node "+myValue);
            store(key);
            return 0;
        }
        //System.out.println("Inserting "+key+" on node "+myValue+" at htl "+htl);
        // Otherwise route
        // Otherwise route it
        randomizeMyConns(r);
        int countTried = 0;
        while(true) {
            route(key, htl, id, r, false);
        if(routingResult.best == null) {
            //System.err.println("RNF inserting data, returning "+-(countTried+1));
            return -(countTried+1);
        }
        //System.out.println("Routing to "+routingResult.best.myValue+" from "+this.myValue+" for "+key);
        int result = routingResult.best.runInsert(key, htl-1, id, r);
        if(result >= 0) {
            if((!DO_FAKE_PCACHING) || result <= INSERT_FAKE_PCACHING_NODES)
                store(key);
            return result+1;
        }
        // Decrement HTL
        //htl+=result;
        countTried -= result;
        }
    }

    /**
     * Store a key into the datastore.
     */
    private void store(Key key) {
        datastore.push(key);
        while(datastore.size() > MAX_DATASTORE_SIZE)
            datastore.pop();
    }

    public void activate() {
        System.out.println("Activating "+myValue);
        if(active) throw new IllegalStateException("Was already active");
        active = true;
        Main.deregisterBorderNode(this);
        Main.registerActive(this);
        for(int i=0;i<myConns.length;i++) {
            Node n = myConns[i];
            if(!n.active) {
                Main.registerBorderNode(n);
            }
        }
    }

    public int countConnections() {
        return myConns.length;
    }

    /**
     * Dump the active connections of this node to stdout.
     * @return Total count of active connections.
     */
    public int dumpActiveConns() {
        int count = 0;
        for(int i=0;i<myConns.length;i++) {
            Node n = myConns[i];
            if(n.active) {
                System.out.println("Active: "+myValue+" -> "+n.myValue);
                count++;
            }
        }
        System.out.println("Node "+myValue+" connections: "+count);
        return count;
    }

    public boolean isConnected(Node node) {
        for(int i=0;i<myConns.length;i++) {
            if(myConns[i] == node) return true;
        }
        return false;
    }

    public void clearStructure() {
        myConns = new Node[0];
        estimators.clear();
    }

    /**
     * Dump this node
     */
    public void dump(PrintWriter pw, String filenameBase) {
        pw.println("Node: "+myValue);
        pw.println("Total hits: "+totalHits);
        pw.println("Total successes: "+totalSuccesses);
        for(int i=0;i<myConns.length;i++) {
            Node n = myConns[i];
            if(!n.active) {
                pw.println("Inactive link: "+n.myValue);
                continue;
            }
            pw.println("Active link: "+n.myValue);
            pw.println("His estimator for me:");
            RouteEstimator r = (RouteEstimator)(n.estimators.get(this));
            r.dump(pw, filenameBase+"-"+n.myValue+"-for-"+myValue);
        }
    }
}
