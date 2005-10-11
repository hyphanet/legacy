package freenet.node.rt;

import freenet.FieldSet;
import freenet.Key;
import freenet.Identity;
import freenet.message.StoreData;
import freenet.node.NodeReference;

/**
 * Filters entries based on Identity.  This could be extended
 * to filter based on physical addresses.  Right now the
 * reason for this is to keep references to itself out of
 * the node's routing table.
 * @author tavin
 */
public class FilterRoutingTable implements RoutingTable {

    protected final RoutingTable rt;
    private final Identity[] blockID;
    
    public long initialRefsCount() {
	return rt.initialRefsCount(); // FIXME?
    }
    
    public int getKeyCount() {
	return rt.getKeyCount(); // FIXME if/when we need an accurate count
    }
    
    public FilterRoutingTable(RoutingTable rt, Identity blockID) {
        this(rt, new Identity[] { blockID });
    }

    public FilterRoutingTable(RoutingTable rt, Identity[] blockID) {
        this.rt = rt;
        this.blockID = blockID;
    }

    private final boolean blocks(Identity id) {
        for (int i=0; i<blockID.length; ++i) {
            if (id.equals(blockID[i]))
                return true;
        }
        return false;
    }
    
    public final boolean wantUnkeyedReference(NodeReference ref) {
	return rt.wantUnkeyedReference(ref);
    }
    
    public final void reference(Key k, Identity i, NodeReference nr, FieldSet e) {
        if(i == null) i = nr.getIdentity();
        if (!blocks(i))
            rt.reference(k, i, nr, e);
    }

    public final boolean references(Identity id) {
	if(id == null) throw new IllegalArgumentException("null");
        return rt.references(id);
    }
    
    public final void updateReference(NodeReference nr) {
	rt.updateReference(nr);
    }
    
    public NodeReference getNodeReference(Identity id) {
	return rt.getNodeReference(id);
    }
    
    public final Routing route(Key k, int htl, long size,
			       boolean isInsert, boolean isAnnounce,
			       boolean orderByInexperience, 
			       boolean wasLocal, boolean willSendRequests) {
        return rt.route(k, htl, size, isInsert, isAnnounce, 
                orderByInexperience, wasLocal, willSendRequests);
    }
    
    public final RoutingStore getRoutingStore() {
        return rt.getRoutingStore();
    }

    public final Object semaphore() {
        return rt.semaphore();
    }

    public final RTDiagSnapshot getSnapshot(boolean starting) {
        return rt.getSnapshot(starting);
    }
    
    public final void reportConnectionSuccess(Identity id, long time) {
	rt.reportConnectionSuccess(id, time);
    }
    
    public final void reportConnectionFailure(Identity id, long time) {
	rt.reportConnectionFailure(id, time);
    }

	/* (non-Javadoc)
	 * @see freenet.node.rt.RoutingTable#getEstimator(freenet.Identity)
	 */
	public FieldSet estimatorToFieldSet(Identity identity) {
		return rt.estimatorToFieldSet(identity);
	}

	/* (non-Javadoc)
	 * @see freenet.node.rt.RoutingTable#shouldReference(freenet.node.NodeReference, freenet.message.StoreData)
	 */
	public boolean shouldReference(NodeReference nr, StoreData sd) {
		if(nr == null) return false;
		if(blocks(nr.getIdentity())) return false;
		return rt.shouldReference(nr, sd);
	}

    public void updateMinRequestInterval(Identity id, double d) {
        rt.updateMinRequestInterval(id, d);
    }

    public void remove(Identity id) {
        rt.remove(id);
    }

    public int countNodes() {
        return rt.countNodes();
    }
}
