package freenet.node.simulator.whackysim;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Random;
import java.util.Vector;

import freenet.Key;
import freenet.keys.CHK;
import freenet.node.rt.BootstrappingDecayingRunningAverageFactory;
import freenet.node.rt.EdgeKludgingBinaryRunningAverage;
import freenet.node.rt.EdgeKludgingWrapperRunningAverageFactory;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.node.rt.RunningAverageFactory;
import freenet.node.rt.SimpleRunningAverage;
import freenet.node.rt.SlidingBucketsKeyspaceEstimatorFactory;

/**
 * @author amphibian
 * 
 * Main driver class for WhackySim.
 * 
 * Terminology:
 * "triangles" property: If A is connected to B and B is connected to C, then
 * it is likely that A is connected to C.
 * 
 * If a graph has this property, then it has small diameter. That does not mean
 * that you can FIND that diameter.
 * 
 * WhackySim does the following:
 *
 * Set up a 1/d network.
 * 
 * Throw all the data away except for the connections.
 * 
 * Do random routing for a while and record average path lengths.
 * 
 * Do NGRouting, with fixed links, for a long period.
 * 
 * Hypothesis: NGRouting will approach the performance of the first LRU network.
 * If this is true, we have cracked the harvesting problem!!
 * 
 * Note that we are assuming unidirectional connections here for simplicity.
 * 
 * Should we use HTL on fetches? What HTL should inserts use?
 */
public class Main {


    static class PSuccessEntry implements Comparable {
        double psuccess;
        Node node;

        PSuccessEntry(double p, Node n) {
            psuccess = p;
            node = n;
        }
        
        public int compareTo(Object o) {
            PSuccessEntry p = (PSuccessEntry) o;
            if(p.psuccess < psuccess) return -1;
            else if(p.psuccess > psuccess) return 1;
            if(p.node.totalHits > node.totalHits) return 1;
            if(p.node.totalHits < node.totalHits) return -1;
            return 0;
        }
        
        public String toString() {
            return Double.toString(psuccess) + " ("+
            	node.totalHits+")";
        }
    }
    
    // This is the maximum
    public final static int NUMBER_OF_NODES = 10000;
    // Initial size of mesh
    public final static int INITIAL_NODES = 200;
    public final static int CONNECTIONS = 10;
    public static int INSERT_HTL = 20;
    private static int FETCH_HTL = 20;
    public static RunningAverageFactory rafb;
    public static RunningAverageFactory raf;
    static final boolean DO_QUEUE_GROWTH = false;
    static final boolean DO_SILLY_GROWTH = true;
    static final boolean DO_BAD_LINKS = false;
    static final boolean GREEDY_ROUTING_INCREASING_ONLY = false;
    static final boolean DO_VARIABLE_HTL = false;
    static final double TARGET_PSUCCESS = 0.80;
    static final boolean DO_LOG_REQUESTS_PER_CYCLE = false;
    static final boolean DO_LOGSQUARED_REQUESTS_PER_CYCLE = false;
    static final boolean DO_LOGCUBED_REQUESTS_PER_CYCLE = false;
    static final boolean DO_DUMP = true;
    static final int BASE_CYCLE_LENGTH = 10000; // number of requests in the first cycle
    private static Node[] nodes;
    private static Random r = new Random();
    private static KeyCollector kc = new KeyCollector(INITIAL_NODES*Node.MAX_DATASTORE_SIZE/10, r);
    private static double lastRequestSuccessRatio = 0;
    private static long startTime = System.currentTimeMillis();
    
    static Vector activeNodes = new Vector();
    static HashSet borderNodes = new HashSet();
    static LinkedList borderQueue = new LinkedList();
    static int cycleCounter;
    
    /** Run the simulation */
    public static void main(String[] args) {
        
        DateFormat df;
        df = new SimpleDateFormat();
        System.out.println(df.format(new Date()));
        
        // Create 1000 nodes, each has an identity number (a long int).
        // Create an explicit 1/D structure from the identity numbers.
        // Each node has 10 links.
        // Verify the triangle property.
        // Verify that it does in fact work well with greedy routing, by doing some greedy routing.
        // Forget the numbers (implicit step - just don't use them).
        // Run NGR.
        
        // First create a KEF
        int MAX_REPORTS = 50;
        raf = new BootstrappingDecayingRunningAverageFactory(0, Double.MAX_VALUE, MAX_REPORTS);
        rafb = new EdgeKludgingWrapperRunningAverageFactory(raf, MAX_REPORTS);
        //rafb = EdgeKludgingBinaryRunningAverage.factory(500, 0);
        KeyspaceEstimatorFactory kef = 
            new SlidingBucketsKeyspaceEstimatorFactory(raf, rafb, raf, /*Node.BUCKET_COUNT*/16, /*Node.MOVEMENT_FACTOR*/0.05, /*Node.DO_SMOOTHING*/false, /*Node.DO_FAST_ESTIMATORS*/true);
        
        // Create a RandSource
        //RandomSource rand = new Yarrow((new File("/dev/urandom").exists() ? "/dev/urandom" : "prng.seed"), "SHA1", "Rijndael",true);
        //r = new Random(/*rand.nextLong()*/);
        
        nodes = new Node[NUMBER_OF_NODES];
        
        // Create network of a LOT of nodes, all inactive
        
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            // Create a node
            Node n = new Node(i, kef, r);
            nodes[i] = n;
        }

        //calculateGreedyRoutingAverageDistribution();
        
        setUpExplicitOneOverDStructureHobxRandom(false);
        
        runGreedyRouting(false, null);
        
        System.out.println("Set up structure, activating initial nodes...");
//        
//        
        // Activate a few nodes to start with
        activateInitialNodes();
        
        dumpActiveStatus();
        
        System.out.println("Verifying source network...");
        
        System.out.println("Running some requests...");
        
        int i = 0;
        int j = 0;
        runNGR();
        while(i < 20 && j < 1000) {
            if(lastRequestSuccessRatio < TARGET_PSUCCESS) i = 0;
            else i++;
            j++;
            System.out.println("Consecutive >"+TARGET_PSUCCESS+": "+i);
////        for(int i=0;i<20;i++) {
////        //for(int i=0;i<10;i++)
            runNGR();
        }
//        
        System.out.println("Growing the network...");
//        
        while(activeNodes.size() < nodes.length) {
            // Add a node
            activateOneNode();
            
            //runGreedyRouting(true);
            
            runNGR();
        }
        System.err.println("ADDED ALL NODES.");
        while(true) {
            runNGR();
        }
    }

	/**
     * 
     */
    private static void calculateGreedyRoutingAverageDistribution() {
        int[] totalGreedySteps = new int[NUMBER_OF_NODES];
        int cycles = 0;
        while(true) {
            setUpExplicitOneOverDStructureHobxRandom(true);
            //setUpExplicitOneOverDStructure(r);
            // Run a load of requests
            cycles++;
            runGreedyRouting(false, totalGreedySteps);
            dumpSteps(cycles, totalGreedySteps);
            clearStructure();
        }
    }

    /**
     * 
     */
    private static void clearStructure() {
        for(int i=0;i<nodes.length;i++) {
            nodes[i].clearStructure();
        }
    }

    /**
     * @param cycles
     * @param totalGreedySteps
     */
    private static void dumpSteps(int cycles, int[] totalGreedySteps) {
        File f = new File("cycle-"+cycles);
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(f);
            PrintWriter pw = new PrintWriter(fos);
            for(int i=0;i<totalGreedySteps.length;i++) {
                pw.println(""+i+", "+((double)totalGreedySteps[i])/(cycles));
            }
            System.err.println("Dumped cycle "+cycles+" to "+f);
            pw.close();
        } catch (FileNotFoundException e) {
            System.err.println("Cannot dump steps");
        }
        f = new File("mirrorcycle-"+cycles);
        try {
            fos = new FileOutputStream(f);
            PrintWriter pw = new PrintWriter(fos);
            for(int i=0;i<totalGreedySteps.length;i++) {
                int x = i;
                if(x > NUMBER_OF_NODES/2) x = NUMBER_OF_NODES - x;
                pw.println(""+i+", "+((double)totalGreedySteps[x])/(cycles));
            }
            System.err.println("Dumped cycle "+cycles+" to "+f);
            pw.close();
        } catch (FileNotFoundException e) {
            System.err.println("Cannot dump steps");
        }
    }

    /**
     * Dump the connectivity status of all active nodes.
     */
    private static void dumpActiveStatus() {
        int totalConns = 0;
        for(int i=0;i<activeNodes.size();i++) {
            Node n = (Node) activeNodes.get(i);
            System.out.println("Node "+n.myValue);
            totalConns+=n.dumpActiveConns();
        }
        System.out.println("Average conns per node: "+((double)totalConns)/activeNodes.size());
    }

    /**
     * Activate a single node.
     */
    private static void activateOneNode() {
        if(!DO_SILLY_GROWTH) {
        if(Main.DO_QUEUE_GROWTH) {
            while(true) {
                // Pop bottom node
                Node n = (Node) borderQueue.removeFirst();
                if(n.active) continue;
                n.activate();
                break;
            }
        } else {
            // Pick a random border node and activate it
            Node[] borders = (Node[]) borderNodes.toArray(new Node[borderNodes.size()]);
            Node randomNode = borders[r.nextInt(borders.length)];
            if(randomNode.active) throw new IllegalStateException("Node active while activating one node");
            randomNode.activate();
        }
        } else {
            lastActivated++;
            nodes[lastActivated].activate();
        }
        if(DO_VARIABLE_HTL) {
            double l = Math.log(activeNodes.size())/Math.log(2.0);
            INSERT_HTL = FETCH_HTL = (int) l;
                //(int) (l*l/4);
            System.err.println("Setting HTL to "+INSERT_HTL);
        }
    }

    static int lastActivated = 0;
    
    /**
     * Activate INITIAL_NODES random nodes. They must be contiguous.
     */
    private static void activateInitialNodes() {
        if(DO_SILLY_GROWTH)
            nodes[0].activate();
        else {
        Node starter = randomNode();
        starter.activate();
        }
        // Find INITIAL_NODES-1 connected nodes
        for(int i=1;i<INITIAL_NODES;i++)
            activateOneNode();
    }

    private static long cycleNumber = 0;

    static int currentCycles = 3;
    
    /**
     * Run NGRouting across the mesh.
     * @param r A Random.
     * @param nodes The nodes.
     */
    private static void runNGR() {
        cycleCounter++;
        // Do a bazillion requests.
        System.err.println("Nodes active: "+activeNodes.size());
        int keepKeys = (int)(activeNodes.size()*Node.MAX_DATASTORE_SIZE/20);
        if(kc.keepingKeys() != keepKeys) {
        	kc.setKeep(keepKeys);
        	System.out.println("Keeping "+keepKeys+" keys");
        }
        if(shouldDump()) try { 
            dumpAll();
        } catch (IOException e) {
            System.err.println("Could not dump: "+e);
            e.printStackTrace();
        }
        int nodeCount = activeNodes.size();
//        double log2 = Math.log(nodeCount)/Math.log(2.0);
//        int requests = (int) (nodeCount * CONNECTIONS * log2 * log2 / 4);
//        System.out.println("Running "+requests+" requests");
        
        // Run requests until we have the target psuccess over the last 5 cycles
        SimpleRunningAverage sra = new SimpleRunningAverage(5, 0.0);
        
        long totalFetchHops = 0;
        long totalFetches = 0;
        long firstTimeFetches = 0;
        long successfulFetches = 0;
        long successfulFirstTimeFetches = 0;
        long totalInserts = 0;
        long successfulInserts = 0;
        long totalFirstTimeFetchHops = 0;
        int miniCycles = 0;
        
//        for(int x=0;x<currentCycles;x++) {
//        while(sra.currentValue() < TARGET_PSUCCESS || sra.countReports() < 5) {
            miniCycles++;
//            totalFetchHops = 0;
//            totalFetches = 0;
//            firstTimeFetches = 0;
//            successfulFetches = 0;
//            successfulFirstTimeFetches = 0;
//            totalInserts = 0;
//            successfulInserts = 0;
//            totalFirstTimeFetchHops = 0;
        // Run one mini-cycle
        // Fixed length mini-cycles - if they are O(n), then we get O(n^2) overall...
           
        int requestsPerCycle;
        final int baseCycleLength = BASE_CYCLE_LENGTH;
        final double baseLog = Math.log(INITIAL_NODES);
        double l = Math.log(activeNodes.size());
        if(DO_LOG_REQUESTS_PER_CYCLE)
            requestsPerCycle = (int)(baseCycleLength * l / baseLog);
        else if(DO_LOGSQUARED_REQUESTS_PER_CYCLE)
            requestsPerCycle = (int) (baseCycleLength * l*l / (baseLog*baseLog));
        else if(DO_LOGCUBED_REQUESTS_PER_CYCLE)
            requestsPerCycle = (int) (baseCycleLength * l*l*l / (baseLog*baseLog*baseLog));
        else requestsPerCycle = baseCycleLength;
        for(int i=0;i<requestsPerCycle;i++) {
            // Insert a file
            // Then fetch it
            // FIXME: maybe we should do the insert-1-then-fetch-100 thing for realism?
            int fetchHops;
            Key key;
            long id;
            //if(i % 10 == 0) {
                key = CHK.randomKey(r);
                id = r.nextLong();
                totalInserts++;
                int res = randomActiveNode().runInsert(key, INSERT_HTL, id, r);
                if(res < 0) {
                    //System.err.println("Failed to insert");
                    if(!kc.hasKeys()) continue;
                } else {
                    //System.err.println("Successful insert");
                    successfulInserts++;
                    kc.add(key);
                }
            //}
            //for(int j=0;j<CONNECTIONS;j++) {
            KeyWithCounter kwc = kc.getRandomKey();
            boolean firstTime = kwc.getCount() == 0;
            if(firstTime) firstTimeFetches++;
            key = kwc.getKeyIncCounter();
            id = r.nextLong();
            fetchHops = randomActiveNode().outerRunRequest(key, FETCH_HTL, id, r);
//            if(fetchHops >= 0)
//                System.out.println("Fetched in "+fetchHops);
//            else
//                System.out.println("Failed to fetch");
            totalFetches++;
            if(fetchHops >= 0) {
                totalFetchHops += fetchHops;
                successfulFetches++;
                if(firstTime) {
                    successfulFirstTimeFetches++;
                    totalFirstTimeFetchHops += fetchHops;
                }
            }
            //}
        }
//        lastRequestSuccessRatio = ((double)successfulFetches)/((double)totalFetches);
//        double lastFirstTimeSuccessRatio = ((double)successfulFirstTimeFetches)/((double)firstTimeFetches);
//        
//        double lastWorstRatio = Math.min(lastRequestSuccessRatio, lastFirstTimeSuccessRatio);
//        
//        System.out.println("Cycle "+cycleNumber+" mini-cycle "+miniCycles+" -> avg: "+lastRequestSuccessRatio+" first-time: "+lastFirstTimeSuccessRatio+" worst: "+lastWorstRatio);
//
//        sra.report(lastWorstRatio);
//        }
        // Now verify the keys exist
        int vc = kc.countValidKeys();
        System.out.println("Keys: "+vc);
        int nulls = 0;
        int validKeys = 0;
        long totalCounts = 0;
        int maxCounts = 0;
        int minCounts = Integer.MAX_VALUE;
        int keysFetchable = 0;
        int duplicationMin = Integer.MAX_VALUE;
        int duplicationMax = 0;
        long duplicationTotal = 0;
        for(int i=0;i<vc;i++) {
            KeyWithCounter kwc = kc.get(i);
            if(kwc == null) {
                nulls++;
                continue;
            }
            validKeys++;
            int count = kwc.getCount();
            totalCounts+= count;
            if(maxCounts < count) maxCounts = count;
            if(minCounts > count) minCounts = count;
            Key k = kwc.getKeyDontIncCounter();
            // We have a valid key
            int countInStores = findInStores(k);
            if(countInStores > duplicationMax) duplicationMax = countInStores;
            if(countInStores < duplicationMin) duplicationMin = countInStores;
            if(countInStores > 0) keysFetchable++;
            duplicationTotal+=countInStores;
        }
        System.out.println("Nulls: "+nulls+", Valid: "+validKeys+", average access count: "+((double)totalCounts)/validKeys+
                ", max access count: "+maxCounts+", min access count: "+minCounts+", fetchable: "+keysFetchable+", duplication max: "+
                duplicationMax+", duplication min: "+duplicationMin+", duplication mean: "+((double)duplicationTotal)/validKeys);
        lastRequestSuccessRatio = ((double)successfulFetches)/((double)totalFetches);
        System.err.println("Cycle "+cycleNumber+": Average path length: "+((double)totalFetchHops)/((double)successfulFetches)+", Success probability: "+lastRequestSuccessRatio+
                ", inserts: "+(((double)successfulInserts)/((double)totalInserts))+", first-time success ratio: "+((double)successfulFirstTimeFetches)/((double)firstTimeFetches)+
                " ("+firstTimeFetches+") - first time path length "+((double)totalFirstTimeFetchHops)/successfulFirstTimeFetches+", mini-cycles: "+miniCycles+" - "+activeNodes.size()+" nodes, "+
                requestsPerCycle+" requests");
        double lastFirstTimeSuccessRatio = ((double)successfulFirstTimeFetches)/((double)firstTimeFetches);
        
        double lastWorstRatio = Math.min(lastRequestSuccessRatio, lastFirstTimeSuccessRatio);
        if(lastWorstRatio < TARGET_PSUCCESS) {
            currentCycles++;
        } else if(lastWorstRatio > TARGET_PSUCCESS) {
            if(currentCycles > 3)
                currentCycles--;
        }
        dumpLoadDistribution();
        cycleNumber++;
    }

    /**
     * Dump all nodes
     */
    private static void dumpAll() throws IOException {
        String filename = getDumpFilename();
        File dumpTo = new File(filename);
        System.err.println("Dumping to "+dumpTo.getPath());
        FileOutputStream fos = new FileOutputStream(dumpTo);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        PrintWriter pw = new PrintWriter(bos);
        pw.println("Node count: "+activeNodes.size());
        for(int i=0;i<activeNodes.size();i++) {
            ((Node)activeNodes.get(i)).dump(pw, filename);
        }
        bos.close();
        System.err.println("Dumped to "+dumpTo.getPath());
    }

    private static String getDumpFilename() {
    	return "dump-"+startTime+"-"+activeNodes.size();
    }

    /**
     * @return True if we should dump all
     */
    private static boolean shouldDump() {
        if(!DO_DUMP) return false;
        int x = cycleCounter - 20;
        if(x % 250 == 0) return true;
        if(x == 201) return true;
        if(x == 250) return true;
        if(x == 300) return true;
        if(x == 400) return true;
        return false;
    }

    private static int findInStores(Key k) {
        int count=0;
        for(int i=0;i<activeNodes.size();i++) {
            Node n = (Node) activeNodes.get(i);
            if(n.datastore.contains(k)) count++;
        }
        return count;
    }

    /**
     * @return A random, active node.
     */
    private static Node randomActiveNode() {
        return (Node) activeNodes.get(r.nextInt(activeNodes.size()));
    }

    static int x = 0;
    static long[] fullLoad = new long[NUMBER_OF_NODES];
    static PSuccessEntry[] fullPSuccess = new PSuccessEntry[NUMBER_OF_NODES];
    private static void dumpLoadDistribution() {
        long totalSinceLastTime = 0;
        long max = 0;
        long min = Long.MAX_VALUE;
        int maxStore = 0;
        int minStore = Integer.MAX_VALUE;
        double maxPSuccess = 0;
        double minPSuccess = Double.MAX_VALUE;
        long totalStore = 0;
        for(int i=0;i<activeNodes.size();i++) {
            Node n = (Node)activeNodes.get(i);
            long sinceLastTime = n.totalHits - n.lastCycleHits;
            long successesSinceLastTime = n.totalSuccesses - n.lastCycleSuccesses;
            double psuccess = ((double) successesSinceLastTime) / ((double) sinceLastTime);
            if(psuccess > maxPSuccess) maxPSuccess = psuccess;
            if(psuccess < minPSuccess) minPSuccess = psuccess;
            fullLoad[i] = sinceLastTime;
            fullPSuccess[i] = new PSuccessEntry(psuccess, n);
            n.lastCycleHits = n.totalHits;
            n.lastCycleSuccesses = n.totalSuccesses;
            totalSinceLastTime += sinceLastTime;
            if(max < sinceLastTime) max = sinceLastTime;
            if(min > sinceLastTime) min = sinceLastTime;
            int store = n.datastore.size();
            totalStore += store;
            if(store > maxStore) maxStore = store;
            if(store < minStore) minStore = store;
        }
        System.out.println("Load: max: "+max+", min: "+min+", avg: "+(totalSinceLastTime/activeNodes.size()));
        System.out.println("Stores: max: "+maxStore+", min: "+minStore+", avg: "+((double)totalStore)/activeNodes.size());
        System.out.println("PSuccess: max: "+max+", min: "+min+", avg: "+(totalSinceLastTime/activeNodes.size()));
        x++;
        if(x % 16 == 0) {
            java.util.Arrays.sort(fullLoad, 0, activeNodes.size());
            System.out.print("Full load: ");
            for(int i=0;i<activeNodes.size();i++) {
                System.out.print(fullLoad[i]);
                System.out.print(' ');
            }
            System.out.println();
            java.util.Arrays.sort(fullPSuccess, 0, activeNodes.size());
            System.out.print("Full psuccess: ");
            for(int i=0;i<activeNodes.size();i++) {
                System.out.print(fullPSuccess[i]);
                System.out.print(' ');
            }
        }
    }

    private static Node randomNode() {
        return nodes[r.nextInt(nodes.length)];
    }

    private static double randomKey() {
        return r.nextDouble() * Key.KEYSPACE_SIZE_DOUBLE;
    }

    /**
     * Do some greedy routing, track the average length of requests.
     */
    private static void runGreedyRouting(boolean activeOnly, int[] totalGreedySteps) {
        long totalRequests = 0;
        long totalHops = 0;
//        for(int i=0;i<100000;i++) {
//            Node startNode = 
//                activeOnly ? randomActiveNode() : randomNode();
//            Node targetNode;
//            do {
//                targetNode = 
//                    activeOnly ? randomActiveNode() : randomNode();
//            } while (targetNode == startNode);
//            int hops = startNode.greedyRoute(targetNode.myValue, r, activeOnly);
//            totalRequests++;
//            totalHops += hops;
//        }
//        System.err.println("Average hops for greedy routing: "+(((double)totalHops)/((double)totalRequests)));
        if(!activeOnly) {
            int rand = NUMBER_OF_NODES/2;//r.nextInt(Main.NUMBER_OF_NODES);
            //System.out.println("From node "+rand);
            Node n = nodes[rand];
            for(int i=0;i<Main.NUMBER_OF_NODES;i++) {
                if(i == rand) continue;
                //System.out.println("Routing from "+rand+" to "+i);
                int x = n.greedyRoute(i, r, false);
                //System.out.println(""+rand+" -> "+i+": "+x);
                if(totalGreedySteps != null)
                    totalGreedySteps[i] += x;
            }
        }
    }

    private static void setUpExplicitOneOverDStructureHobxForward() {
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            Node n1 = nodes[i];
            Node prev = nodes[wrap(i-1)];
            Node next = nodes[wrap(i+1)];
            prev.connect(n1, false);
            n1.connect(prev, false);
            next.connect(n1, false);
            n1.connect(next, false);
        }
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            Node n1 = nodes[i];
            System.out.print("Setting up conns for "+i+" has "+n1.countConnections()+"      \r");
            int count = 0;
            // Only connect forward. Previous nodes have already been filled up.
            while(n1.countConnections() < CONNECTIONS && count < NUMBER_OF_NODES*10) {
                count++;
                int distance;
                if(DO_BAD_LINKS)
                    distance = r.nextInt(NUMBER_OF_NODES-1)+1;
                else
                    distance = (int)Math.pow(NUMBER_OF_NODES, r.nextDouble());
                int nodeToConnectTo = i + distance;
                if(nodeToConnectTo >= NUMBER_OF_NODES) continue;
                Node target = nodes[nodeToConnectTo];
//                System.out.println("Node "+i+" -> "+nodeToConnectTo+": I have "+
//                        n1.countConnections()+", he has "+target.countConnections());
                if(n1.isConnected(target)) continue;
                if(Main.GREEDY_ROUTING_INCREASING_ONLY) {
                    n1.connect(target, false);
                } else {
                    if(target.countConnections() < CONNECTIONS) {
                        n1.connect(target, false);
                        target.connect(n1, false);
                    }
                }
            }
        }
        System.out.println();
    }

    private static void setUpExplicitOneOverDStructureHobxRandom(boolean noEstimators) {
        // Set up the structure randomly
        
        Node[] nodesWithLessThanMaxConnections = nodes;

        // Connect each to its previous and next node
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            Node n1 = nodes[i];
            Node prev = nodes[wrap(i-1)];
            Node next = nodes[wrap(i+1)];
            prev.connect(n1, noEstimators);
            n1.connect(prev, noEstimators);
            next.connect(n1, noEstimators);
            n1.connect(next, noEstimators);
        }
        
        while(nodesWithLessThanMaxConnections.length > 0) {
            int changes = 0;
            for(int i=0;i<1000;i++) {
                Node n1 = nodesWithLessThanMaxConnections[r.nextInt(nodesWithLessThanMaxConnections.length)];
                int start = n1.myValue;
                if(n1.countConnections() == CONNECTIONS) continue;
                if(n1.countConnections() > CONNECTIONS) {
                    System.err.println("Too many connections on node "+n1.myValue);
                    continue;
                }
                int distance;
                if(DO_BAD_LINKS)
                    distance = r.nextInt(NUMBER_OF_NODES/2-1)+1;
                else
                    distance = (int) Math.floor(Math.pow(NUMBER_OF_NODES/2-1, r.nextDouble()))+1;
                if(distance == 0) continue;
                if(r.nextBoolean()) distance = -distance;
                int nodeToConnectTo = wrap(start + distance);
                if(nodeToConnectTo == n1.myValue) continue;
                Node target = nodes[nodeToConnectTo];
                if(target.countConnections() >= CONNECTIONS) continue;
                if(n1.isConnected(target)) continue;
                n1.connect(target, noEstimators);
                target.connect(n1, noEstimators);
                changes++;
            }
            if(changes == 0) {
                System.err.println("Exiting initialization: 0 changes this cycle, "+nodesWithLessThanMaxConnections.length+" nodes left");
                break;
            }
            nodesWithLessThanMaxConnections = updateNodesWithLessThanMaxConnections(nodesWithLessThanMaxConnections);
            System.out.println("Init cycle end, nodes remaining: "+nodesWithLessThanMaxConnections.length);
        }
        
        System.out.println();
    }

    /**
     * @param nodes
     * @return
     */
    private static Node[] updateNodesWithLessThanMaxConnections(Node[] nodes) {
        int x=0;
        for(int i=0;i<nodes.length;i++) {
            if(nodes[i].countConnections() < CONNECTIONS) x++;
        }
        Node[] n = new Node[x];
        int y=0;
        for(int i=0;i<nodes.length;i++) {
            int conns = nodes[i].countConnections();
            if(conns == CONNECTIONS) continue;
            if(conns > CONNECTIONS) {
                System.err.println("Too many connections on node: "+nodes[i].myValue);
            }
            n[y] = nodes[i];
            y++;
        }
        return n;
    }

    private static int wrap(int i) {
        if(i >= NUMBER_OF_NODES) i-= NUMBER_OF_NODES;
        if(i < 0) i += NUMBER_OF_NODES;
        return i;
    }

    /**
     * Calculate the shortest wrapped distance between two nodes'
     * numbers for purposes of e.g. greedy routing.
     */
    public static int wrapDistance(int node1, int node2) {
        if(node1 > node2) {
            int n = node2;
            node2 = node1;
            node1 = n;
        }
        // Now they are the right way around
        int straightDist = node2 - node1;
        int altDist = node1 + NUMBER_OF_NODES - node2;
        if(altDist > straightDist) return straightDist;
        else return altDist;
    }

    /**
     * Register a border node.
     */
    public static void registerBorderNode(Node n) {
        if(n.active) throw new IllegalStateException("Node was active registering border node");
        if(borderNodes.contains(n)) return;
        borderNodes.add(n);
        borderQueue.addLast(n);
    }

    /**
     * Deregister a border node.
     */
    public static void deregisterBorderNode(Node node) {
        if(!node.active) throw new IllegalStateException("Node was inactive unregistering border node");
        borderNodes.remove(node);
    }

    public static void registerActive(Node node) {
        activeNodes.add(node);
    }
    
//    /**
//     * 
//     */
//    private static void setUpExplicitOneOverDStructure(Random r, Node[] nodes) {
//        // Set up the 1/d structure
//        double max = ((double)Long.MAX_VALUE)*2;
//        for(int i=0;i<1000;i++) {
//            System.out.println("Setting up node: "+i);
//            for(int j=0;j<1000;j++) {
//                if(i == j) continue;
//                Node n1 = nodes[i];
//                Node n2 = nodes[j];
//                // Find the wrapped distance between the two nodes.
//                double val1 = n1.value();
//                double val2 = n2.value();
//                if(val2 < val1) {
//                    // Switch them round
//                    double temp = val2;
//                    val2 = val1;
//                    val1 = val2;
//                }
//                // val2 > val1
//                double dist1 = val2 - val1; // straight distance
//                double dist2 = val1 + max - val2;
//                double dist = dist1;
//                if(dist > dist2) dist = dist2;
//                // Now scale it.
//                // Because of wrap around, the result of this will be 0 <= scaledDist <= 0.5.
//                double scaledDist = dist / max;
//                if(scaledDist > 0.5) {
//                    throw new IllegalStateException("WTF? scaledDist = "+scaledDist);
//                }
//                
//                // Force 1/d property
//                if(r.nextDouble() > 1.0 / scaledDist) {
//                    n1.connect(n2);
//                }
//            }
//        }
//    }
//    
}
