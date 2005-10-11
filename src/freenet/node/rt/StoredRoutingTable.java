/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.rt;

import java.util.Arrays;
import java.util.Enumeration;

import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.support.Logger;

/**
 * Routing table based on a routingStore
 * Does not actually route
 * Base of TreeRoutingTable and NGRoutingTable
 */
public abstract class StoredRoutingTable implements RoutingTable {
    
    protected final RoutingStore routingStore;
    protected final int maxNodes;
	
    protected Node node = null;
    
    public StoredRoutingTable(RoutingStore routingStore,
							  int maxNodes) {
        
        this.routingStore = routingStore;
        this.maxNodes = maxNodes;
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	}
	
    public abstract void reference(Key k, NodeReference nr, FieldSet e);
	
	public RoutingMemory reference(NodeReference nr) {
		RoutingMemory mem;
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		synchronized(this) {
		    Identity id = nr.getIdentity();
			mem = routingStore.getNode(id);
			if (mem == null || nr.supersedes(mem.getNodeReference()))
				mem = routingStore.putNode(id, nr);
		}
		
		// enforce max nodes limit
		int count = countNodes();
	    if (count > maxNodes) {
			synchronized(discardSync) {
				if(count > maxNodes)
					discard(count / 20);
			}
		}
		return mem;
	}
	
	public void updateReference(NodeReference nr) {
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		//TODO: Decrease locking. We *dont* want to lock all of the rt..
		RoutingMemory mem;
		Identity i = nr.getIdentity();
		synchronized(this) {
			mem = routingStore.getNode(i);
			if(mem != null && nr.supersedes(mem.getNodeReference())) {
				mem = routingStore.putNode(i, nr);
				return;
			}
		}
		// Can probably downgrade to MINOR...
		Core.logger.log(this, "updateReference("+nr+") - ref not in RT",
		        new Exception("debug"), Logger.NORMAL);
//		if(wantUnkeyedReference(nr) && nr.isSigned()) {
//			reference(null, null, nr, null);
//		}
	}
	
	//all threads end up being stuck here
	//routingTime goes up several seconds
	//cpu load goes 100%
	//and its already synchronized in DataObjectRoutingStore.contains(...)
	//btw this may not fix the problem, but at least threads not using
	//the routingStore object won't block those using it--zab
	public  boolean references(Identity id) {
        return routingStore.contains(id);
    }
    
	//same situation here
	public  NodeReference getNodeReference(Identity id) {
		if(id == null) throw new IllegalArgumentException("null identity in getNodeReference");
		RoutingMemory r =routingStore.getNode(id);
		return r==null?null:r.getNodeReference();
    }
    
	public final RoutingStore getRoutingStore() {
        return routingStore;
    }
	
    public final Object semaphore() {
        return this;
    }
	
	protected final Object discardSync = new Object();
    private boolean logDEBUG;
    
    /**
     * Discards information about some nodes to free up memory.  The
     * deletion algorithm is derived from the subclass implementation.
     * All that is required is a Comparable object for each node.
     * 
     * This routine requires building a heap, so it is called in a
     * high water / low water mark pattern.
     */
    protected void discard(int num) {
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		synchronized(discardSync) {
		    DiscardContext ctx = getTypicalDiscardSortContext();
		    DiscardSort[] ds = makeSortedRT(ctx);
			int onum = num;
			Core.logger.log(this, "Discarding "+num+" references", 
							Logger.MINOR);
			DiscardSort d;
			int i = ds.length - 1;
			while (num-- > 0 && i >= 0) {
			    d = ds[i];
			    if(d == null) continue;
			    i--;
				remove(d.mem, d.ident);
				// We may be called by seed(), before the node has been set up
				// In that case we don't need to remove the PHs because they 
				// haven't been added yet
				if(node != null && node.connections != null)
				    node.connections.removePeerHandler(d.ident);
				if(Core.logger.shouldLog(Logger.DEBUG,this))
					Core.logger.log(this, "Removing "+d, Logger.DEBUG);
			}
			if(logDEBUG)
			    Core.logger.log(this, "Discarded "+onum+" references",
			            Logger.DEBUG);
		}
    }
    
	/**
     * @return
     */
    protected DiscardSort[] makeSortedRT(DiscardContext ctx) {
        int i=0;
        DiscardSort[] ds;
        synchronized(this) { // FIXME: can we improve on this?
            ds = new DiscardSort[routingStore.size()];
            Enumeration rme = routingStore.elements();
            while (rme.hasMoreElements()) {
                RoutingMemory mem = (RoutingMemory) rme.nextElement();
                Comparable c = getDiscardValue(mem,ctx);
                if(c != null) {
                    DiscardSort d = new DiscardSort(c, mem);
                    if(logDEBUG)
                        Core.logger.log(this, "Adding "+d, Logger.DEBUG);
                    ds[i] = d;
                    i++;
                }
            }
        }
        Arrays.sort(ds, 0, i);
        return ds;
    }

	protected abstract void remove(RoutingMemory mem, Identity ident);
	
	public synchronized void remove(Identity id) {
	    RoutingMemory mem = routingStore.getNode(id);
	    if(mem != null)
	        remove(mem, id);
	}
	
	protected static final class DiscardSort implements Comparable {
        final Comparable cmp;
        final Identity ident;
		final RoutingMemory mem;
		public String toString() {
			return "DiscardSort: "+mem+","+ident+","+cmp;
		}
        DiscardSort(Comparable cmp, RoutingMemory mem) {
            this.cmp = cmp;
			this.mem = mem;
			this.ident = mem.getIdentity();
        }
        public final int compareTo(Object o) {
            return compareTo((DiscardSort) o);
        }
        public final int compareTo(DiscardSort o) {
            return cmp.compareTo(o.cmp);
        }
    }
	
    public void setNode(Node node) {
		if(node == null) 
			throw new IllegalStateException("Tried to set node to null!");
		this.node = node;
    }
    
	public int countOutboundConnections(Identity i) {
		if(node == null) return 0;
		return node.connections.countOutboundConnections(i);
	}
	
	public int countInboundConnections(Identity i) {
		if(node == null) return 0;
		return node.connections.countInboundConnections(i);
	}
	
	public int countConnections(Identity i) {
	    if(node == null) return 0;
	    return node.connections.countConnections(i);
	}
	
	public boolean isConnected(Identity id) {
		if(node == null) return false;
		return node.connections.isOpen(id);
	}
	
	public static interface DiscardContext{
	    // No members yet
	}
	
	/**
     * @return  an object that can be used to decide what node to discard
     *          by picking the nodes with the highest sort-value according
     *          to the Comparable returned.
     * @param ctx a DiscardContext, a n arbitrary lump of information as required by the implementation.
     */
    protected abstract Comparable getDiscardValue(RoutingMemory mem, DiscardContext ctx);
    
    protected abstract DiscardContext getTypicalDiscardSortContext();
}
