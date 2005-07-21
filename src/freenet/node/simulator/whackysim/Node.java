package freenet.node.simulator.whackysim;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Random;

import freenet.Key;
import freenet.node.rt.BootstrappingDecayingRunningAverage;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.node.rt.RunningAverage;
import freenet.support.LRUQueue;
import freenet.node.simulator.whackysim.Main.ReadFromDiskException;

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
public class Node implements Serializable {


    /**
     * Contains the results of a call to the route function.
     */
    public class RoutingResult implements Serializable {

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
    long hitsUpToLastCycle;
    long hitsLoad;
    long totalSuccesses;
    long lastCycleSuccesses;
    long successesLoad;
    boolean active;
    RunningAverage avgHits = new BootstrappingDecayingRunningAverage(1.0, 0.0, Long.MAX_VALUE, 100);
    RunningAverage avgSuccesses = new BootstrappingDecayingRunningAverage(1.0, 0.0, Long.MAX_VALUE, 100);
    final RouteEstimator globalEstimator;
    final LRUDoubleRank storeEstimateRank = new LRUDoubleRank(PCACHE_BY_ESTIMATE_RANK_MAX);
    
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
    static final boolean DO_PROBE_REQUESTS = true;
    static final boolean DO_PREFIX = false;
    /** Count each hop as not 1 but (number of reqs served last cycle).
     * Minimum of 1.
     * Incompatible with fake-pcaching as presently implemented.
     */
    static final boolean DO_LOAD_COST = true;
    static final boolean DO_LOAD_COST_TOTAL_HITS = false;
    static final boolean DO_LOAD_COST_HITS = true;
    static final boolean DO_LOAD_COST_TOTAL_SUCCESSES = false;
    static final boolean LOAD_COST_AVERAGED = true;
    static final double RANDOM_REINSERT_PROBABILITY = 0.005;
    static final int REQUEST_FAKE_PCACHING_NODES = 2;
    static final int INSERT_FAKE_PCACHING_NODES = 2;
    static boolean DO_SIMPLE_PCACHE = false;
    static boolean DO_CACHE_ADD_BY_ESTIMATE = false;
    static final int PCACHE_BY_ESTIMATE_RANK_MAX = 100;
    private final SuccessFailureStats timeCounter;
    
    public static void getFilenameBase(StringBuffer sb) {
        sb.append(MAX_DATASTORE_SIZE);
        sb.append("store-");
        if(DO_RANDOM_ROUTING)
            sb.append("randomrouting-");
        if(DO_FAKE_PCACHING) {
            sb.append("fakepcaching-");
            sb.append(REQUEST_FAKE_PCACHING_NODES);
            sb.append("req-");
            sb.append(INSERT_FAKE_PCACHING_NODES);
            sb.append("insert-");
        }
        if(DO_THREE_ESTIMATORS)
            sb.append("3est-");
        if(DO_PATHCOUNTING_THREE_ESTIMATORS)
            sb.append("pathcount3est-");
        if(DO_NO_PATHCOUNTING)
            sb.append("nopathcount-");
        if(PATHCOUNTING_HALF_VALUES)
            sb.append("pathcounthalfvalues-");
        if(DO_PATHCOUNTING_NO_TDNF)
            sb.append("nodnftime-");
        if(PROBABILITY_RUNNING_AVERAGE)
            sb.append("probra-");
        if(PRODUCT_OF_TSUCCESS_AND_PFAILURE)
            sb.append("pfailure_times_tsuccess-");
        if(PRODUCT_TSUCCESS_INCLUDE_BOTH_AVERAGES)
            sb.append("inc_both_averages-");
        if(DO_PROB_ESTIMATOR)
            sb.append("pureprob-");
        if(DO_IAN_CRAZY_ONE)
            sb.append("iancrazy1-");
        if(RANDOM_REINSERT) {
            sb.append("randomreinsert-");
            sb.append(RANDOM_REINSERT_PROBABILITY);
        }
        if(DO_PROBE_REQUESTS)
            sb.append("proberequests-");
        if(DO_PREFIX)
            sb.append("prefix-");
        if(DO_CACHE_ADD_BY_ESTIMATE)
            sb.append("cachebyestimate-");
        if(DO_SIMPLE_PCACHE)
            sb.append("simplepcaching-");
        if(DO_LOAD_COST) {
            sb.append("loadcost-");
            if(DO_LOAD_COST_TOTAL_HITS)
                sb.append("totalhits-");
            if(DO_LOAD_COST_HITS)
                sb.append("hits-");
            if(DO_LOAD_COST_TOTAL_SUCCESSES)
                sb.append("totalsuccesses-");
            if(LOAD_COST_AVERAGED)
                sb.append("avg100-");
        }
    }
    
    public Node(int i, KeyspaceEstimatorFactory kef, Random r) {
        myValue = i;
        myConns = new Node[0];
        datastore = new LRUQueue();
        estimators = new HashMap();
        this.kef = kef;
        active = false;
        timeCounter = new SuccessFailureStats();
        raKeys = new KeyAverager(r);
        globalEstimator = newRouteEstimator();
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

    static int hopCounter = 0;
    
	public int outerRunRequest(Key key, int htl, long requestID, Random r) {
	    hopCounter = 0;
	    int x = runRequest(key, htl, requestID, r, DO_PREFIX);
	    if(x >= 0) {
	        timeCounter.reportSuccess(x);
	        return hopCounter;
	    } else {
	        timeCounter.reportDNF(-1-x);
	        return -hopCounter-1;
	    }
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
        hopCounter++;
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
        int myDelta = 1;
        if(Node.DO_LOAD_COST) {
            if(DO_LOAD_COST_TOTAL_HITS)
                myDelta = (int) (1 + hitsUpToLastCycle);
            else if(DO_LOAD_COST_HITS)
                myDelta = (int) (1 + (Node.LOAD_COST_AVERAGED ? avgHits.currentValue() : hitsLoad));
            else if(DO_LOAD_COST_TOTAL_SUCCESSES)
                myDelta = (int) (1 + totalSuccesses);
            else
                myDelta = (int) (1 + (Node.LOAD_COST_AVERAGED ? avgSuccesses.currentValue() : successesLoad));
        }
        int result = routingResult.best.runRequest(key, htl, requestID, r, prefix);
        if(result < 0) {
            if(routingResult.est != null) {
                routingResult.est.failed(key, -myDelta-result);
                globalEstimator.failed(key, -myDelta-result);
            }
            return result-myDelta;
        }
        // Otherwise, it succeeded
        // Update estimator
        if(routingResult.est != null) {
            routingResult.est.succeeded(key, result);
            globalEstimator.succeeded(key, result);
        }
        // Store data
        if((!prefix) && (!DO_FAKE_PCACHING) || (result <= REQUEST_FAKE_PCACHING_NODES))
            store(key, r);
        if(RANDOM_REINSERT && r.nextDouble() < RANDOM_REINSERT_PROBABILITY)
            runInsert(key, Main.INSERT_HTL, r.nextLong(), r);
        totalSuccesses++;
        return result+myDelta;
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
            store(key, r);
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
                store(key, r);
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
    private void store(Key key, Random r) {
        if(DO_CACHE_ADD_BY_ESTIMATE) {
            // Should we cache?
            double d = globalEstimator.estimate(key);
            int rank = storeEstimateRank.rank(d);
            if(datastore.size() < MAX_DATASTORE_SIZE)
                datastore.push(key);
            else {
                if(r.nextDouble() >= ((double)(rank+1)) / (3*storeEstimateRank.currentMaxRank()))
                    datastore.push(key);
            }
        } else if(DO_SIMPLE_PCACHE) {
            if(r.nextDouble() >= (1.0/3.0))
                datastore.push(key);
        } else
            datastore.push(key);
        // Always strict LRU for _removals_
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

    public int countConnectionsToActiveNodes() {
        int count = 0;
        for(int i=0;i<myConns.length;i++) {
            Node n = myConns[i];
            if(n.active) {
                count++;
            }
        }
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

    public String linkDistribution() {
        StringBuffer sb = new StringBuffer();
        for(int i=0;i<myConns.length;i++) {
            Node n = myConns[i];
            if(!n.active) continue;
            sb.append(" ");
            sb.append(n.myValue);
            sb.append("=");
            RouteEstimator r = (RouteEstimator)(n.estimators.get(this));
            sb.append(r.hits());
        }
        return sb.toString();
    }
    
    /**
     * @param oos
     */
    public static void writeStaticToDisk(ObjectOutputStream oos) throws IOException {
        oos.writeInt(MAX_DATASTORE_SIZE);
        oos.writeBoolean(DO_RANDOM_ROUTING);
        oos.writeBoolean(DO_FAKE_PCACHING);
        oos.writeBoolean(DO_THREE_ESTIMATORS);
        oos.writeBoolean(DO_NO_PATHCOUNTING);
        oos.writeBoolean(PATHCOUNTING_HALF_VALUES);
        oos.writeBoolean(PROBABILITY_RUNNING_AVERAGE);
        oos.writeBoolean(PRODUCT_OF_TSUCCESS_AND_PFAILURE);
        oos.writeBoolean(PRODUCT_TSUCCESS_INCLUDE_BOTH_AVERAGES);
        oos.writeBoolean(DO_PROB_ESTIMATOR);
        oos.writeBoolean(DO_IAN_CRAZY_ONE);
        oos.writeBoolean(RANDOM_REINSERT);
        oos.writeBoolean(DO_PROBE_REQUESTS);
        oos.writeBoolean(DO_PREFIX);
        oos.writeDouble(RANDOM_REINSERT_PROBABILITY);
        oos.writeInt(REQUEST_FAKE_PCACHING_NODES);
        oos.writeInt(INSERT_FAKE_PCACHING_NODES);
    }

    public static void readFromDisk(ObjectInputStream ois) throws IOException, ReadFromDiskException {
        int storeSize = ois.readInt();
        if(storeSize != MAX_DATASTORE_SIZE)
            throw new ReadFromDiskException("bad store size: "+storeSize+" expected "+MAX_DATASTORE_SIZE);
        boolean randomRouting = ois.readBoolean();
        if(randomRouting != DO_RANDOM_ROUTING)
            throw new ReadFromDiskException("bad random routing: "+randomRouting+" expected "+DO_RANDOM_ROUTING);
        boolean fakePCaching = ois.readBoolean();
        if(fakePCaching != DO_FAKE_PCACHING)
            throw new ReadFromDiskException("bad fake pcaching: "+fakePCaching+" should be "+DO_FAKE_PCACHING);
        boolean threeEstimators = ois.readBoolean();
        if(threeEstimators != DO_THREE_ESTIMATORS)
            throw new ReadFromDiskException("bad 3est: "+threeEstimators+" should be "+DO_THREE_ESTIMATORS);
        boolean noPathcounting = ois.readBoolean();
        if(noPathcounting != DO_NO_PATHCOUNTING)
            throw new ReadFromDiskException("bad no-pathcounting: "+noPathcounting+" should be "+DO_NO_PATHCOUNTING);
        boolean halfValues = ois.readBoolean();
        if(halfValues != PATHCOUNTING_HALF_VALUES)
            throw new ReadFromDiskException("bad half-values: "+halfValues+" should be "+PATHCOUNTING_HALF_VALUES);
        boolean probRA = ois.readBoolean();
        if(probRA != PROBABILITY_RUNNING_AVERAGE)
            throw new ReadFromDiskException("bad probRA: "+probRA+" should be "+PROBABILITY_RUNNING_AVERAGE);
        boolean tSuccessTimesPFailure = ois.readBoolean();
        if(tSuccessTimesPFailure != PRODUCT_OF_TSUCCESS_AND_PFAILURE)
            throw new ReadFromDiskException("bad tsuccess_times_pfailure: "+tSuccessTimesPFailure+" should be "+PRODUCT_OF_TSUCCESS_AND_PFAILURE);
        boolean bothAverages = ois.readBoolean();
        if(bothAverages != PRODUCT_TSUCCESS_INCLUDE_BOTH_AVERAGES)
            throw new ReadFromDiskException("bad both-averages: "+bothAverages+" should be "+PRODUCT_TSUCCESS_INCLUDE_BOTH_AVERAGES);
        boolean probEstimator = ois.readBoolean();
        if(probEstimator != DO_PROB_ESTIMATOR)
            throw new ReadFromDiskException("bad probability-only-estimators: "+probEstimator+" should be "+DO_PROB_ESTIMATOR);
        boolean ianCrazyOne = ois.readBoolean();
        if(ianCrazyOne != DO_IAN_CRAZY_ONE)
            throw new ReadFromDiskException("bad ian-crazy-one: "+ianCrazyOne+" should be "+DO_IAN_CRAZY_ONE);
        boolean randomReinsert = ois.readBoolean();
        if(randomReinsert != RANDOM_REINSERT)
            throw new ReadFromDiskException("bad random reinsert: "+randomReinsert+" should be "+RANDOM_REINSERT);
        boolean probeRequests = ois.readBoolean();
        if(probeRequests != DO_PROBE_REQUESTS)
            throw new ReadFromDiskException("bad probe requests: "+probeRequests+" should be "+DO_PROBE_REQUESTS);
        boolean prefix = ois.readBoolean();
        if(prefix != DO_PREFIX)
            throw new ReadFromDiskException("bad prefix: "+prefix+" should be "+DO_PREFIX);
        double randomReinsertProb = ois.readDouble();
        if(randomReinsertProb != RANDOM_REINSERT_PROBABILITY)
            throw new ReadFromDiskException("bad random reinsert probability: "+randomReinsertProb+" should be "+RANDOM_REINSERT_PROBABILITY);
        int reqFakePCachingNodes = ois.readInt();
        if(reqFakePCachingNodes != REQUEST_FAKE_PCACHING_NODES)
            throw new ReadFromDiskException("bad request fake pcaching nodes: "+reqFakePCachingNodes+" should be "+REQUEST_FAKE_PCACHING_NODES);
        int insFakePCachingNodes = ois.readInt();
        if(insFakePCachingNodes != INSERT_FAKE_PCACHING_NODES)
            throw new ReadFromDiskException("bad insert fake pcaching nodes: "+insFakePCachingNodes+" should be "+INSERT_FAKE_PCACHING_NODES);
    }
}
