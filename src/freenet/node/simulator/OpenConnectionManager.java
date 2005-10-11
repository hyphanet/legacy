package freenet.node.simulator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;

/**
 * Simulator open connection manager.
 * Keeps track of connections.
 * 
 * <pre>
 * Functions:
 * 1. Node -> Peer (or null).
 * 2. Do we have any experienced nodes?
 * 3. Remove and return LRU experienced node.
 * 4. Remove and return LRU experienced node or LRU inexperienced node 
 * if there aren't any experienced ones.
 * 5. Promote node to top of queue.
 * </pre>
 */
public class OpenConnectionManager {

    final Node myNode;
    /** Maps Node to Peer */
    final HashMap peers; 
    /** Inexpreienced Nodes */
    final DoublyLinkedList lruInexperienced;
    /** Experienced nodes */
    final DoublyLinkedList lruExperienced;
    /** Nodes on newbie routing */
    final Vector peersNewbieRouting;
    /** Nodes on normal routing */
    final Vector peersNormalRouting;
    
    /**
     * Get the Peer corresponding to a given Node, or null if we are not
     * connected to that node.
     * @param n The node we are checking for.
     * @return
     */
    Peer getPeer(Node n) {
        return (Peer) peers.get(n);
    }

    /**
     * Do we have any experienced i.e. droppable nodes?
     */
    boolean hasExperiencedNodes() {
        return !lruExperienced.isEmpty();
    }

    /**
     * Remove and return the LRU experienced node
     */
    Peer removeLRUExperiencedNode() {
        Peer p = (Peer)lruExperienced.shift();
        peers.remove(p.n);
        if(p.wasNewbieRouting)
            peersNewbieRouting.remove(p);
        else
            peersNormalRouting.remove(p);
        return p;
    }

    /**
     * Remove and return the LRU experienced node or the LRU inexperienced
     * node if there aren't any experienced nodes. 
     */
    Peer removeLRUNodeExperiencedPref() {
        Peer p = (Peer)lruExperienced.shift();
        if(p == null)
            p = (Peer)lruInexperienced.shift();
        peers.remove(p.n);
        if(p.wasNewbieRouting)
            peersNewbieRouting.remove(p);
        else
            peersNormalRouting.remove(p);
        return p;
    }

    void promote(Peer p, boolean wasThere) {
        if(Node.DO_MORE_LOGGING)
            System.out.println("Promoting "+p+","+wasThere+" for "+this+" - wasInexperienced: "+p.wasInexperienced+", isInexperienced: "+p.isInexperienced());
        if(wasThere && getPeer(p.n) != p)
            throw new IllegalStateException("Wasn't there: "+p+" -> "+getPeer(p.n));
        if(!wasThere) {
            if(getPeer(p.n) == null)
                peers.put(p.n, p);
        }
        // Which list?
        if(p.isInexperienced()) {
            if(p.wasInexperienced) {
                // Still on same list
                lruInexperienced.remove(p);
                lruInexperienced.push(p);
            } else {
                // Demoted somehow!
                if(wasThere) {
                    System.err.println("Demoted - huh?: "+p);
                    lruExperienced.remove(p);
                }
                lruInexperienced.push(p);
                p.wasInexperienced = true;
            }
        } else {
            if(p.wasInexperienced) {
                if(wasThere) {
                    // Promoting to experienced
                    lruInexperienced.remove(p);
                }
                lruExperienced.push(p);
                p.wasInexperienced = false;
            } else {
                lruExperienced.remove(p);
                lruExperienced.push(p);
            }
        }
        if(p.isNewbieRouting()) {
            if(p.wasNewbieRouting && !wasThere) {
                peersNewbieRouting.add(p);        
                // ok
            } else {
                peersNormalRouting.remove(p);
                peersNewbieRouting.add(p);
                p.wasNewbieRouting = true;
            }            
        } else {
            if(p.wasNewbieRouting) {
                peersNewbieRouting.remove(p);
                peersNormalRouting.add(p);
                p.wasNewbieRouting = false;
            } else if(!wasThere) {           
                peersNormalRouting.add(p);
                // ok
            }
        }
    }
    
    public OpenConnectionManager(Node myNode) {
        peers = new HashMap();
        this.myNode = myNode;
        lruInexperienced = new DoublyLinkedListImpl();
        lruExperienced = new DoublyLinkedListImpl();
        peersNewbieRouting = new Vector(Node.RT_MAX_NODES);
        peersNormalRouting = new Vector(Node.RT_MAX_NODES);
    }

    /**
     * @return the total number of connected peers
     */
    public int size() {
        return peers.size();
    }

    /**
     * Remove a peer from the peer list.
     * @param p Peer to remove. 
     */
    public void remove(Peer p) {
        if(p.wasInexperienced)
            lruInexperienced.remove(p);
        else
            lruExperienced.remove(p);
        peers.remove(p.n);
        if(p.wasNewbieRouting)
            peersNewbieRouting.remove(p);
        else
            peersNormalRouting.remove(p);
    }

    /**
     * @param stream
     */
    public void dump(PrintStream stream) {
        stream.println("Connected peers (inexperienced):");
        
        for(Enumeration e = lruInexperienced.forwardElements();e.hasMoreElements();) {
            Node n = ((Peer)(e.nextElement())).n;
            stream.println(n.toString());
        }
        stream.println("Connected peers (experienced):");
        for(Enumeration e = lruExperienced.forwardElements();e.hasMoreElements();) {
            Node n = ((Peer)(e.nextElement())).n;
            stream.println(n.toString());
        }
    }

    /**
     * @return An Iterator over the Peers currently connected.
     */
    public Iterator peers() {
        return peers.values().iterator();
    }

    /**
     * De-serialize OCM from InputStream. 
     * @param parent
     * @param dis
     * @param nodesByID
     * @throws IOException
     */
    OpenConnectionManager(Node parent, DataInputStream dis, HashMap nodesByID) throws IOException {
        this.myNode = parent;
        if(Main.VERBOSE_LOAD)
            System.err.println("Loading OCM");
        int peerCount = dis.readInt();
        if(peerCount > Main.MAX_STORED_KEYS)
            throw new IOException("Peer count: "+peerCount+" too high should be <= "+Main.MAX_STORED_KEYS);
        peers = new HashMap();
        peersNormalRouting = new Vector();
        peersNewbieRouting = new Vector();
        for(int i=0;i<peerCount;i++) {
            long id = dis.readLong();
            Long iid = new Long(id);
            Node n = (Node) nodesByID.get(iid);
            if(n == null) throw new IOException("No node for ID read: "+id);
            if(Main.VERBOSE_LOAD)
                System.err.println("ID: "+id);
            Peer p = new Peer(parent, n, dis);
            peers.put(n, p);
            if(p.isNewbieRouting())
                peersNewbieRouting.add(p);
            else
                peersNormalRouting.add(p);
        }
        lruInexperienced = new DoublyLinkedListImpl();
        lruExperienced = new DoublyLinkedListImpl();
        readLRU(dis, lruInexperienced, "lruInexperienced", nodesByID, peerCount);
        readLRU(dis, lruExperienced, "lruExperienced", nodesByID, peerCount);
    }
    
    private void readLRU(DataInputStream dis, DoublyLinkedList list, String name, HashMap nodesByID, int peerCount) throws IOException {
        // lruInexperienced
        int lruInexperiencedLength = dis.readInt();
        if(lruInexperiencedLength < 0 || lruInexperiencedLength > peerCount)
            throw new IOException(name+" length invalid: "+lruInexperiencedLength);
        for(int i=0;i<lruInexperiencedLength;i++) {
            long id = dis.readLong();
            Long iid = new Long(id);
            Node n = (Node) nodesByID.get(iid);
            if(n == null)
                throw new IOException("No such node by ID: "+id+" reading "+name);
            Peer p = (Peer) peers.get(n);
            if(p == null)
                throw new IOException("Not connected to "+id+" reading "+name);
            list.push(p);
        }
    }

    /**
     * Serialize connected nodes to the DataOutputStream.
     * Don't write the actual nodes, just the id's.
     * But include all the LRU info and so on.
     * @param dos The stream to write the data to.
     * @throws IOException on write error.
     */
    public void writeTo(DataOutputStream dos) throws IOException {
        // peers first
        dos.writeInt(peers.size());
        for(Iterator i=peers.values().iterator();i.hasNext();) {
            Peer p = (Peer) i.next();
            dos.writeLong(p.n.id);
            p.writeTo(dos);
        }
        // lruInexperienced
        dos.writeInt(lruInexperienced.size());
        for(Enumeration e=lruInexperienced.forwardElements();e.hasMoreElements();) {
            Peer p = (Peer) e.nextElement();
            dos.writeLong(p.n.id);
        }
        // lruExperienced
        dos.writeInt(lruExperienced.size());
        for(Enumeration e=lruExperienced.forwardElements();e.hasMoreElements();) {
            Peer p = (Peer) e.nextElement();
            dos.writeLong(p.n.id);
        }
        // Expect reader to reconstruct peers*Routing
    }
    
    public String toString() {
        return super.toString()+" for "+myNode;
    }

    /**
     * @return
     */
    public boolean hasInexperiencedNodes() {
        return !lruInexperienced.isEmpty();
    }
}
