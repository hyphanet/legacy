package freenet;

import freenet.session.*;

/**
 * The peer class represents a single route of contact to a peer.
 *
 * @author oskar
 */
public class Peer {

    private final Identity id;
    private final Address addr;
    private final LinkManager lm;
    private final Presentation p;

    public Peer(Identity id, Address addr, LinkManager lm, Presentation p) {
        this.id = id;
        this.addr = addr;
        this.lm = lm;
        this.p = p;
    }

    public Identity getIdentity() {
        return id;
    }

    public Address getAddress() {
        return addr;
    }

    public LinkManager getLinkManager() {
        return lm;
    }

    public Presentation getPresentation() {
        return p;
    }

    public boolean equalsIdent(Peer p) {
        return p != null && id.equals(p.id);
    }

    public boolean equalsIdent(Identity id) {
        return id != null && this.id.equals(id);
    }
    
    public boolean equals(Object o) {
	if(o instanceof Peer)
	    return ((Peer)o).equalsIdent(this);
	else return false;
    }
    
    public int hashCode() {
        return id.hashCode() ^ addr.hashCode();
    }
    
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("Peer [");
        sb.append(id).append(" @ ");
        sb.append(addr).append(" (");
        sb.append(lm.designatorNum()).append('/');
        sb.append(p.designatorNum()).append(")]");
        return sb.toString();
    }
}

    
    
