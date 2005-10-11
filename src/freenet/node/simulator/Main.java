package freenet.node.simulator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.Vector;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import freenet.Core;
import freenet.Key;
import freenet.KeyException;
import freenet.keys.CHK;
import freenet.node.rt.BootstrappingDecayingRunningAverageFactory;
import freenet.node.rt.EdgeKludgingBinaryRunningAverage;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.node.rt.RunningAverageFactory;
import freenet.node.rt.SimpleBinaryRunningAverage;
import freenet.node.rt.SlidingBucketsKeyspaceEstimatorFactory;
import freenet.support.Logger;

public class Main {

    /**
     * A Key collection in a circular buffer.
     */
    static class LimitedKeyCollection {

        Key[] keys;
        int ptr;
        int totalKeysAdded;
        
        public String toString() {
            return super.toString()+": "+keys.length+" keys, ptr="+ptr+", total added: "+totalKeysAdded;
        }
        
        /**
         * 
         * @param capacity The maximum ammount of keys to store.  
         */
        public LimitedKeyCollection(int capacity) {
            keys = new Key[capacity];
            ptr = 0;
            totalKeysAdded = 0;
        }

        /**
         * Add a key to the circular buffer. If the buffer is full, this
         * will result in a previous key being overwritten.
         * @param k
         */
        public void add(Key k) {
            keys[ptr] = k;
            ptr++;
            if(ptr == keys.length) ptr = 0;
            totalKeysAdded++;
        }

        public int size() {
            return Math.min(totalKeysAdded, keys.length);
        }

        public Key get(int i) {
            return keys[i];
        }

        /**
         * Extend the size of the key collection.
         * @param i The new size of the array.
         * @throws IllegalArgumentException if size is smaller than the current size.
         */
        public void extend(int i) {
            if(i < keys.length) throw new IllegalArgumentException();
            //System.err.println("Expanding from "+this+", i="+i);
            Key[] nkeys = new Key[i];
            System.arraycopy(keys, 0, nkeys, 0, keys.length);
            // HACK
            totalKeysAdded = Math.min(keys.length, totalKeysAdded);
            ptr = totalKeysAdded;
            keys = nkeys;
            //System.err.println("Expanded to "+this);
        }
    }
    static int INITIAL_NODES = 400;
    static int INSERT_HTL = 10;
    static int REQUEST_HTL = 10;
    static int MAX_STORED_KEYS = 100;
    private static boolean FULLY_CONNECTED = false;
    /** The number of nodes to use (and RT size) if simulating fully-connected */
    private static int FULLY_CONNECTED_NODES = 100;
    /** The number of nodes to use if simulating non-fully-connected */
    private static int NFC_NODES = 400;
    /** The size of each node's routing table if simulating non-fully-connected */
    private static int NFC_RT_NODES = 25;
    /** Whether to dump full status to stdout every so often */
    private static boolean DO_REGULAR_DUMPS = false;
    /** The fraction of the total theoretical capacity to fetch from */
    private static double OVERRIDE_FETCH_FRACTION = -1;
    /** Filename to re-load from, if any */
    private static String LOAD_FILENAME = null;
    /** Whether to dump contents to output while loading */
    static boolean VERBOSE_LOAD = true;

    private static void append(StringBuffer sb, char c, String s) {
        if(sb.length() == 0)
            sb.append(s);
        else
            sb.append(c).append(s);
    }

    /** Options string, separated by c, major options separated by maj 
     */
    private static String getOptionsString(char c, char maj) {
        if(File.separatorChar == '\\')
            return "sillyWindowsPathLimits";
        StringBuffer sb = new StringBuffer(200);
        if(FULLY_CONNECTED) {
            append(sb,maj,"fullyconnected");
            append(sb,maj,"fcnodes="+FULLY_CONNECTED_NODES);
        } else {
            append(sb,maj,"nonfullyconnected");
            append(sb,maj,"nfcnodes="+NFC_NODES);
            append(sb,maj,"nfcrt="+NFC_RT_NODES);
        }
        append(sb,maj,"htl="+INSERT_HTL);
        append(sb,maj,"storesize="+MAX_STORED_KEYS);
        append(sb,c,"fetchfrac="+Main.OVERRIDE_FETCH_FRACTION);
        char next = maj;
        if(Node.DO_NEWBIEROUTING) { append(sb,next,"newbie"); next = c; }
        if(Node.DO_CLASSIC_PCACHING) { append(sb,next,"pcaching"); next = c; }
        if(Node.DO_PREFERENCE) { append(sb,next,"probref"); next = c; }
        if(Node.USE_THREE_ESTIMATORS) { append(sb,next,"longestimators"); next = c; }
        if(Node.DO_SMOOTHING) { append(sb,next,"smoothing"); next = c; }
        if(Node.DO_RANDOM) { append(sb,next,"random"); next = c; }
        if(Node.DO_FAST_ESTIMATORS) { append(sb,next,"fastestimators"); next = c; }
        if(Node.DO_ANNOUNCEMENTS) { append(sb,next,"announce"); next = c; }
        if(Node.DO_ALT_ANNOUNCEMENTS) { append(sb,next,"altannounce"); next = c; }
        if(Node.DO_OFFLINE_RT) { append(sb,next,"offlinert"); next = c; }
        if(Node.PROBE_BY_INEXPERIENCE) { append(sb,next,"probeinexperience"); next = c; }
        if(Node.RANDOMIZE_WHEN_EQUAL) { append(sb,next,"randomizewhenequal"); next = c; }
        if(Node.PROBE_ONLY_WHEN_NECESSARY) { append(sb,next,"sparseprobe"); next = c; }
        if(Node.DO_ESTIMATOR_PASSING) { append(sb,next,"estimatorpassing"); next = c; }
        if(Node.DO_LOW_CHURN) { append(sb,next,"lowchurn"); next = c; }
        else { append(sb,next,"highchurn"); next = c; }
        append(sb,maj,"movementfactor="+Node.MOVEMENT_FACTOR);
        append(sb,c,"buckets="+Node.BUCKET_COUNT);
        append(sb,c,"newbiehits="+Node.MIN_HITS_NEWBIE);
        if(Node.DO_BUGGY_BACKPASSING) append(sb,c,"backpassbug");
        else append(sb,c,"nobackpassbug");
        return sb.toString();
    }

    static final int MAGIC = 0x334cba56;
    
    static Random rng = new Random(Core.getRandSource().nextLong());
    
    static Stats globalStats = new Stats();
    
    static final long startTime = System.currentTimeMillis();
    static long reloadTime = -1;
    private static Simulator s;
    
    /**
     * The main program interface for the simulator.
     * A simple CLI for now.
     */
    public static void main(String[] args) throws KeyException, IOException {
        Core.setupErrorLogger(""); // "freenet.node.rt:debug";

        initArgs(args, false);
        s = new Simulator();
        RunningAverageFactory raf = new BootstrappingDecayingRunningAverageFactory(0, Double.MAX_VALUE, 10);
        RunningAverageFactory rafb = EdgeKludgingBinaryRunningAverage.factory(500, 0);
        KeyspaceEstimatorFactory kef;
        
        gcAndDumpMemoryUsage();
        
        if(Main.LOAD_FILENAME != null) {
            System.err.println("Loading from "+Main.LOAD_FILENAME);
            kef = load(raf, rafb);
            gcAndDumpMemoryUsage();
        } else
            kef = new SlidingBucketsKeyspaceEstimatorFactory(raf, rafb, raf, Node.BUCKET_COUNT, Node.MOVEMENT_FACTOR, Node.DO_SMOOTHING, Node.DO_FAST_ESTIMATORS);

        
        if(FULLY_CONNECTED) {
            runFullyConnected(kef, s);
        } else {
            runNonFullyConnected(kef, s);
        }
    }
    
    static void gcAndDumpMemoryUsage() {
//        System.gc();
//        System.runFinalization();
//        System.gc();
//        System.runFinalization();
        Runtime r = Runtime.getRuntime();
        long free = r.freeMemory();
        long total = r.totalMemory();
        long used = total - free;
        System.err.println("Memory usage: "+used+" ("+free+" free of "+total+")");
    }

    /**
     * Re-load current status from a disk file
     */
    private static KeyspaceEstimatorFactory load(RunningAverageFactory timeFactory,
            RunningAverageFactory probFactory) throws IOException {
        FileInputStream fis = new FileInputStream(Main.LOAD_FILENAME);
        GZIPInputStream gzis = new GZIPInputStream(fis);
        BufferedInputStream bis = new BufferedInputStream(gzis, 1024*1024);
        DataInputStream dis = new DataInputStream(bis);
        // Now read it
        int magic = dis.readInt();
        if(magic != MAGIC)
            throw new IOException("Invalid magic "+magic+" should be "+MAGIC);
        int version = dis.readInt();
        if(version != 1)
            throw new IOException("Unrecognized version "+version+" should be "+1);
        String options = dis.readUTF();
        if(VERBOSE_LOAD)
            System.err.println("Options: "+options);
        String[] optionsSplit = options.split(" ");
        if(VERBOSE_LOAD) {
            for(int i=0;i<optionsSplit.length;i++)
                System.err.println("Option "+i+": "+optionsSplit[i]);
        }
        initArgs(optionsSplit, true);
        KeyspaceEstimatorFactory kef = 
            new SlidingBucketsKeyspaceEstimatorFactory(timeFactory, probFactory, 
                    timeFactory, Node.BUCKET_COUNT, Node.MOVEMENT_FACTOR, Node.DO_SMOOTHING, Node.DO_FAST_ESTIMATORS);
        Main.globalStats = new Stats(dis);
        Main.gcAndDumpMemoryUsage();
        Main.s = new Simulator(dis, kef, Main.MAX_STORED_KEYS);
        Main.gcAndDumpMemoryUsage();
        last10KRequestsSuccess = new SimpleBinaryRunningAverage(REQUESTS_TO_AVERAGE, dis);
        Main.gcAndDumpMemoryUsage();
        Node.readStatic(dis);
        Main.gcAndDumpMemoryUsage();
        reloadTime = dis.readLong();
        return kef;
    }

    /**
     * Start a simulation of a non-fully connected network.
     * @param kef
     * @param s
     * @throws KeyException
     * @see #runRequestInsertTests(Simulator, long, int, int, LimitedKeyCollection)
     */
    private static void runNonFullyConnected(KeyspaceEstimatorFactory kef, Simulator s) throws KeyException, IOException {

        Main.INITIAL_NODES = Main.NFC_NODES;
        Node.RT_MAX_NODES = Main.NFC_RT_NODES;
        
        System.err.println("Running "+INITIAL_NODES+"x"+Node.RT_MAX_NODES+" not fully connected");

        if(Main.LOAD_FILENAME == null) {
        	createRandomlyConnectedNodes(kef, s);

        	if(Node.DO_ALT_ANNOUNCEMENTS)
        	    announce(s);
        	
        	// Now run the simulation
        	System.err.println("Created "+INITIAL_NODES+"x"+Node.RT_MAX_NODES+" randomly connected nodes");
        	Main.gcAndDumpMemoryUsage();
        }
        
        s.showConnStats(System.err);

        // Default. You should definitely set a value, this is not good for comparing different HTLs!!
        int totalCapacity = s.nodes.size() * MAX_STORED_KEYS;
        int maxFetchKeys = totalCapacity / (INSERT_HTL * 2);
        if(Main.OVERRIDE_FETCH_FRACTION > 0) maxFetchKeys = (int) (OVERRIDE_FETCH_FRACTION * totalCapacity);
        
        LimitedKeyCollection keysInserted = new LimitedKeyCollection(maxFetchKeys);
        Main.writeToDisk();
        runRequestInsertTests(s, -1, INSERT_HTL, REQUEST_HTL, keysInserted);        
//        // Create 25x25 fully connected network
//        initFullyConnected(kef, s, Node.RT_MAX_NODES);
//        // Announce
//        // FIXME: announce
////        if(Node.DO_ANNOUNCEMENTS || Node.DO_ALT_ANNOUNCEMENTS) {
////            announce(s);
////        }
//        // Run 100k requests, many inserts.
//        LimitedKeyCollection keysInserted = new LimitedKeyCollection((s.nodes.size() * MAX_STORED_KEYS) / (INSERT_HTL * 2));
//        runRequestInsertTests(s, 100*1000, 5, 5, keysInserted);
//        
//        // Now expand the network
//        // Add one node at a time
//        for(int i=Node.RT_MAX_NODES;i<Main.INITIAL_NODES;i++) {
//            addNodeToNetwork(kef, s); // includes announcement
//            keysInserted.extend((s.nodes.size() * MAX_STORED_KEYS) / (INSERT_HTL * 2));
//            runRequestInsertTests(s, 10*1000, INSERT_HTL, REQUEST_HTL, keysInserted);
//            System.err.println("Nodes: "+s.nodes.size());
//        }
//        System.err.println("Added all nodes");
//        runRequestInsertTests(s, -1, INSERT_HTL, REQUEST_HTL, keysInserted);
    }

    private static void createRandomlyConnectedNodes(KeyspaceEstimatorFactory kef, Simulator s) throws KeyException {
        // Create a field of INITIAL_NODES fully connected nodes
        for(int i=0;i<INITIAL_NODES;i++) {
            Node n = new Node(kef, MAX_STORED_KEYS);
            s.addNode(n);
        }

        Vector nodesCopy = new Vector(s.nodes);
        
        int count = nodesCopy.size();
        
        System.err.println("Made "+count+" nodes");
        
        Node[] randomizedNodes = new Node[count];

        int i;
        for(i=0;i<count;i++) {
            int j = Core.getRandSource().nextInt(nodesCopy.size());
            Node n = (Node) nodesCopy.remove(j);
            if(n == null) throw new NullPointerException();
            System.err.println("r["+i+"]="+n);
            randomizedNodes[i] = n;
        }
        
        System.err.println("Copied "+i+" nodes");
        
        for(i=0;i<randomizedNodes.length;i++) {
            Node n = randomizedNodes[i];
            if(n == null) {
                System.err.println("Null: "+i);
                throw new NullPointerException();
            }
            // Connect to a random 25 nodes
            for(int j=0;j<=Node.RT_MAX_NODES;j++) {
                while(true) {
                    int y = Core.getRandSource().nextInt(randomizedNodes.length);
                    Node n2 = randomizedNodes[y];
                    if(n2 == null) {
                        System.err.println("Null: "+y);
                        throw new NullPointerException();
                    }
                    if(n2 != n) {
                        //System.err.println("Connecting "+n+" to "+n2);
                        n.connect(n2, null, true);
                        break;
                    } // else continue
                }
            }
        }
        
        System.err.println("Randomly connected nodes");
    }

    public static void runFullyConnected(KeyspaceEstimatorFactory kef, Simulator s) throws KeyException, IOException {
        System.err.println("Running "+FULLY_CONNECTED_NODES+"x"+FULLY_CONNECTED_NODES+" fully connected");
        Node.RT_MAX_NODES = Main.FULLY_CONNECTED_NODES;
        Main.INITIAL_NODES = Main.FULLY_CONNECTED_NODES;
        if(Main.LOAD_FILENAME == null) {
            initFullyConnected(kef, s, Node.RT_MAX_NODES);
            if(Node.DO_ALT_ANNOUNCEMENTS)
                announce(s);
        }
        // Default. You should definitely set a value, this is not good for comparing different HTLs!!
        int totalCapacity = s.nodes.size() * MAX_STORED_KEYS;
        int maxFetchKeys = totalCapacity / (INSERT_HTL * 2);
        if(Main.OVERRIDE_FETCH_FRACTION > 0) maxFetchKeys = (int)(OVERRIDE_FETCH_FRACTION * totalCapacity);
        
        LimitedKeyCollection keysInserted = new LimitedKeyCollection(maxFetchKeys);
        Main.writeToDisk();
        runRequestInsertTests(s, -1, INSERT_HTL, REQUEST_HTL, keysInserted);        
    }
    
    private static void addNodeToNetwork(KeyspaceEstimatorFactory kef, Simulator s) throws KeyException {
        // Create new node
        Node n = new Node(kef, MAX_STORED_KEYS);
        s.addNode(n);
        if(Node.DO_ALT_ANNOUNCEMENTS) {
            while(true) {
                Node n2 = s.randomNode();
                if(n2 == n) continue;
                n2.announce(n, Core.getRandSource().nextLong(), Node.RT_MAX_NODES-1, CHK.randomKey(Core.getRandSource()), null);
                break;
            }
        } else {
            // Connect to 25 random nodes
            for(int i=0;i<Node.RT_MAX_NODES;i++) {
                while(true) {
                    Node n2 = s.randomNode();
                    if(n2 == n) continue;
                    if(n.isConnected(n2)) continue;
                    n.connect(n2, null, true);
                    break;
                }
            }
        }
        // FIXME: announce
        // Announcements are different with non-fully-connected networks...
        // Recommend use oskar's suggestion - one seednode, get initial connections via announcement.
    }

    static int REQUESTS_TO_AVERAGE = 20000;
    
    static SimpleBinaryRunningAverage last10KRequestsSuccess = new SimpleBinaryRunningAverage(REQUESTS_TO_AVERAGE, 0);

    static long lastWroteToDisk = -1;
    static long MIN_SERIALIZE_OUT_INTERVAL = 0; // 10 * 60 * 1000 /* 10 minutes */;
    static long serializeOutInterval = MIN_SERIALIZE_OUT_INTERVAL;
    
    /**
     * Insert & request simulation.
     * @param s
     * @param maxRequests
     * @param insertHTL
     * @param requestHTL
     * @param keysInserted
     * @throws KeyException
     */
    private static void runRequestInsertTests(Simulator s, long maxRequests, int insertHTL, int requestHTL, LimitedKeyCollection keysInserted) throws KeyException {
        if(lastWroteToDisk == -1) lastWroteToDisk = System.currentTimeMillis();
        System.err.println("Running tests...");
        //s.showStats(System.out);
        Key k;
        RequestStatus r = new RequestStatus();
        Stats localStats = new Stats();
        long lastTime = System.currentTimeMillis();
        long lastConnectionsAddedCount = Node.connectionsAdded;
        long lastConnectionsTriedCount = Node.connectionsTried;
        long lastForceConnectionsTriedCount = Node.forceConnectionsTried;
        long lastConnectionsPassed = Node.connectionsPassedEstimators;
        long startCount = globalStats.requests;
	long target;
	if(maxRequests < 0) target = -1;
	else target = globalStats.requests + maxRequests;
        int rounds = 0;
        while(true) {
            // localStats has been wiped at end of loop
            long now = System.currentTimeMillis();
            if(now - lastWroteToDisk > serializeOutInterval) {
                try {
                    writeToDisk();
                } catch (IOException e) {
                    Core.logger.log(Main.class, "Caught "+e+" trying to save progress to disk", e, Logger.ERROR);
                }
                lastWroteToDisk = now;
                if(DO_REGULAR_DUMPS)
                    s.dumpEverything(System.err, globalStats.requests);
            }
            if(target > 0 && globalStats.requests >= target) {
                System.err.println("Completed "+target+" ("+globalStats.requests+") requests");
                return;
            }
            for(int x=0;x<INITIAL_NODES;x++) {
                if(/*globalStats.inserts < (INITIAL_NODES * MAX_STORED_KEYS) / (INSERT_HTL * 2)*/true) {
                    // Now run an insert
                    localStats.inserts++;
                    k = CHK.randomKey(Core.getRandSource());
                    Insert i = new Insert(k, insertHTL);
                    Node n = s.randomNode();
                    // Now execute the insert
                    r.clear();
                    if(n.run(i, r, null)) {
                        if(Node.DO_MORE_LOGGING)
                            System.out.println("Completed: "+r.toString());
                        keysInserted.add(k);
                    } else {
                        if(Node.DO_MORE_LOGGING)
                            System.out.println("Rejected "+i);
                        localStats.rejectedInserts++;
                        continue;
                    }
                    if(Node.DO_MORE_LOGGING)
                        System.out.println("Inserted "+k);
                }
                //s.showStats(System.out);
                for(int y=0;y<Math.min(25,keysInserted.size());y++) {
                    int sz = keysInserted.size();
                    int idx = Main.rng.nextInt(sz);
                    k = (Key)keysInserted.get(idx);
                    if(k == null) {
                        System.err.println("No such index "+idx+" of "+sz+" on "+keysInserted);
                        throw new NullPointerException();
                    }
                    // Now run a request
                    localStats.requests++;
                    r.clear();
                    Request req = new Request(k, requestHTL);
                    Node n2 = s.randomNode();
                    if(Node.DO_MORE_LOGGING)
                        System.out.println("Fetching "+k+" from "+n2);
                    if(n2.run(req, r, null) >= 0) {
                        if(r.succeeded()) {
                            Main.last10KRequestsSuccess.report(1.0);
                            if(Node.DO_MORE_LOGGING)
                                System.out.println("Completed: "+req+" on "+n2+": "+r);
                            localStats.successfulRequests++;
                            localStats.totalSuccessfulRequestHTL +=
                                r.hopsTaken;
                        } else {
                            Main.last10KRequestsSuccess.report(0.0);
                            if(Node.DO_MORE_LOGGING)
                                System.out.println("Failed: "+req+" on "+n2+": "+r);
                            localStats.failedRequests++;
                        }
                    } else {
                        localStats.rejectedRequests++;
                        if(Node.DO_MORE_LOGGING)
                            System.out.println("Rejected "+req+" on "+n2);
                    }
                    //s.showStats(System.out);
                }
            }
            globalStats.add(localStats);
            System.err.println();
            System.err.println("Global Summary:");
            globalStats.dump();
            System.err.println();
            System.err.println("Local Summary:");
            localStats.dump();
            localStats.clear();
            //if(stillInStoreCounter++ % 8 == 0)
            showStillInStoreStats(keysInserted, s);
            s.showLoadStats(System.err);
            s.showConnStats(System.err);
            s.showSuccessStats(System.err);
            Node.dumpCounters();
            Node.clearCounters();
            now = System.currentTimeMillis();
            long ca = Node.connectionsAdded - lastConnectionsAddedCount;
            lastConnectionsAddedCount = Node.connectionsAdded;
            long ct = Node.connectionsTried - lastConnectionsTriedCount;
            lastConnectionsTriedCount = Node.connectionsTried;
            long cf = Node.forceConnectionsTried - lastForceConnectionsTriedCount;
            lastForceConnectionsTriedCount = Node.forceConnectionsTried;
            long cp = Node.connectionsPassedEstimators - lastConnectionsPassed;
            lastConnectionsPassed = Node.connectionsPassedEstimators;
            System.err.println("Success ratio on last 10K reqs: "+Main.last10KRequestsSuccess.currentValue()+" ("+Main.last10KRequestsSuccess.extraToString()+")");
            System.err.println("Connections added: "+ca+", tried "+ct+", force tried "+cf+", passed estimators: "+cp);
            System.err.println("Cycle took "+(now-lastTime)+"ms");
        	Main.gcAndDumpMemoryUsage();
            lastTime = now;
            rounds++;
        }
    }
    
    /**
     * Write the current state of the simulation to disk.
     */
    private static void writeToDisk() throws IOException {
        /**
         * First problem: Where to write it _to_ ?
         */
        long startTime = System.currentTimeMillis();
        String name = "simsave"+File.separatorChar+getOptionsString('-', File.separatorChar)+File.separatorChar+getDateString(Main.startTime);
        if(reloadTime != -1)
            name += "-reloadedfrom-"+getDateString(reloadTime);
        name+=File.separatorChar+getDateString(System.currentTimeMillis());
        File filename = new File(name);
        File parent = filename.getParentFile();
        System.err.println("Writing to dir: "+parent+" file "+filename);
        if(!((parent.exists() && parent.isDirectory()) || parent.mkdirs())) {
            System.err.println("Could not create dir and does not exist: "+parent);
            return;
        }
        FileOutputStream fos = new FileOutputStream(filename);
        GZIPOutputStream gzos = new GZIPOutputStream(fos);
        BufferedOutputStream bos = new BufferedOutputStream(gzos, 16384);
        DataOutputStream dos = new DataOutputStream(bos);
        writeTo(dos);
        dos.close();
        long endTime = System.currentTimeMillis();
        System.err.println("Write took "+(endTime-startTime)+"ms");
        System.err.println("Written to "+filename);
        updateSerializeOutInterval(endTime-startTime, filename.length());
    }

    /**
     * Update the serialization interval.
     * @param timeToWrite the time taken to write the data to disk (this time; we assume this is accurate) 
     * @param fileSize the size of the written file
     */
    private static void updateSerializeOutInterval(long timeToWrite, long fileSize) {
        // We want to spend at least 95% of our time actually simulating
        long byWriteTime = timeToWrite * 20;
        // We want the data to be downloadable in the time it takes to generate it,
        // assuming 1kB/sec transfer rate, which gives us a reasonable margin: 10% of the time
        // transferring on a link with 10kB/sec.
        // 1kB/sec = 1B/ms
        long byTransferTime = fileSize;
        // And we don't want to write out more often than MIN_SERIALIZE_OUT_INTERVAL
        Main.serializeOutInterval = Math.max(Main.MIN_SERIALIZE_OUT_INTERVAL, Math.max(byWriteTime, byTransferTime));
        System.err.println("Serialization interval now "+serializeOutInterval+"ms"+
                " because file size = "+fileSize+", at 5kB/sec = "+byTransferTime+" ms, and it takes "+
                timeToWrite+"ms to write the sim to disk, so to ensure 95% of our time is actually simulating we need "+
                byWriteTime+"ms");
    }

    /**
     * Write current status to a DataOutputStream
     * 
     * @param dos
     */
    private static void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(1); // version
        // FIXME: should we serialize the state of the RNG?
        String options = getOptionsString(' ',' ');
        dos.writeUTF(options);
        globalStats.writeTo(dos);
        s.writeTo(dos);
        Main.last10KRequestsSuccess.writeDataTo(dos);
        Node.writeStatic(dos);
        dos.writeLong(Main.startTime);
    }

    static final String DATE_FORMAT_STRING ="yyyy-MM-dd-HH-mm-ss";
    static final DateFormat df = new SimpleDateFormat(DATE_FORMAT_STRING);
    
    /**
     * @param l
     * @return
     */
    private static String getDateString(long l) {
        return df.format(new Date(l));
    }

    static int stillInStoreCounter = 0;

    private static void announce(Simulator s) throws KeyException {
        for(int i=0;i<INITIAL_NODES;i++) {
            Node n = s.getNode(i);
            Node n2 = s.getNode(Core.getRandSource().nextInt(s.nodes.size()));
            // Announce n to n2
            n2.announce(n, Core.getRandSource().nextLong(), REQUEST_HTL, CHK.randomKey(Core.getRandSource()), null);
        }
        System.err.println("Announced");
    }

    /**
     * 
     */
    private static void initFullyConnected(KeyspaceEstimatorFactory kef, Simulator s, int nodes) throws KeyException {
        // Create a field of INITIAL_NODES fully connected nodes
        for(int i=0;i<nodes;i++) {
            Node n = new Node(kef, MAX_STORED_KEYS);
            s.addNode(n);
        }
        for(int i=0;i<nodes;i++) {
            Node n = s.getNode(i);
            for(int j=0;j<nodes;j++) {
                if(i==j) continue;
                Node n2 = s.getNode(j);
                n.connect(n2, null, true);
            }
        }
        System.err.println("Created "+INITIAL_NODES+" virtual nodes");
    }

    /**
     * @param args
     */
    private static void initArgs(String[] args, boolean ignoreLoad) {
        for(int i=0;i<args.length;i++) {
            String arg = args[i];
            arg = arg.toLowerCase();
            if(arg.equalsIgnoreCase("pcaching")) {
                System.err.println("Enabling pcaching");
                Node.DO_CLASSIC_PCACHING = true;
            } else if(arg.equalsIgnoreCase("nopcaching")) {
                System.err.println("Disabling pcaching");
                Node.DO_CLASSIC_PCACHING = false;
            } else if(arg.equals("probref")) {
                System.err.println("Enabling probabilistic reference");
                Node.DO_PREFERENCE = true;
            } else if(arg.equals("noprobref")) {
                System.err.println("Disabling probabilistic reference");
                Node.DO_PREFERENCE = false;
            } else if(arg.equalsIgnoreCase("newbie")) {
                System.err.println("Enabling newbie routing");
                Node.DO_NEWBIEROUTING = true;
            } else if(arg.equalsIgnoreCase("nonewbie")) {
                System.err.println("Disabling newbie routing");
                Node.DO_NEWBIEROUTING = false;
            } else if(arg.equalsIgnoreCase("longestimators")) {
                System.err.println("Enabling long estimators");
                Node.USE_THREE_ESTIMATORS = true;
            } else if(arg.equalsIgnoreCase("nolongestimators")) {
                System.err.println("Disabling extra estimators");
                Node.USE_THREE_ESTIMATORS = false;
            } else if(arg.equalsIgnoreCase("smoothing")) {
                System.err.println("Enabling smoothing");
                Node.DO_SMOOTHING = true;
            } else if(arg.equalsIgnoreCase("nosmoothing")) {
                System.err.println("Disabling smoothing");
                Node.DO_SMOOTHING = false;
            } else if(arg.equalsIgnoreCase("random")) {
                System.err.println("Enabling pure random routing");
                Node.DO_RANDOM = true;
            } else if(arg.equalsIgnoreCase("norandom")) {
                // Just for completeness
                System.err.println("Disabling pure random routing");
                Node.DO_RANDOM = false;
            } else if(arg.equalsIgnoreCase("nofastestimators")) {
                System.err.println("Disabling fast estimators");
                Node.DO_FAST_ESTIMATORS = false;
            } else if(arg.equalsIgnoreCase("fastestimators")) {
                System.err.println("Enabling fast estimators");
                Node.DO_FAST_ESTIMATORS = true;
            } else if(arg.equalsIgnoreCase("announce")) {
                System.err.println("Enabling announcement");
                Node.DO_ANNOUNCEMENTS = true;
            } else if(arg.equalsIgnoreCase("noannounce")) {
                System.err.println("Disabling announcement");
                Node.DO_ANNOUNCEMENTS = false;
            } else if(arg.equalsIgnoreCase("altannounce")) {
                System.err.println("Enabling alternate announcement");
                Node.DO_ALT_ANNOUNCEMENTS = true;
            } else if(arg.equalsIgnoreCase("noaltannounce")) {
                System.err.println("Disabling alternate announcement");
                Node.DO_ALT_ANNOUNCEMENTS = false;
            } else if(arg.equalsIgnoreCase("probeinexperience")) {
                System.err.println("Enabling probe by inexperience");
                Node.PROBE_BY_INEXPERIENCE = true;
            } else if(arg.equalsIgnoreCase("noprobeinexperience")) {
                System.err.println("Disabling probe by inexperience");
                Node.PROBE_BY_INEXPERIENCE = false;
            } else if(arg.equalsIgnoreCase("randomizewhenequal")) {
                System.err.println("Enabling randomize when equal");
                Node.RANDOMIZE_WHEN_EQUAL = true;
            } else if(arg.equalsIgnoreCase("norandomizewhenequal")) {
                System.err.println("Disabling randomize when equal");
                Node.RANDOMIZE_WHEN_EQUAL = false;
            } else if(arg.equalsIgnoreCase("sparseprobe")) {
                System.err.println("Enabling probe only when necessary");
                Node.PROBE_ONLY_WHEN_NECESSARY = true;
            } else if(arg.equalsIgnoreCase("nosparseprobe")) {
                System.err.println("Disabling probe only when necessary");
                Node.PROBE_ONLY_WHEN_NECESSARY = false;
            } else if(arg.equalsIgnoreCase("fullyconnected")) {
                System.err.println("Enabling fully connected simulation");
                Main.FULLY_CONNECTED = true;
            } else if(arg.equalsIgnoreCase("nonfullyconnected")) {
                System.err.println("Enabling non-fully-connected simulation");
                Main.FULLY_CONNECTED = false;
            } else if(arg.equalsIgnoreCase("estimatorpassing")) {
                System.err.println("Enabling estimator passing");
                Node.DO_ESTIMATOR_PASSING = true;
            } else if(arg.equalsIgnoreCase("noestimatorpassing")) {
                System.err.println("Disabling estimator passing");
                Node.DO_ESTIMATOR_PASSING = false;
            } else if(arg.equalsIgnoreCase("offlinert")) {
                System.err.println("Enabling offline routing table");
                Node.DO_OFFLINE_RT = true;
            } else if(arg.equalsIgnoreCase("noofflinert")) {
                System.err.println("Disabling offline routing table");
                Node.DO_OFFLINE_RT = false;
            } else if(arg.equalsIgnoreCase("dumps")) {
                System.err.println("Enabling regular dumps");
                Main.DO_REGULAR_DUMPS = true;
            } else if(arg.equalsIgnoreCase("nodumps")) {
                System.err.println("Disabling regular dumps");
                Main.DO_REGULAR_DUMPS = false;
            } else if(arg.equalsIgnoreCase("lowchurn")) {
                System.err.println("Enabling low-churn mode");
                Node.DO_LOW_CHURN = true;
            } else if(arg.equalsIgnoreCase("highchurn")) {
                System.err.println("Disabling low-churn mode");
                Node.DO_LOW_CHURN = false;
            } else if(arg.equalsIgnoreCase("backpassbug")) {
                System.err.println("Enabling back-passing bug emulation");
                Node.DO_BUGGY_BACKPASSING = true;
            } else if(arg.equalsIgnoreCase("nobackpassbug")) {
                System.err.println("Disabling back-passing bug emulation");
                Node.DO_BUGGY_BACKPASSING = false;
            } else if(arg.startsWith("fcnodes=")) {
                String s = arg.substring("fcnodes=".length());
                Main.FULLY_CONNECTED_NODES = Integer.parseInt(s);
                System.err.println("Setting fully connected nodes count to "+FULLY_CONNECTED_NODES);
            } else if(arg.startsWith("nfcnodes=")) {
                String s = arg.substring("nfcnodes=".length());
                Main.NFC_NODES = Integer.parseInt(s);
                System.err.println("Setting non-fully-connected nodes count to "+NFC_NODES);
            } else if(arg.startsWith("nfcrt=")) {
                String s = arg.substring("nfcrt=".length());
                Main.NFC_RT_NODES = Integer.parseInt(s);
                System.err.println("Setting non-fully-connected routing table size to "+NFC_RT_NODES);
            } else if(arg.startsWith("movementfactor=")) {
                // Set movement factor
                String s = arg.substring("movementfactor=".length());
                // Parse
                double mf = Double.parseDouble(s);
                System.err.println("Setting movementFactor to "+mf);
                Node.MOVEMENT_FACTOR = mf;
            } else if(arg.startsWith("buckets=")) {
                // Set # buckets
                String s = arg.substring("buckets=".length());
                int buckets = Integer.parseInt(s);
                System.err.println("Setting # buckets to "+buckets);
                Node.BUCKET_COUNT = buckets;
            } else if(arg.startsWith("htl=")) {
                String s = arg.substring("htl=".length());
                int htl = Integer.parseInt(s);
                System.err.println("Setting HTL to "+htl);
                Main.INSERT_HTL = Main.REQUEST_HTL = htl;
            } else if(arg.startsWith("fetchfrac=")) {
                String s = arg.substring("fetchfrac=".length());
                Main.OVERRIDE_FETCH_FRACTION = Double.parseDouble(s);
                System.err.println("Setting fetch fraction to "+OVERRIDE_FETCH_FRACTION);
            } else if(arg.startsWith("newbiehits=")) {
                String s = arg.substring("newbiehits=".length());
                Node.MIN_HITS_NEWBIE = Integer.parseInt(s);
                System.err.println("Setting newbie hits to "+Node.MIN_HITS_NEWBIE);
            } else if(arg.startsWith("storesize=")) {
                String s = arg.substring("storesize=".length());
                Main.MAX_STORED_KEYS = Integer.parseInt(s);
                System.err.println("Setting store size to "+MAX_STORED_KEYS);
            } else if(!ignoreLoad) {
                if(arg.startsWith("loadfrom=")) {
                    Main.LOAD_FILENAME = arg.substring("loadfrom=".length());
                } else if(arg.equals("verboseload")) {
                    Main.VERBOSE_LOAD = true;
                }
            }
        }
    }

    private static void showStillInStoreStats(LimitedKeyCollection keysInserted,
            Simulator s) {
        int keysStillStoredSomewhere = 0;
        for(int i=0;i<keysInserted.size();i++) {
            Key k = (Key) keysInserted.get(i);
            boolean found = false;
            for(int j=0;j<s.nodes.size();j++) {
                Node n = s.getNode(j);
                if(n.containsKey(k)) {
                    keysStillStoredSomewhere++;
                    found = true;
                    break;
                }
            }
            if(!found)
                System.out.println("Lost "+k);
        }
        System.err.println("Still in store: "+keysStillStoredSomewhere+"/"+keysInserted.size()+": "+
                ((double)keysStillStoredSomewhere/keysInserted.size()));
    }

    static class Stats {
        long rejectedInserts = 0;
        long inserts = 0;
        long requests = 0;
        long rejectedRequests = 0;
        long successfulRequests = 0;
        long totalSuccessfulRequestHTL = 0;
        long failedRequests = 0;

        int MAGIC = 0x823218e9;
        
        public void dump() {
            System.err.println("Total inserts: "+inserts);
            System.err.println("Rejected inserts: "+rejectedInserts);
            System.err.println("Total requests: "+requests);
            System.err.println("Rejected requests: "+rejectedRequests);
            System.err.println("Failed requests: "+failedRequests);
            System.err.println("Successful requests: "+successfulRequests);
            System.err.println("Overall success ratio: "+(double)successfulRequests/requests);
            System.err.println("Average hops taken to find data: "+(double)totalSuccessfulRequestHTL/successfulRequests);
        }

        /**
         * @param dos
         */
        public void writeTo(DataOutputStream dos) throws IOException {
            dos.writeInt(MAGIC);
            dos.writeInt(0);
            dos.writeLong(rejectedInserts);
            dos.writeLong(inserts);
            dos.writeLong(requests);
            dos.writeLong(rejectedRequests);
            dos.writeLong(successfulRequests);
            dos.writeLong(totalSuccessfulRequestHTL);
            dos.writeLong(failedRequests);
        }

        /**
         * Create a Stats from serialized data.
         */
        public Stats(DataInputStream dis) throws IOException {
            int magic = dis.readInt();
            if(magic != MAGIC)
                throw new IOException("Invalid magic: "+magic+" should be "+MAGIC);
            int ver = dis.readInt();
            if(ver != 0)
                throw new IOException("Unrecognized version: "+ver+" should be "+0);
            rejectedInserts = dis.readLong();
            inserts = dis.readLong();
            requests = dis.readLong();
            rejectedRequests = dis.readLong();
            successfulRequests = dis.readLong();
            totalSuccessfulRequestHTL = dis.readLong();
            failedRequests = dis.readLong();
        }

        public Stats() {
        }

        public void clear() {
            rejectedInserts = 0;
            inserts = 0;
            requests = 0;
            rejectedRequests = 0;
            successfulRequests = 0;
            failedRequests = 0;
            totalSuccessfulRequestHTL = 0;
        }

        public void add(Stats localStats) {
            rejectedInserts += localStats.rejectedInserts;
            inserts += localStats.inserts;
            requests += localStats.requests;
            rejectedRequests += localStats.rejectedRequests;
            successfulRequests += localStats.successfulRequests;
            failedRequests += localStats.failedRequests;
            totalSuccessfulRequestHTL += localStats.totalSuccessfulRequestHTL;
        }
    }

    /**
     * @return
     */
    public static int getExpectedNodeCount() {
        if(Main.FULLY_CONNECTED)
            return Main.FULLY_CONNECTED_NODES;
        else
            return Main.NFC_NODES;
    }
}
