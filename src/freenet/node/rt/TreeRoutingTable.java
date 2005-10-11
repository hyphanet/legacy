package freenet.node.rt;

import java.util.Enumeration;
import java.util.Random;

import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.Version;
import freenet.node.NodeReference;
import freenet.support.BinaryTree;
import freenet.support.Logger;
import freenet.support.MetricWalk;
import freenet.support.Skiplist;
import freenet.support.BinaryTree.Node;
import freenet.support.Skiplist.SkipNodeImpl;

/**
 * The basic core of a routing table that routes by walking
 * through closest keys.
 * @author tavin
 */
public abstract class TreeRoutingTable extends StoredRoutingTable {

    protected final int maxRefsPerNode;

    protected long initialRefs;
    protected int curRefs;

    protected final BinaryTree refTree = new Skiplist(32, new Random()); //new RedBlackTree();


    public TreeRoutingTable(RoutingStore routingStore,
                            int maxNodes, int maxRefsPerNode) {
        
	super(routingStore, maxNodes);
        this.maxRefsPerNode = maxRefsPerNode;
	this.initialRefs = 0;
	this.curRefs = 0;
        
        // build binary tree of references
        Enumeration rme = routingStore.elements();
        while (rme.hasMoreElements()) {
            RoutingMemory mem = (RoutingMemory) rme.nextElement();
            ReferenceSet refSet = ReferenceSet.getProperty(mem, "keys");
            Enumeration refs = refSet.references();
            while (refs.hasMoreElements()) {
                Reference ref = (Reference) refs.nextElement();
                refTree.treeInsert(new SkipNodeImpl(ref), true);
		initialRefs++;
		curRefs++;
            }
        }
    }

    public long initialRefsCount() {
	return initialRefs;
    }
    
    public int getKeyCount() {
	return curRefs;
    }
    
    public synchronized void reference(Key k, NodeReference nr, FieldSet e) {

	if(!Version.checkGoodVersion(nr.getVersion())) {
	    Core.logger.log(this, "Rejecting reference "+nr+" - too old",
			    Logger.NORMAL);
	    return;
	}
	
        long now = System.currentTimeMillis();
        
	RoutingMemory mem = reference(nr);
	
	if(k != null && mem != null) {

	    ReferenceSet refSet = ReferenceSet.getProperty(mem, "keys");
	    
	    Reference ref = new Reference(k, nr.getIdentity(), now);
	    
	    Node oldRef = refTree.treeInsert(new SkipNodeImpl(ref), false);
	    if (oldRef != null) {
		ref = (Reference) oldRef.getObject();
		ref.timestamp = now;
		refSet.remove(ref);
	    } else curRefs++;
	    
	    // enforce refs per node limit
	    if (refSet.size() >= maxRefsPerNode) {
		refTree.treeRemove(refSet.pop());
		curRefs--;
	    }
	    
	    refSet.append(ref);
	    
	    mem.setProperty("keys", refSet);
	}
	if(Core.logger.shouldLog(Logger.MINOR,this)) {
	    long length = System.currentTimeMillis()-now;
	    Core.logger.log(this, "reference("+k+","+nr+") took "+
			    length, 
			    length>500?Logger.MINOR:Logger.DEBUG);
	}
    }
    
    /**
     * @see freenet.node.rt.RoutingTable#route(freenet.Key, int, long, boolean, boolean, boolean, boolean)
     * Does not use htl, size, orderByInexperience, willSendRequests
     */
    public synchronized Routing route(Key k, int htl, long size, 
				      boolean isInsert, boolean isAnnounce, 
				      boolean orderByInexperience,
				      boolean wasLocal, boolean willSendRequests) {
	long x = -1;
	
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	boolean logMINOR = Core.logger.shouldLog(Logger.MINOR,this);
    	if(logDEBUG) {
	    x = System.currentTimeMillis();
	    Core.logger.log(this, "Entered route("+k+
			    ") at "+x, new Exception("debug"),
			    Logger.DEBUG);
	}
	MetricWalk w = new MetricWalk(refTree, new Reference(k), 
				      false);
	long y = -1;
	long length = -1;
	if(logMINOR) {
	    y = System.currentTimeMillis();
	    Core.logger.log(this, "Creating MetricWalk for "+k+" took "+(y-x)+
			    " at "+y, Logger.DEBUG);
	    length = y-x;
	}
	TreeRouting r = new TreeRouting(this, w, 
					freenet.node.Main.node /*FIXME!*/,
					wasLocal, k);
	if((length > 500 && logMINOR) || logDEBUG)
	    Core.logger.log(this, "route("+k+
			    ") locked RT for "+length+" at "+y, 
			    length > 500 ? Logger.MINOR : Logger.DEBUG);
	return r;
    }
    
    /**
     * Remove a node from the routing table. Call with lock held.
     * @param mem the RoutingMemory corresponding to the node. Must not be null.
     * @param ident the identity, if known. May be null.
     */
    protected synchronized void remove(RoutingMemory mem, Identity ident) {
	ReferenceSet refSet = ReferenceSet.getProperty(mem, "keys");
	Enumeration refs = refSet.references();
	while(refs.hasMoreElements()) {
	    Reference ref = (Reference) refs.nextElement();
	    refTree.treeRemove(ref);
	    curRefs--;
	}
	routingStore.remove(ident == null ? mem.getIdentity() : ident);
	// Save a syscall
    }
    
    public abstract RTDiagSnapshot getSnapshot(boolean starting);

    // For book keeping.
    //protected abstract void attempted(RoutingMemory mem);
    // This is not needed because either routeConnected()
    // or connectFailed() is always called.
    
    protected abstract boolean isRoutable(RoutingMemory mem, 
					  boolean desperate);

    protected synchronized boolean isRoutable(RoutingMemory mem) {
	return isRoutable(mem, false);
    }
    
    // then this can be called at any time:

    protected abstract void timedOut(RoutingMemory mem);

    
    // then only one of these (or timedOut()):
    
    protected abstract void routeAccepted(RoutingMemory mem);
    
    
    // then only one of these (or timedOut()):
    
    protected abstract void routeSucceeded(RoutingMemory mem);
    
    protected abstract void transferFailed(RoutingMemory mem);
    
    protected abstract void verityFailed(RoutingMemory mem);

    protected abstract void queryRejected(RoutingMemory mem, long attenuation);

    protected abstract void earlyTimeout(RoutingMemory mem);

    /**
     * @param mem
     */
    protected abstract void searchFailed(RoutingMemory mem);
}



