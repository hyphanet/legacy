package freenet.node.simulator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;

import freenet.Core;
import freenet.Key;
import freenet.KeyException;
import freenet.keys.CHK;
import freenet.node.rt.BucketDistribution;
import freenet.node.rt.FastSlidingBucketsKeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.node.rt.RecentRequestHistory;
import freenet.support.HexUtil;
import freenet.support.LRUQueue;

/**
 * A single simulated node.
 */
public class Node implements Comparable {

    /** Whether to route newbie nodes requests purely by their announcement key */
    static boolean DO_NEWBIEROUTING = false;
    /** Whether to cache exclusively according to the global estimate of the key.
     * This is a really stupid idea, as you will find if you simulate it. 
     * Note: uses ONE estimator. */
    //static boolean DO_CACHE_BY_ESTIMATE = false;
    /** Whether to do probabilistic caching based on hops since reset */
    static boolean DO_CLASSIC_PCACHING = false;
    /** Whether to do probabilistic reference based on hops since reset */
    static boolean DO_PREFERENCE = true;
    /** Whether to log excessively */
    static boolean DO_MORE_LOGGING = false;
    /** Whether to use two estimators. Otherwise we use one. */
    static boolean USE_THREE_ESTIMATORS = true;
    /** Whether to enable smoothing */
    static boolean DO_SMOOTHING = false;
    /** Whether to use pure random routing */
    static boolean DO_RANDOM = false;
    /** Whether to use fast estimators (double instead of BigInteger) */
    static boolean DO_FAST_ESTIMATORS = false;
    /** Whether to use announcements */
    static boolean DO_ANNOUNCEMENTS = false;
    /** Whether to use alternate announcements */
    static boolean DO_ALT_ANNOUNCEMENTS = false;
    /** Whether to use an offline RT to store old estimator data */
    static boolean DO_OFFLINE_RT = false;
    /** Whether to probe by inexperience; if not, probes are random */
    static boolean PROBE_BY_INEXPERIENCE = false;
    /** Whether to randomize when several nodes have the same estimate, if
     * not ALL nodes have the same estimate. Probably a good idea, optional
     * to allow comparison with older plots.
     */
    static boolean RANDOMIZE_WHEN_EQUAL = false;
    /** Whether to only do probe requests when we actually have some inexperienced
     * nodes (as opposed to newbie nodes)
     */
    static boolean PROBE_ONLY_WHEN_NECESSARY = false;
    /** Whether to pass estimators around */
    static boolean DO_ESTIMATOR_PASSING = false;
    /** Movement factor for estimators */
    static double MOVEMENT_FACTOR = 0.05;
    /** Number of buckets to use for estimators */
    static int BUCKET_COUNT = 8;
    /** Routing table size */
    static int RT_MAX_NODES = 25;
    /** Minimum routing table fullness at which pref kicks in */
    // FIXME: add to Main.getOptionsString if becomes configurable.
    static double PREF_MIN_RT_FULL = 1.0;
    /** Minimum number of hits before a node can be dropped */
    static int MIN_HITS_NEWBIE = 80;
    /** If true, don't accept or make any connections until we have at least
     * MIN_HITS_NEWBIE hits on each node in the RT. If false, don't dump any node
     * that has less than MIN_HITS_NEWBIE hits.
     */ 
    static boolean DO_LOW_CHURN = true;
    
    /** If true, emulate a bug in older simulations that caused the StoreData 
     * estimators to be used by both the node connecting to the DataSource, and 
     * the DataSource itself when backconnecting to the requestor. i.e. A->B->C->D,
     * DataSource is D, A connects to D using passed estimator (from C), and D also
     * uses the same estimator for A.
     */
    static boolean DO_BUGGY_BACKPASSING = false;
    
    static long connectionsAdded = 0;
    static long connectionsTried = 0;
    static long forceConnectionsTried = 0;
    static long connectionsPassedEstimators = 0;
    
    static long routedByNewbie = 0;
    static long routedRandom = 0;
    static long routedByEstimators = 0;
    
    public static void clearCounters() {
        routedByNewbie = 0;
        routedRandom = 0;
        routedByEstimators = 0;
    }
    
    public static void dumpCounters() {
        System.err.println("Routed: "+routedByNewbie+" newbie, "+routedRandom+
                " random, "+routedByEstimators+" by estimators");
    }
    
    final long id;
    static long idCounter = 0;
    
    long lastRequestID = -1;
    
    // FIXME: make configurable, then add to Main.getOptionsString.
    final double PCACHE_FACTOR = 0.8; //larger value to cache more, smaller to cache less
    
    /**
     * All the Nodes we are connected to.
     * For the purposes of the simulation:
     * We are connected to our entire routing table. We are never
     * connected to anyone who we will not consider for routing.
     * Bidirectional connections are in effect: if we are connected
     * to them, they are connected to us.
     * Maps Node to Peer
     */
    OpenConnectionManager connections;
    /** Offline routing table - include online, but is twice the size */
    LRUQueue peersLRUOffline = new LRUQueue();
    Hashtable offlinePeers = new Hashtable();
    public Key announcementKey;
    static final int KEEP_REQUESTS = 100;
    RecentRequestHistory recentRequests = new RecentRequestHistory(KEEP_REQUESTS,null);

    /**
     * Keys we actually have stored.
     */
    LRUQueue storedKeys;
    
    int MAX_STORED_KEYS;
    
    final KeyspaceEstimatorFactory kf;
    
    KeyspaceEstimator myEstimator;
    
    
    /**
     * Create a new node with no initial refs etc.
     * @param kef Factory used to create KeyspaceEstimators.
     * @param keys Set maximum ammount of keys stored on this node.
     */
    public Node(KeyspaceEstimatorFactory kef, int keys) throws KeyException {
        connections = new OpenConnectionManager(this);
        storedKeys = new LRUQueue();
        id = idCounter++;
        kf = kef;
        MAX_STORED_KEYS = keys;
        myEstimator = newEstimator();
        announcementKey = CHK.randomKey(Core.getRandSource());
    }

    /**
     * Create a node from scratch with a given id.
     * Detail will be read in later.
     */
    public Node(long id2, KeyspaceEstimatorFactory kef, int maxKeys) {
        this.id = id2;
        this.kf = kef;
        this.MAX_STORED_KEYS = maxKeys;
    }

    /**
     * Connect to a node. Adds a Node to this nodes routingtable.
     * @param n Node to add
     * @param r
     * @param force Add the node even though there are no droppable nodes in rt.
     * @return true if we are already connected, or if we successfully connect,
     * false if we can't connect.
     */
    public boolean connect(Node n, RequestStatus r, boolean force) {
        if(DO_MORE_LOGGING) {
            System.out.println("Connect to "+n+" from "+this);
            System.out.println("connections size="+connections.size());
        }
    	if (n == this) throw new IllegalArgumentException("Adding a node to it's own routing table isn't wise.");    		
        if(n.id == id) {
            System.err.println("Same ID");
            return true; // n == me :(
        }
        if(connections.getPeer(n) != null) {
            if(DO_MORE_LOGGING)
                System.out.println("Already connected");
            return true;
        }
        if(!n.canConnect(this, force)) {
            if(DO_MORE_LOGGING)
                System.out.println("Cannot connect");
            return false;
        }
        connectionsTried++;
        if(force) forceConnectionsTried++;
        if(!canConnect(n, force)) return false;
        // Now, is it in the offline routing table?
        Peer p = null;
        if(DO_OFFLINE_RT)
            p = (Peer) offlinePeers.get(n);
        if(p == null) {
            p = new Peer(this, n);
            if(DO_OFFLINE_RT) {
                // Add to offline RT
                offlinePeers.put(n, p);
                peersLRUOffline.push(p);
                while(peersLRUOffline.size() > RT_MAX_NODES * 2) {
                    Peer drop = (Peer)peersLRUOffline.pop();
                    Node dropNode = drop.n;
                    offlinePeers.remove(dropNode);
                }
            }
        } else {
            if(DO_OFFLINE_RT)
                peersLRUOffline.push(p);
        }
        if(DO_ESTIMATOR_PASSING && r != null) {
            boolean passed = false;
            if(r.sourceE != null) {
                p.e = (KeyspaceEstimator) r.sourceE.clone();
                passed = true;
                p.initialHits = p.e.countReports();
            }
            if(r.sourceEpDNF != null) {
                p.epDNF = (KeyspaceEstimator) r.sourceEpDNF.clone();
                passed = true;
                p.initialHits = p.epDNF.countReports();
            }
            if(r.sourceEtFailure != null) {
                p.etFailure = (KeyspaceEstimator) r.sourceEtFailure.clone();
                passed = true;
            }
            if(r.sourceEtSuccess != null) {
                p.etSuccess = (KeyspaceEstimator) r.sourceEtSuccess.clone();
                passed = true;
            }
            if(passed) {
                if(DO_MORE_LOGGING)
                    System.out.println("Passed estimators");
                connectionsPassedEstimators++;
            } else {
                if(DO_MORE_LOGGING)
                    System.out.println("Didn't pass estimators");
            }
        }
        connections.promote(p, false);
        // Now drop LRU
        while(connections.size() > RT_MAX_NODES) {
            Peer p2 = 
                force ? connections.removeLRUNodeExperiencedPref() :
                    connections.removeLRUExperiencedNode();
            if(p2 == null) {
                System.err.println("Couldn't find peer to drop");
                new Exception("debug").printStackTrace();
                return false;
            }
            
            Node n2 = p2.n;
            if(DO_MORE_LOGGING)
                System.out.println("Dropping "+p2+" : "+n2+" for "+this);
        }
        if(DO_MORE_LOGGING)
            System.out.println("Exiting, connections size="+connections.size());
        if(!n.connect(this, DO_BUGGY_BACKPASSING ? r : null, force)) {
            if(DO_MORE_LOGGING)
                System.out.println("Could not connect");
            connections.remove(p);
            return false;
        }
        connectionsAdded++;
        if(DO_MORE_LOGGING)
            System.out.println("Connected");
        return true;
    }

    /**
     * @param node
     * @return
     */
    private boolean canConnect(Node node, boolean force) {
        boolean willDrop = connections.size() >= RT_MAX_NODES;
        if(Node.DO_LOW_CHURN) {
            if((!force) && willDrop && connections.hasInexperiencedNodes()) {
                // Don't drop ANYTHING until we have a good picture of all nodes'
                // performance and specialization. If we drop before that, then the
                // best node (or the first node to be tried) will get experienced
                // first, and then immediately get dropped!
                if(DO_MORE_LOGGING)
                    System.out.println("Must drop if proceed and has inexperienced nodes; not connecting");
                return false;
            }
        } else {
            // CAVEAT: Will fail adding a node if experienced node list is empty
            // and force wasn't specified.
            if(willDrop && (!(connections.hasExperiencedNodes() || force))) {
                if(Node.DO_MORE_LOGGING)
                    System.out.println("No nodes to dump");
                return false;
            }
        }
        return true;
    }

    /**
     * Very brief to avoid recursion
     */
    public String toString() {
        return super.toString()+"("+Long.toHexString(id)+")";
    }
    
    /**
     * @param stream
     */
    public void dump(PrintStream stream) {
        // Dump us
        stream.println("Node ID: "+Long.toHexString(id));
        connections.dump(stream);
        stream.println("Stored keys:");
        
        for(Enumeration e = storedKeys.elements();e.hasMoreElements();) {
            Key k = (Key)e.nextElement();
            stream.println(k.toString());
        }
        stream.println();
    }

    long lastAnnounceID = -1;
    
    // Obviously IRL we route BEFORE the announcement key has been determined ;)
    public boolean announce(Node n, long id, int htl, Key announceKey, Node prev) throws KeyException {
        // Route to random key
        //System.err.println("Announce "+n+" on "+this+" htl "+htl+" announcementkey "+announceKey);
        if(lastAnnounceID == id) return false;
        if(n == this) return false;
        lastAnnounceID = id;
        Key k = CHK.randomKey(Core.getRandSource());
        HashSet routedTo = null;
        while(htl > 0) {
            Peer p = route(k, routedTo, prev, n);
            if(p == null) return false;
            Node sendTo = p.n;
            if(sendTo.announce(n, id, htl-1, announceKey, this)) break;
            if(routedTo == null) routedTo = new HashSet();
            routedTo.add(sendTo);
        }
        connect(n, null, true);
        Peer p = connections.getPeer(n);
        if(p == null) {
            System.err.println("Couldn't find "+n+" for "+this+" in announcement");
            return false;
        }
        
        if(DO_ANNOUNCEMENTS) {
            // Based on partial newbierouting
            if(p.hasAnnounced()) {
                System.err.println("Already in newbie on "+this+": "+n+" - announcementKey: "+
                        p.announcementKey+" - newbierouting="+DO_NEWBIEROUTING);
                return false;
            }
        }
        
        if(DO_ANNOUNCEMENTS) {
            p.announcementKey = announceKey;
            connections.promote(p, true);
        } else if(DO_ALT_ANNOUNCEMENTS) {
            p.setAnnouncementEstimators(announceKey);
        }
        return true;
    }

    /**
     * Run a request.
     * @param req the request
     * @param r context - where to store success, stats, etc.
     * @return the number of hops from the last node, or -1 if failure.
     */
    public int run(Request req, RequestStatus r, Node requestor) {
        HashSet triedNodes = null;
        hits++;
        recentRequests.add(req.key, req.hopsToLive, 0, 0);
        if(r.id == lastRequestID) {
            return -1;
        }
        lastRequestID = r.id;
        if(DO_MORE_LOGGING) {
            System.out.println("Running "+req+" on "+this+" with "+r);
            System.out.println("My estimate: "+myEstimator.guessTime(req.key));
        }
        r.hop();
        while(true) {
        // First find a place to put it, then put it there, then StoreData
        req.updateHTL();
        if(storedKeys.contains(req.key)) {
            storedKeys.push(req.key);
            // Success
            r.dataFound(req.hopsToLive);
            r.dataSource = this;
            myEstimator.reportTime(req.key, 0);
            successes++;
            return 0;
        }
        if(req.hopsToLive ==  0) {
            // Doh
            r.dataNotFound();
            myEstimator.reportTime(req.key, 500);
            return 0; // DNF is not an RNF
        } else {
            // First route it
            Peer p = route(req.key, triedNodes, requestor, null);
            if(p == null) {
                System.err.println("Could not find route for "+req+" on "+this);
                return -1; // rnf?
            }
            if(DO_MORE_LOGGING)
                System.out.println("Routing to: "+p.n+" on "+this+" - promoting in LRU");
            // Moved here to take over function of p.updateNewbieness
            // Right up here so we don't drop it..
            connections.promote(p, true);
            Node next = p.n;
            int result = next.run(req, r, this);
            if(result >= 0) {
                // Accepted
                if(r.succeeded()) {
                    successes++;
                    // Yay, data found
                    r.hopSinceReset(this);
                    store(req.key, r.hopsSinceReset);
                    if(USE_THREE_ESTIMATORS) {
                        if(DO_MORE_LOGGING)
                            System.out.println("Recording on epDNF and etSuccess for "+p+" for "+p.n);
                        p.epDNF.reportProbability(req.key, 0.0);
                        BucketDistribution bd = new BucketDistribution();
                        p.epDNF.getBucketDistribution(bd);
                        if(DO_MORE_LOGGING)
                            System.out.println("BD now for epDNF: "+bd.toString());
                        p.etSuccess.reportTime(req.key, result);
                    } else {
                        if(DO_MORE_LOGGING)
                            System.out.println("Recording on short estimators for "+p.n+" for "+p.n);
                        p.e.reportTime(req.key, result);
                    }
                    myEstimator.reportTime(req.key, result);
                    if((!isConnected(r.dataSource)) && shouldReference(r)) {
                        connect(r.dataSource, r, false);
                    }
                } else {
                    // Terminal failure i.e. DNF
                    // FIXME!!!
                    if(USE_THREE_ESTIMATORS) {
                        p.epDNF.reportProbability(req.key, 1.0);
                        p.etFailure.reportTime(req.key, result);
                    } else {
                        p.e.reportTime(req.key, 500);
                    }
                    myEstimator.reportTime(req.key, 500);
                }
                // Either way
                return result+1;
            } else {
                // Try a different one
                if(triedNodes == null) {
                    triedNodes = new HashSet();
                }
                triedNodes.add(next);
                continue;
            }
        }
        }
    }
        
    public boolean shouldReference(RequestStatus rs) {
    	if (rs.dataSource == this)
    		return false;
        if(connections.size() < (int)(PREF_MIN_RT_FULL * RT_MAX_NODES)) {
            return true;
        }
        if(DO_PREFERENCE) {
            double p = Math.pow(PCACHE_FACTOR, rs.hopsSinceReset);
            if(Core.getRandSource().nextFloat() > p) //don't store this data; too far from source.
                return false;
        }
        return true;
    }

    private void store(Key key, int hopsSinceReset) {
        // Don't pcache until store full
        if(storedKeys.size() < MAX_STORED_KEYS) {
            storedKeys.push(key);
            if(DO_MORE_LOGGING)
                System.out.println("Store for "+this+": "+storedKeys.size()+" keys");
            return;
        }
//        if (DO_CACHE_BY_ESTIMATE) {
//            storedKeys.push(key);
//            while (storedKeys.size() > MAX_STORED_KEYS) {
//                // Need to dump a key
//                // Normally this will only go round ONCE
//                Enumeration e = storedKeys.elements();
//                double maxEstimate = Double.MIN_VALUE;
//                double minEstimate = Double.MAX_VALUE;
//                Key worst = null;
//                while (e.hasMoreElements()) {
//                    Key k = (Key) e.nextElement();
//                    // Estimate
//                    double estimate = myEstimator.guessTime(k);
//                    if (estimate > maxEstimate) {
//                        worst = k;
//                        maxEstimate = estimate;
//                        if(DO_MORE_LOGGING)
//                            System.out.println("Worst so far: " + k + " : "
//                                    + estimate);
//                    }
//                    minEstimate = Math.min(minEstimate, estimate);
//                }
//                storedKeys.remove(worst);
//                if(DO_MORE_LOGGING)
//                    System.out.println("Removed " + worst + " - estimate was "
//                            + maxEstimate + ", min estimate was " + minEstimate
//                            + " on " + this);
//            }
//        }
        if(DO_CLASSIC_PCACHING) {
            double p = Math.pow(PCACHE_FACTOR, hopsSinceReset);
            if(Core.getRandSource().nextFloat() > p) //don't store this data; too far from source.
                return;
        }
        storedKeys.push(key);
        while(storedKeys.size() > MAX_STORED_KEYS) {
            Key k = (Key)storedKeys.pop();
            if(DO_MORE_LOGGING)
                System.out.println("Dropping "+k+" on "+this);
        }
        if(DO_MORE_LOGGING)
            System.out.println("Store for "+this+": "+storedKeys.size()+" keys");
    }

    /**
     * @param i an insert
     * @param r object to store result on
     * @return true if we succeeded, false if we failed
     */
    public boolean run(Insert i, RequestStatus r, Node requestor) {
        HashSet triedNodes = null;
        if(r.id == lastRequestID) {
            if(DO_MORE_LOGGING)
                System.out.println("Loop: "+i);
            return false;
        }
        lastRequestID = r.id;
        if(DO_MORE_LOGGING)
            System.out.println("Running "+i+" on "+this+" with "+r);
        r.hop();
        while(true) {
        // First find a place to put it, then put it there, then StoreData
        i.updateHTL();
        if(storedKeys.contains(i.key)) {
            storedKeys.push(i.key);
            // Collision
            r.collision(this);
            // Collisions are nonfatal, because we assume CHKs
            return true;
        }
        if(i.hopsToLive ==  0) {
            // Store here - don't route
            store(i.key, 0);
            r.stored();
            r.dataSource = this;
            // FIXME: StoreData (keep it on the RequestStatus, then execute it?
            return true;
        } else {
            // First route it
            Peer p = route(i.key, triedNodes, requestor, null);
            if(p == null) {
                System.err.println("Could not find route for "+i+" on "+this);
                return false; // rnf?
            }
            Node next = p.n;
            connections.promote(p, true);
            if(next.run(i, r, this)) {
                // Success
                // FIXME: implement pcaching
                r.hopSinceReset(this);
                store(i.key, r.hopsSinceReset);
                return true;
            } else {
                // Try a different one
                if(triedNodes == null) {
                    triedNodes = new HashSet();
                }
                triedNodes.add(next);
                continue;
            }
        }
        }
    }

    /**
     * Route a key to a node
     */
    private Peer route(Key k, HashSet h, Node requestor, Node other) {
        // 5% chance of random routing
        boolean routeRandom = false;
        if(DO_RANDOM) routeRandom = true;
        else if(Core.getRandSource().nextInt(20) == 0) {
            if(DO_MORE_LOGGING)
                System.out.println("Random route");
            routeRandom = true;
        }
        int newbies = connections.peersNewbieRouting.size();
        int oldnodes = connections.peersNormalRouting.size();
        int total = newbies+oldnodes;
        int x = Core.getRandSource().nextInt(total);
        if(PROBE_ONLY_WHEN_NECESSARY && routeRandom) {
            // Verify
            boolean needProbe = false;
            for(Iterator i = connections.peers();i.hasNext();) {
                Peer p = (Peer) i.next();
                if(p.isInexperienced()) {
                    needProbe = true;
                    break;
                }
            }
            if(!needProbe) routeRandom = false;
        }
        if(routeRandom) {
            if(PROBE_BY_INEXPERIENCE)
                return oldRoute(k, h, requestor, other, true);
            return randomRoute(h, requestor, other);
        }
        if(x < newbies)
            return newbieRoute(k, h, requestor, other);
        else
            return oldRoute(k, h, requestor, other, false);
    }

    BucketDistribution bd = new BucketDistribution();
    // FIXME: if we ever thread, un-static! :)
    static Peer[] oldRouteEqualPeers = new Peer[Main.INITIAL_NODES];
    
    /**
     * @param k
     * @param h
     * @return
     */
    private Peer oldRoute(Key k, HashSet h, Node requestor, Node other,
            boolean byInexperience) {
        //System.err.println("Normal route: "+k+" on "+this);
        routedByEstimators++;
        // Which has the lowest estimate?
        double minEstimate = Double.MAX_VALUE;
        double maxEstimate = Double.MIN_VALUE;
        int equalPeers = 0;
        Peer best = null;
        int sz = connections.peersNormalRouting.size();
        for(int i=0;i<sz;i++) {
            Peer p = (Peer)(connections.peersNormalRouting.get(i));
            if(h != null && h.contains(p.n)) continue;
            if(p.n == requestor) continue;
            if(p.n == other) continue;
            double estimate;
            if(byInexperience) {
                if(USE_THREE_ESTIMATORS)
                    estimate = p.epDNF.countReports();
                else
                    estimate = p.e.countReports();
            } else {
                if(USE_THREE_ESTIMATORS) {
                    double pDNF = p.epDNF.guessProbability(k);
                    double pNotDNF = 1.0 - pDNF;
                    double tSuccess = p.etSuccess.guessTime(k);
                    double tFailure = p.etFailure.guessTime(k);
                    estimate = (tSuccess * pNotDNF) + tFailure / pNotDNF;
                    if(DO_MORE_LOGGING)
                        System.out.println("pDNF: "+pDNF+", pNotDNF: "+pNotDNF+
                                ", tSuccess: "+tSuccess+", tFailure: "+
                                tFailure+", estimate: "+estimate+" for "+k+" on "+p+":"+p.n+
                                " for "+this+" - estimators epDNF: "+p.epDNF+", etSuccess: "+
                                p.etSuccess+", etFailure: "+p.etFailure);
                } else {
                    estimate = p.e.guessTime(k);
                }
            }
            if(DO_MORE_LOGGING && !USE_THREE_ESTIMATORS) {
                p.e.getBucketDistribution(bd);
                if(DO_MORE_LOGGING)
                    System.out.println("Estimate: "+estimate+" for "+p.n+" hits: "+
                            p.e.countReports()+
                            " "+bd);
            }
            if(estimate < minEstimate) {
                if(h != null && h.contains(p.n)) continue;
                minEstimate = estimate;
                best = p;
                equalPeers = 0;
                oldRouteEqualPeers[0] = p;
            } else if(estimate == minEstimate) {
                equalPeers++;
                oldRouteEqualPeers[equalPeers] = p;
            }
            maxEstimate = Math.max(estimate, maxEstimate);
        }
        if(RANDOMIZE_WHEN_EQUAL && equalPeers > 0) {
            // Pick random one
            return oldRouteEqualPeers[Core.getRandSource().nextInt(equalPeers+1)];
        }
        return best;
    }

    /**
     * @return
     */
    private Peer newbieRoute(Key k, HashSet h, Node requestor, Node other) {
        //System.err.println("Newbie route: "+k+" on "+this);
        routedByNewbie++;
        // Nearest key
        // FIXME: use long's
        // CAVEAT: long's are signed!
        BigInteger bk = k.toBigInteger();
        BigInteger minDistance = null;
        Peer bestPeer = null;
        int sz = connections.peersNewbieRouting.size();
        for(int i=0;i<sz;i++) {
            Peer p = (Peer)(connections.peersNewbieRouting.get(i));
            Node n = p.n;
            if(h != null && h.contains(n)) continue;
            if(p.n == requestor) continue;
            if(p.n == other) continue;
            BigInteger bi = p.announcementKey.toBigInteger();
            BigInteger diff1 = bi.subtract(bk);
            BigInteger diff2 = diff1.subtract(Key.KEYSPACE_SIZE);
            BigInteger diff3 = diff1.add(Key.KEYSPACE_SIZE);
            diff1 = diff1.abs();
            diff2 = diff2.abs();
            diff3 = diff3.abs();
            BigInteger diff = diff1.min(diff2).min(diff3);
            if(minDistance == null || minDistance.compareTo(diff) == 1) {
                minDistance = diff;
                bestPeer = p;
            }
        }
        return bestPeer;
    }

    private Peer[] pv = new Peer[Main.INITIAL_NODES];
    
    private Peer randomRoute(HashSet h, Node requestor, Node other) {
        //System.err.println("Random route on "+this);
        routedRandom++;
        int len = connections.peers.size();
        pv = (Peer[])connections.peers.values().toArray(pv);
        int x = 0;
        for(int y=0;y<len;y++) {
            Peer p = pv[y];
            Node n = p.n;
            if(h != null && h.contains(n)) continue;
            if(n == requestor || n == other) continue;
            pv[x] = p;
            x++;
        }
        if(x == 0) {
            System.err.println("No random route found");
            return null;
        } else {            
            // Pick randomly
            return pv[Core.getRandSource().nextInt(x)];
        }
    }
    
    /**
     * @return a new KeyspaceEstimator suitable for a node
     */
    KeyspaceEstimator newEstimator() {
    	return kf.createTime(500, null);
    }
    
    KeyspaceEstimator newTimeEstimator() {
        return kf.createTime(10, null);
    }
    
    KeyspaceEstimator newProbabilityEstimator() {
        return kf.createProbability(1.0, null);
    }
    
    KeyspaceEstimator newProbabilityEstimator(DataInputStream dis) throws IOException {
        KeyspaceEstimator k = kf.createProbability(dis,null);
        System.err.println("New probability estimator: "+k);
        return k;
    }

    KeyspaceEstimator newTimeEstimator(DataInputStream dis) throws IOException {
        KeyspaceEstimator k = kf.createTime(dis,null);
        System.err.println("New time estimator: "+k);
        return k;
    }
    
    KeyspaceEstimator newEstimator(DataInputStream dis) throws IOException {
        KeyspaceEstimator k = kf.createTime(dis,null);
        System.err.println("New estimator: "+k);
        return k;
    }
    
    public int compareTo(Object o) {
        if(o instanceof Node) {
            Node n = (Node)o;
            if(n == this) return 0;
            if(n.id == id) return 0;
            if(n.id > id) return 1;
            else return -1;
        } else throw new ClassCastException();
    }
    
    public boolean equals(Object o) {
        return o == this;
    }

    long hits = 0;
    long successes = 0;
    
    public long hits() {
        return hits;
    }

    /**
     * @param k
     * @return
     */
    public boolean containsKey(Key k) {
        return storedKeys.contains(k);
    }

    /**
     * @param n
     * @return
     */
    public boolean isConnected(Node n) {
        return connections.getPeer(n) != null;
    }

    public int peerCount() {
        return connections.size();
    }

    public Peer peerFor(Node n) {
        return connections.getPeer(n);
    }

    public void dumpDatastore(PrintStream stream) {
        Enumeration e = storedKeys.elements();
        int i=0;
        while(e.hasMoreElements()) {
            Key k = (Key)e.nextElement();
            stream.println("Key "+i+" on "+this+": "+k);
            i++;
        }
    }

    /** Dump all recent requests to a stream */
    public void dumpRecentRequests(PrintStream stream) {
        recentRequests.dumpSimple(stream);
    }

    static final int MAGIC = 0xdc8ff4b1;
    
    /**
     * Serialize the status of this node to a DataOutputStream.
     * @param dos
     */
    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(1);
        dos.writeLong(id);
        connections.writeTo(dos);
        HexUtil.writeBigInteger(announcementKey.toBigInteger(), dos);
        dos.writeLong(hits);
        dos.writeLong(successes);
        // lastAnnounceID, lastRequestID can be left alone since we don't serialize during a request
        dos.writeInt(MAX_STORED_KEYS);
        myEstimator.writeDataTo(dos);
        if(Node.DO_OFFLINE_RT) {
            // offlinePeers == peersLRUOffline, right?
            int i = peersLRUOffline.size();
            dos.writeInt(i);
            // From tail to head; head is the MRU, tail is the LRU
            // i.e. deletion order.
            Enumeration e = peersLRUOffline.elements();
            while(e.hasMoreElements()) {
                Peer p = (Peer) e.nextElement();
                Node n = p.n;
                long id = n.id;
                dos.writeLong(id);
                if(connections.getPeer(n) != null) {
                    p.writeTo(dos);
                }
            }
        }
        recentRequests.writeTo(dos);
        // FIXME: remove
        if(storedKeys == null) {
            throw new IllegalStateException("Null storedKeys on node "+id);
        }
        int i = storedKeys.size();
        dos.writeInt(i);
        if(i > MAX_STORED_KEYS)
            throw new IllegalStateException("Too many stored keys: "+i);
        Enumeration e = storedKeys.elements();
        while(e.hasMoreElements()) {
            Key k = (Key) e.nextElement();
            BigInteger bi = k.toBigInteger();
            HexUtil.writeBigInteger(bi, dos);
        }
    }

    /**
     * Read a node in.
     * @param dis Stream to read from
     * @param nodesMap Map of IDs to nodes
     */
    public static void readNode(DataInputStream dis, HashMap nodesMap) throws IOException {
        int magic = dis.readInt();
        if(magic != MAGIC)
            throw new IOException("Invalid magic "+magic+" should be "+MAGIC);
        int ver = dis.readInt();
        if(ver != 1)
            throw new IOException("Unrecognized version "+ver);
        long id = dis.readLong();
        if(Main.VERBOSE_LOAD) System.err.println("Read node ID "+id);
        Long iid = new Long(id);
        Node n = (Node)nodesMap.get(iid);
        if(n == null) throw new IOException("No node for "+id);
        n.readRestFrom(dis, nodesMap);
        if(Main.VERBOSE_LOAD)
            System.err.println("Read ID: "+id);
    }
    
    /**
     * Read most fields from a DataInputStream.
     * @param dis
     */
    private void readRestFrom(DataInputStream dis, HashMap nodesMap) throws IOException {
        connections = new OpenConnectionManager(this, dis, nodesMap);
        BigInteger bi = HexUtil.readBigInteger(dis);
        announcementKey = new Key(bi);
        if(Main.VERBOSE_LOAD)
            System.err.println("Announcement Key: "+announcementKey);
        hits = dis.readLong();
        if(hits < 0) throw new IOException("Invalid hits: "+hits);
        successes = dis.readLong();
        if(successes < 0) throw new IOException("Invalid successes: "+successes);
        if(successes > hits) throw new IOException("Successes ("+successes+") > hits ("+hits+")");
        if(Main.VERBOSE_LOAD)
            System.err.println("Hits: "+hits+", successes: "+successes);
        MAX_STORED_KEYS = dis.readInt();
        if(MAX_STORED_KEYS != Main.MAX_STORED_KEYS)
            throw new IOException("MAX_STORED_KEYS inconsistent");
        if(Main.VERBOSE_LOAD)
            System.err.println("Max stored keys: "+MAX_STORED_KEYS);
        myEstimator = newEstimator(dis);
        if(Node.DO_OFFLINE_RT) {
            if(Main.VERBOSE_LOAD)
                System.err.println("Loading offline RT...");
            int offlineRTSize = dis.readInt();
            if(offlineRTSize > 2*RT_MAX_NODES)
                throw new IOException("Offline RT too big: "+offlineRTSize+", RT is "+RT_MAX_NODES);
            // First on the list is the MRU, it progresses to LRU.
            // So just read and insert.
            HashSet readNodes = new HashSet();
            for(int i=0;i<offlineRTSize;i++) {
                long id = dis.readLong();
                Long iid = new Long(id);
                Node n = (Node) nodesMap.get(iid);
                if(n == null) throw new IOException("Invalid ID: "+id);
                if(readNodes.contains(iid)) throw new IOException("ID read twice reading offline RT: "+id);
                if(Main.VERBOSE_LOAD)
                    System.err.println("Offline RT ID: "+id);
                Peer p = connections.getPeer(n);
                if(p == null) p = new Peer(this, n, dis);
                peersLRUOffline.push(p);
                offlinePeers.put(n, p);
           }
        }
        recentRequests = new RecentRequestHistory(KEEP_REQUESTS, null, dis, Main.REQUEST_HTL, 0);
        if(Main.VERBOSE_LOAD)
            recentRequests.dumpSimple(System.err);
        storedKeys = new LRUQueue();
        int storedKeysCount = dis.readInt();
        if(storedKeysCount > MAX_STORED_KEYS) throw new IOException("Too many stored keys: "+storedKeysCount+" of "+MAX_STORED_KEYS);
        for(int i=0;i<storedKeysCount;i++) {
            BigInteger b = HexUtil.readBigInteger(dis);
            Key k = new Key(b);
            storedKeys.push(k);
        }
    }

    /**
     * @return
     */
    public double pSuccess() {
        return ((double) successes) / (double) hits;
    }

    public static void writeStatic(DataOutputStream dos) throws IOException {
        dos.writeLong(Node.connectionsTried);
        dos.writeLong(Node.forceConnectionsTried);
        dos.writeLong(Node.connectionsAdded);
        dos.writeLong(Node.connectionsPassedEstimators);
        dos.writeLong(Node.idCounter);
        dos.writeLong(Node.routedByEstimators);
        dos.writeLong(Node.routedByNewbie);
        dos.writeLong(Node.routedRandom);
    }

    public static void readStatic(DataInputStream dis) throws IOException {
        connectionsTried = dis.readLong();
        if(connectionsTried < 0) throw new IOException("Invalid connections tried: "+connectionsTried);
        forceConnectionsTried = dis.readLong();
        if(forceConnectionsTried < 0) throw new IOException("Invalid forceConnectionsTried: "+forceConnectionsTried);
        if(forceConnectionsTried > connectionsTried) throw new IOException("forceConnectionsTried ("+forceConnectionsTried+") > connectionsTried ("+connectionsTried+")");
        connectionsAdded = dis.readLong();
        if(connectionsAdded < 0) throw new IOException("Invalid connections added: "+connectionsAdded);
        if(connectionsAdded > connectionsTried) throw new IOException("Connections added ("+connectionsAdded+") > connections tried ("+connectionsTried+")");
        connectionsPassedEstimators = dis.readLong();
        if(connectionsPassedEstimators < 0) throw new IOException("Invalid connections passed estimators: "+connectionsPassedEstimators);
        if(connectionsPassedEstimators > connectionsTried) throw new IOException("Connections passed estimators ("+connectionsPassedEstimators+") > connections tried ("+connectionsTried+")");
        idCounter = dis.readLong();
        if(idCounter < 0) throw new IOException("Invalid id counter: "+idCounter);
        routedByEstimators = dis.readLong();
        if(routedByEstimators < 0) throw new IOException("Invalid routed by estimators: "+routedByEstimators);
        routedByNewbie = dis.readLong();
        if(routedByNewbie < 0) throw new IOException("Invalid routed by newbie: "+routedByNewbie);
        routedRandom = dis.readLong();
        if(routedRandom < 0) throw new IOException("Invalid routed random: "+routedRandom);
        if(Main.VERBOSE_LOAD) {
            System.err.println("Connections tried: "+connectionsTried+", forced: "+forceConnectionsTried+", added: "+connectionsAdded+", passed estimators: "+connectionsPassedEstimators);
            System.err.println("ID counter: "+idCounter);
            System.err.println("Routings: by estimators: "+routedByEstimators+", by newbie: "+routedByNewbie+", random: "+routedRandom);
        }
    }
}
