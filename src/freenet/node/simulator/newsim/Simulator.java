package freenet.node.simulator.newsim;

import java.util.HashMap;
import java.util.Random;

import freenet.node.rt.BootstrappingDecayingRunningAverageFactory;
import freenet.node.rt.EdgeKludgingBinaryRunningAverage;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.node.rt.RunningAverageFactory;
import freenet.node.rt.SlidingBucketsKeyspaceEstimatorFactory;
import freenet.support.Logger;

/**
 * The Simulator object itself. Most of the global data is stored here.
 */
public class Simulator {

    /** Virtual clock. Incremented every time we go a hop. */
    long clock = 0;
    /** The nodes, by ID. FIXME: if you add to nodesByID, null out nodesCache */
    final HashMap nodesByID;
    private Node[] nodesCache;
    /** The configuration of the simulator */
    final SimConfig myConfig;
    /** The random number generator */
    final Random r;
    /** The Logger */
    final Logger logger;
    final KeyspaceEstimatorFactory kef;
    
    public Simulator(SimConfig sc, Random r, Logger l) {
        myConfig = sc;
        logger = l;
        this.r = r;
        /** FIXME: make all this configurable */
        RunningAverageFactory raf = new BootstrappingDecayingRunningAverageFactory(0, Double.MAX_VALUE, 10);
        RunningAverageFactory rafb = EdgeKludgingBinaryRunningAverage.factory(500, 0);
        kef = new SlidingBucketsKeyspaceEstimatorFactory(raf, rafb, raf, /*Node.BUCKET_COUNT*/16, /*Node.MOVEMENT_FACTOR*/0.05, /*Node.DO_SMOOTHING*/false, /*Node.DO_FAST_ESTIMATORS*/true);
        logger.log(this, "Created factories", Logger.NORMAL);
        nodesByID = new HashMap();
        // FIXME: will be different if reading from disk
        // FIXME: will be different if any other initialization strategy e.g. organic growth
        // Create the required number of nodes
        for(int i=0;i<myConfig.initialNodes;i++) {
            Node n = new Node(this);
            nodesByID.put(new Integer(i), n);
        }
        logger.log(this, "Created "+myConfig.initialNodes+" nodes", Logger.NORMAL);
        // Set up routing tables
        Node[] nodes = new Node[nodesByID.size()];
        nodes = (Node[]) nodesByID.values().toArray(nodes);
        for(int i=0;i<nodes.length;i++) {
            Node n = nodes[i];
            if(!myConfig.isFullyConnected) {
                for(int j=0;j<myConfig.maxRTSize;j++) {
                    while(true) {
                        int x = r.nextInt(nodes.length);
                        if(x == i) continue;
                        Node n2 = nodes[x];
                        if(n.isConnectedTo(n2)) continue;
                        n.forceConnectTo(n2);
                        break;
                    }
                }
            } else {
                for(int x=0;x<myConfig.initialNodes;x++) {
                    logger.log(this, "Connecting "+x, Logger.MINOR);
                    Node n2 = nodes[x];
                    if(x != i)
                        n.forceConnectTo(n2);
                }
            }
        }
        logger.log(this, "Connected "+myConfig.initialNodes+" nodes with RTs of "+
                myConfig.maxRTSize, Logger.MINOR);
    }

    /**
     * @return a random node
     */
    public Node randomNode() {
        Node[] nodes = getCachedNodesArray();
        return nodes[r.nextInt(nodes.length)];
    }

    /**
     * @return an array of the current nodes.
     */
    private Node[] getCachedNodesArray() {
        if(nodesCache == null) {
            nodesCache = (Node[]) nodesByID.values().toArray(new Node[nodesByID.size()]);
        }
        return nodesCache;
    }

    /**
     * Dump some stats to stderr
     */
    public void dumpStats() {
        Node[] nodes = getCachedNodesArray();
        int minHits = Integer.MAX_VALUE;
        int maxHits = 0;
        int minConns = Integer.MAX_VALUE;
        int maxConns = 0;
        int minDS = Integer.MAX_VALUE;
        int maxDS = 0;
        for(int i=0;i<nodes.length;i++) {
            Node n = nodes[i];
            int hits = n.hits;
            int conns = n.conns.confirmedSize();
            int ds = n.datastore.size();
            if(hits < minHits) minHits = hits;
            if(hits > maxHits) maxHits = hits;
            if(conns < minConns) minConns = conns;
            if(conns > maxConns) maxConns = conns;
            if(ds < minDS) minDS = ds;
            if(ds > maxDS) maxDS = ds;
        }
        System.err.println("Hits: "+minHits+" to "+maxHits+", Connections: "+
                minConns+" to "+maxConns+", DS: "+minDS+" to "+maxDS);
    }

    int dumpIndex = 0;
    
    /**
     * 
     */
    public void dumpStores() {
        Node[] nodes = getCachedNodesArray();
        for(int i=0;i<nodes.length;i++) {
            nodes[i].dumpStore(dumpIndex);
        }
        dumpIndex++;
    }
}
