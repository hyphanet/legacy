package freenet.message;

import freenet.BaseConnectionHandler;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.node.BadReferenceException;
import freenet.node.NodeReference;
import freenet.node.State;
import freenet.node.states.announcement.NewAnnouncement;
import freenet.support.Fields;
import freenet.support.HexUtil;

/**
 * The message that initiates an announcement transaction.
 *
 * @author oskar
 */

public class NodeAnnouncement extends NodeMessage implements HTLMessage {

    public static final String messageName = "NodeAnnouncement";

    private int hopsToLive;
    private int depth;
    private NodeReference announcee;
    private NodeReference anSource;
    private byte[] commitVal;

    public NodeAnnouncement(long id, int htl, int depth, 
                            NodeReference source, NodeReference announcee, 
                            byte[] commitVal) {
        super(id);
        this.hopsToLive = htl;
        this.anSource = source;
        this.depth = depth;
        this.announcee = announcee;
        this.commitVal = commitVal;
    }

    public NodeAnnouncement(long id, int htl, int depth, 
                            NodeReference source, NodeReference announcee, 
                            byte[] commitVal, FieldSet otherFields) {
        super(id, otherFields);
        this.hopsToLive = htl;
        this.anSource = source;
        this.depth = depth;
        this.announcee = announcee;
        this.commitVal = commitVal;
    }

    public NodeAnnouncement(BaseConnectionHandler conn, RawMessage raw) 
        throws InvalidMessageException {

        super(conn, raw);
        String hopS = otherFields.getString("HopsToLive");
        String depthS = otherFields.getString("Depth");
        FieldSet afs = otherFields.getSet("Announcee");
        FieldSet sfs = otherFields.getSet("Source");
        String commitS = otherFields.getString("CommitValue");
        if (hopS == null || hopS.length()==0) {
            throw new InvalidMessageException("No HopsToLive field.");
        } else if (depthS == null || depthS.length()==0) {
            throw new InvalidMessageException("No Depth field.");
        } else if (afs == null || afs.isEmpty()) {
            throw new InvalidMessageException("No Announcee!");
        } else if (sfs == null || sfs.isEmpty()) {
            throw new InvalidMessageException("No Source!");
        } else if (commitS == null || commitS.length()==0) {
            throw new InvalidMessageException("No Commit value");
        } else {
            try {
                hopsToLive = (int) Fields.hexToLong(hopS);
                depth = (int) Fields.hexToLong(depthS);
                announcee = new NodeReference(afs, true);
                anSource = new NodeReference(sfs, false, 
                                             source.peerIdentity());
                commitVal = HexUtil.hexToBytes(commitS);
            } catch (NumberFormatException e) {
                throw 
                  new InvalidMessageException("Malformed depth/htl/commitval");
            } catch (BadReferenceException e) {
                throw new InvalidMessageException("Corrupt Announcee");
            }
        }
        otherFields.remove("HopsToLive");
        otherFields.remove("Depth");
        otherFields.remove("Announcee");
        otherFields.remove("Source");
        otherFields.remove("CommitValue");
    }

    public RawMessage toRawMessage(Presentation p, PeerHandler ph) {
        RawMessage raw = super.toRawMessage(p,ph);
        //raw.messageType = messageName;
        raw.fs.put("HopsToLive", Long.toHexString(hopsToLive));
        raw.fs.put("Depth", Long.toHexString(depth));
        raw.fs.put("Announcee", announcee.getFieldSet());
        raw.fs.put("CommitValue", HexUtil.bytesToHex(commitVal));
        raw.fs.put("Source", anSource.getFieldSet(false));
        return raw;
    }
    
    public final boolean hasTrailer() {
	return false;
    }
    
    public final long trailerLength() {
	return 0;
    }
    
    public String getMessageName() {
        return messageName;
    }

    public int getHopsToLive() {
        return hopsToLive;
    }
    public int getDepth()
    {
		return depth;
    }

    public void decHopsToLive() {
        this.hopsToLive--;
    }

    public void incDepth() {
        this.depth++;
    }
    public NodeReference getAnnouncee(){
    	return announcee;
    }

    public NodeReference getRef() {
        return anSource;
    }

    public void setSource(NodeReference nr) {
        this.anSource = nr;
    }
    
    public void setCommitVal(byte[] commitVal) {
        this.commitVal = commitVal;
    }
    public byte[] getCommitValue(){
    	return commitVal;
    }

    public State getInitialState() {
        return new NewAnnouncement(id, announcee, 
                                   depth + 1, hopsToLive - 1, commitVal);
    }

    public int getPriority() {
        return -10; // fairly important
    }

    public void setHopsToLive(int i) {
        hopsToLive = i;
    }
}


