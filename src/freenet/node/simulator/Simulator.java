package freenet.node.simulator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Vector;

import freenet.Core;
import freenet.node.rt.KeyspaceEstimatorFactory;

/**
 * New Freenet simulator, based on NGRouting, takes load into account.
 * Probably we will not simulate interleaved requests.
 * Started 6 September 2004.
 * @author amphibian
 */
public class Simulator {

    public class NodeIDSorter implements Comparator {
        public int compare(Object o1, Object o2) {
            Node n1 = (Node) o1;
            Node n2 = (Node) o2;
            if(n1.id > n2.id) return 1;
            if(n2.id > n1.id) return -1;
            return 0;
        }
    }
    
    /**
     * Serialization magic identifier.
     */
    final int MAGIC = 0x75048bd2;
    
    final Vector nodes;
    
    /**
     * Create an empty Simulator.
     */
    public Simulator() {
    	nodes = new Vector();
    }

    /**
     * De-serialize simulation status from an InputStream.
     * @param dis InputStream to read from.
     * @param kef
     * @param maxKeys
     * @throws IOException
     */
    public Simulator(DataInputStream dis, KeyspaceEstimatorFactory kef, int maxKeys) throws IOException {
        int magic = dis.readInt();
        if(magic != MAGIC)
            throw new IOException("Invalid magic: "+magic+" should be "+MAGIC);
        int ver = dis.readInt();
        if(ver != 0)
            throw new IOException("Unrecognized version "+ver);
        int count = dis.readInt();
        if(count <= 0)
            throw new IOException("Invalid count: "+count);
        if(Main.VERBOSE_LOAD)
            System.err.println(""+count+" nodes");
        int predcount = Main.getExpectedNodeCount();
        if(predcount != count)
            throw new IOException("Wrong number of nodes: "+count+" should be "+predcount);
        HashMap nodesMap = new HashMap();
        System.err.println("Reading IDs...");
        Main.gcAndDumpMemoryUsage();
        for(int i=0;i<count;i++) {
            long id = dis.readLong();
            Long iid = new Long(id);
            if(nodesMap.containsKey(iid)) {
                System.err.println("Already read ID: "+id);
            }
            Node n = new Node(id,kef,maxKeys);
            nodesMap.put(iid, n);
        }
        Main.gcAndDumpMemoryUsage();
        System.err.println("Reading nodes...");
        for(int i=0;i<count;i++) {
            Main.gcAndDumpMemoryUsage();
            Node.readNode(dis, nodesMap);
        }
        Collection values = nodesMap.values();
        Node[] myNodes = new Node[values.size()];
        myNodes = (Node[]) values.toArray(myNodes);
        java.util.Arrays.sort(myNodes, new NodeIDSorter());
        nodes = new Vector(myNodes.length);
        for(int i=0;i<myNodes.length;i++) nodes.add(myNodes[i]);
        System.err.println("Leaving Simulator constructor-from-stream");
        Main.gcAndDumpMemoryUsage();
    }

    /**
     * @param n
     */
    public void addNode(Node n) {
        nodes.add(n);
    }

    /**
     * Dump status of all currently running simulated nodes.
     */
    public void showStats(PrintStream stream) {
        int x = nodes.size();
        stream.println(x+" nodes:");
        for(int i=0;i<nodes.size();i++) {
            Node n = (Node)(nodes.get(i));
            n.dump(stream);
        }
    }
    
    public void showLoadStats(PrintStream stream) {
        int x = nodes.size();
        long[] load = new long[x];
        for(int i=0;i<x;i++) {
            load[i] = ((Node)nodes.get(i)).hits();
        }
        java.util.Arrays.sort(load);
        stream.println("Load distribution: median: "+load[x/2]+" max: "+
                load[x-1]+" min: "+load[0]);
        stream.print("Load distribution detail: ");
        for(int i=0;i<x;i++)
            stream.print(" "+load[i]);
        stream.println();
        stream.println("Load min/max="+(double)load[0]/load[x-1]);
    }

    public void showSuccessStats(PrintStream stream) {
        int x = nodes.size();
        double[] load = new double[x];
        for(int i=0;i<x;i++) {
            load[i] = ((Node)nodes.get(i)).pSuccess();
        }
        java.util.Arrays.sort(load);
        stream.println("pSuccess distribution: median: "+load[x/2]+" max: "+
                load[x-1]+" min: "+load[0]);
        stream.print("pSuccess distribution detail: ");
        for(int i=0;i<x;i++)
            stream.print(" "+load[i]);
        stream.println();
        stream.println("pSuccess min/max="+(double)load[0]/load[x-1]);
    }

    public void showConnStats(PrintStream stream) {
        int x = nodes.size();
        int[] load = new int[x];
        for(int i=0;i<x;i++) {
            load[i] = ((Node)nodes.get(i)).peerCount();
        }
        java.util.Arrays.sort(load);
        stream.println("Conns distribution: median: "+load[x/2]+" max: "+
                load[x-1]+" min: "+load[0]);
        stream.print("Conns distribution detail: ");
        for(int i=0;i<x;i++)
            stream.print(" "+load[i]);
        stream.println();
        stream.println("Conns min/max="+(double)load[0]/load[x-1]);
    }

    /**
     * Get a node from the simulation.
     * @param i The index number of the node.
     * @return a Node from the simulation.
     * @see freenet.node.simulation.Node
     */
    public Node getNode(int i) {
        return (Node) nodes.get(i);
    }

   /** 
    * Dump:
    * <ul>
    * <li>Every node's current datastore.</li>
    * <li>Every node's last 100 request keys.</li>
    * <li>Every node's routing table, in detail.</li>
    * </ul><br>
    * We want to be able to show specialization or lack thereof in estimators.
    *
    */
    void dumpEverything(PrintStream stream, long requests) {
        stream.println("Dump at "+requests+" requests:");
        for(int i=0;i<nodes.size();i++) {
            Node n = (Node)nodes.get(i);
            stream.println("Node "+i+": "+n+" - Datastore:");
            // Dump datastore
            n.dumpDatastore(stream);
            stream.println("Node "+i+": "+n+" - Recent requests:");
            n.dumpRecentRequests(stream);
            //stream.println("Node "+i+": "+n+" - Routing table:");
            //n.dumpRoutingTable();
        }
    }

    /**
     * Request for a random Node from the pool.
     * 
     * @return a randomly chosen Node.
     */
    public Node randomNode() {
        return (Node)nodes.get(Core.getRandSource().nextInt(nodes.size()));
    }


    /**
     * Serialize the status of the simulator to a DataOutputStream.
     * @param dos DataOutputStream to write into.
     * @throws IOException If writing fails.
     */
    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(0); // version
        int count = nodes.size();
        dos.writeInt(count);
        for(int i=0;i<count;i++) {
            dos.writeLong(((Node)nodes.get(i)).id);
        }
        for(int i=0;i<count;i++) {
            ((Node)nodes.get(i)).writeTo(dos);
        }
    }
}
