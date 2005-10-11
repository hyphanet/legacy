package freenet.node.simulator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimator;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.HexUtil;


class Peer extends DoublyLinkedListImpl.Item {
    private final Node node;
    Node n;
    KeyspaceEstimator e;
    KeyspaceEstimator epDNF;
    KeyspaceEstimator etSuccess;
    KeyspaceEstimator etFailure;
    long initialHits;
    Key announcementKey;
    boolean wasNewbie;
    /** Which list were we last added to? */
    boolean wasInexperienced;
    public boolean wasNewbieRouting = false;
    
    /**
     * Create coupling.
     * @param node
     * @param n
     */
    public Peer(Node node, Node n) {
        this.n = n;
        this.node = node;
        if(Node.DO_NEWBIEROUTING)
            this.announcementKey = n.announcementKey;
        else
            this.announcementKey = null;
        if(Node.USE_THREE_ESTIMATORS) {
            epDNF = this.node.newProbabilityEstimator();
            etSuccess = this.node.newTimeEstimator();
            etFailure = this.node.newTimeEstimator();
        } else {
            e = this.node.newEstimator();
        }
        wasNewbie = isNewbie();
    }
    /**
     * @param node2
     * @param n2
     * @param dis
     */
    public Peer(Node owner, Node n, DataInputStream dis) throws IOException {
        this.node = owner;
        this.n = n;
        if(Main.VERBOSE_LOAD)
            System.err.println("Reading Peer for "+owner.id+" target is "+n.id);
        int magic = dis.readInt();
        if(magic != MAGIC)
            throw new IOException("Invalid magic: "+magic+" should be "+MAGIC);
        int ver = dis.readInt();
        if(ver != 0)
            throw new IOException("Unknown version: "+ver);
        if(Node.USE_THREE_ESTIMATORS) {
            epDNF = node.newProbabilityEstimator(dis);
            etSuccess = node.newTimeEstimator(dis);
            etFailure = node.newTimeEstimator(dis);
        } else {
            e = node.newEstimator(dis);
        }
        initialHits = dis.readLong();
        if(initialHits < 0)
            throw new IOException("Invalid initial hits");
        if(hits() < initialHits)
            throw new IOException("Estimator hits "+hits()+" but initialHits "+initialHits);
        boolean hasAnnounceKey = dis.readBoolean();
        if(hasAnnounceKey) {
            announcementKey = new Key(HexUtil.readBigInteger(dis));
        }
        this.wasInexperienced = this.isInexperienced();
        this.wasNewbie = this.isNewbie();
        this.wasNewbieRouting = this.isNewbieRouting();
    }
    private long hits() {
        if(Node.USE_THREE_ESTIMATORS)
            return epDNF.countReports();
        else
            return e.countReports();
    }
    
    /**
     * If we do newbie routing, a node is a newbie as long as it's
     * not experienced.
     * @return true if the connected node is a newbie.
     * @see Node.DO_NEWBIEROUTING
     */
    boolean isNewbie() {
        if(announcementKey != null) { // either NEWBIEROUTING or announcement
            return isInexperienced();
        } else
            return false;
        //return false;
    }
    
    /**
     * @return Whether the node is sufficiently inexperienced to warrant
     * a probe request.
     */
    public boolean isInexperienced() {
        if(Node.USE_THREE_ESTIMATORS)
            return epDNF.countReports() - initialHits < Node.MIN_HITS_NEWBIE;
        else
            return e.countReports() - initialHits < Node.MIN_HITS_NEWBIE;
    }
    
    /**
     * @param announceKey
     */
    public void setAnnouncementEstimators(Key announceKey) {
        if(Node.USE_THREE_ESTIMATORS) {
            for(int i=0;i<10;i++) {
                epDNF.reportProbability(announceKey, 0.0);
                etSuccess.reportTime(announceKey, 0);
            }
        } else {
            for(int i=0;i<10;i++)
                e.reportTime(announceKey, 0);
        }
    }
    
    /**
     * @return true if this node is supposed to be routed via newbie routing
     */
    public boolean isNewbieRouting() {
        return Node.DO_ANNOUNCEMENTS && isNewbie();
    }
    /**
     * @return true if we have an announcement key for newbie-routing.
     */
    public boolean hasAnnounced() {
        return announcementKey != null;
    }
    
    static final int MAGIC = 0xfd374eb0;
    
    /**
     * Write data to stream.
     * Don't include the node ID, or the node.
     * @param dos
     */
    public void writeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(0); // version
        // Either e is null and !USE_THREE_ESTIMATORS, and the others are not null
        // Or e is not null and the others are and USE_THREE_ESTIMATORS
        if(Node.USE_THREE_ESTIMATORS) {
            epDNF.writeDataTo(dos);
            etSuccess.writeDataTo(dos);
            etFailure.writeDataTo(dos);
        } else {
            e.writeDataTo(dos);
        }
        dos.writeLong(initialHits);
        if(announcementKey == null)
            dos.writeBoolean(false);
        else {
            dos.writeBoolean(true);
            BigInteger bi = announcementKey.toBigInteger();
            freenet.support.HexUtil.writeBigInteger(bi, dos);
        }
        // was* should be reconstructed by reader
    }
    
    public String toString() {
        return super.toString() + "(owner:"+node+",node:"+n+")";
    }
}
