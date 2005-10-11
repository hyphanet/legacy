package freenet.node.rt;

import java.util.Hashtable;

import freenet.Core;
import freenet.Identity;
import freenet.Key;
import freenet.support.Logger;
import freenet.support.MetricWalk;
import freenet.support.BinaryTree.Node;

/**
 * This iterates over a metric walk of Reference nodes in the binary tree.
 * Each time a unique Identity is encountered it becomes the next route,
 * if its RoutingMemory can pass the isRoutable() test.
 * @author tavin
 */
class TreeRouting extends TerminableRouting {

    protected final Hashtable steps = new Hashtable();

    protected final TreeRoutingTable rt;
    
    protected final MetricWalk refWalk;

    protected RoutingMemory mem;
    
    protected int stepCount = 0;
    protected boolean logDEBUG = false;

    TreeRouting(TreeRoutingTable rt, MetricWalk refWalk, 
        freenet.node.Node n, boolean wasLocal, Key k) {
        super(k, wasLocal, n, false /* fixme */);
        this.rt = rt;
        this.refWalk = refWalk;
    logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this); // HACK
    if (logDEBUG) Core.logger.log(this, "Created TreeRouting at "+System.currentTimeMillis()+
            " for "+refWalk, Logger.DEBUG);
    }
    
    public Identity getNextRoute() {
		while (true) {
			if (stepCount++ > freenet.node.Node.maxRoutingSteps) {
				// Just give up (RNF) once we have
				// tried a reasonable number of references.
				//
				// The rationale is that at some point the specialization
				// of the references that we are trying is so far away
				// from the search key that we are no longer doing
				// a useful steepest ascent search by trying further
				// refs.
				//
				mem = null;
				if (logDEBUG)
					Core.logger.log(this, "Terminating routing with stepCount=" + stepCount + " for " + refWalk, Logger.DEBUG);
				terminate(false, true, true);
				return null;
			}

			if (logDEBUG)
				Core.logger.log(this, "Trying to getNextRoute... step " + stepCount, Logger.DEBUG);

//            long doneTrivialTime = System.currentTimeMillis();
			Node n= getNextRouteCandidate();

			long gotNodeTime = System.currentTimeMillis();
			//long syncedTime = gotNodeTime - enteredTime;
			//if (syncedTime > 500)
			//	if (Core.logger.shouldLog(Logger.MINOR, this))
			//		Core.logger.log(this, "Got node (synchronized) from " + refWalk + " in " + syncedTime, Logger.MINOR);
			//	else if (logDEBUG)
			//		Core.logger.log(this, "Got node (synchronized) from " + refWalk + " in " + syncedTime, Logger.DEBUG);

			if (n == null) {
				mem = null;
				if (logDEBUG)
					Core.logger.log(this, "refWalk returned null", Logger.DEBUG);
				if (logDEBUG)
					Core.logger.log(this, "... so quitting for " + refWalk, Logger.DEBUG);
				return null;
			}
			if (logDEBUG)
				Core.logger.log(this, "Got a non-null node from refWalk (" + refWalk + ")", Logger.DEBUG);
			Reference ref = (Reference) n.getObject();
			if (!steps.containsKey(ref.ident)) {
				if (logDEBUG)
					Core.logger.log(this, "Node not in already tried nodes", Logger.DEBUG);

				mem = rt.routingStore.getNode(ref.ident);
				// RoutingStore is threadsafe

				long gotFromRTTime = System.currentTimeMillis();
				if (logDEBUG)
					Core.logger.log(this, "Got mem from RT in " + (gotFromRTTime - gotNodeTime), Logger.DEBUG);
				if (mem == null) { // race condition ?
					if (logDEBUG)
						Core.logger.log(this, "Node no longer in routing table!", Logger.DEBUG);
					stepCount--;
					continue;
				}
				if (logDEBUG)
					Core.logger.log(this, "Still here; node is in routing table; " + "memory is " + mem + ", at " + System.currentTimeMillis() + ", for " + refWalk, Logger.DEBUG);
				if (((CPAlgoRoutingTable) rt).isRoutable(mem, false) && freeConn(ref.ident)) {
					// routing table is threadsafe
					if (logDEBUG)
						Core.logger.log(this, "Node is routable", Logger.DEBUG);
					long routableTime = System.currentTimeMillis();
					if (logDEBUG)
						Core.logger.log(this, "Is routable in " + (routableTime - gotFromRTTime), Logger.DEBUG);
					steps.put(ref.ident, ref.ident);
					//rt.attempted(mem);
					long gotNewRefTime = System.currentTimeMillis();
					if (logDEBUG)
						Core.logger.log(this, "Returning route in " + (gotNewRefTime - routableTime) + " for " + refWalk, Logger.DEBUG);
					return mem.getIdentity();
				} else {
					long notRoutableTime = System.currentTimeMillis();
					if (logDEBUG)
						Core.logger.log(this, "Node is NOT routable in " + (notRoutableTime - gotFromRTTime) + " at " + notRoutableTime + " for " + refWalk, Logger.DEBUG);
					stepCount--; // steps must be actually tried
				}
			} else {
				if (logDEBUG)
					Core.logger.log(this, "Node is in already tried nodes", Logger.DEBUG);
				stepCount--;
			}
		}
	}
    
    private Node getNextRouteCandidate() {
		Node n;
		//long enteredTime = -1;
		synchronized (rt) {
			//enteredTime = System.currentTimeMillis();
			//long syncTime = enteredTime - doneTrivialTime;
			//if (syncTime > 500)
			//	if (Core.logger.shouldLog(Logger.MINOR, this))
			//		Core.logger.log(this, "Got lock on RT in " + syncTime + " for " + refWalk, Logger.MINOR);
			//	else if (logDEBUG)
			//		Core.logger.log(this, "Got lock on RT in " + syncTime + " for " + refWalk, Logger.DEBUG);

			/*
			 * BinaryTree's are NOT thread-safe, and the convention is to lock on the routingTable (it does not lock the tree). Locking the RT for the whole
			 */
			n = (Node) refWalk.getNext();
		}
		return n;
	}

	public void ignoreRoute() {
    if(stepCount>0) stepCount--;
    }

    public final Identity getLastIdentity() {
        return mem == null ? null : mem.getIdentity();
    }

    public final void routeAccepted() {
        rt.routeAccepted(mem);
    }

    public final void routeSucceeded() {
        rt.routeSucceeded(mem);
    }

    public final void verityFailed() {
        rt.verityFailed(mem);
    }

    public final void queryRejected(long attenuation, boolean afterAccepted) {
        rt.queryRejected(mem, attenuation);
    }
    
    public void searchFailed() {
        rt.searchFailed(mem);
    }
    
    public void transferFailed(long time, int htl, long size, long etime) {
        rt.transferFailed(mem);
        // Not usually terminal
    }
    
    public void transferSucceeded(long time, int htl, long size, long etime) {
        rt.routeSucceeded(mem);
    terminate(true, true, false);
    long stdFileSize;
    if(node.dir.countKeys() > 16)
        stdFileSize = node.dir.used() / node.dir.countKeys();
    else stdFileSize = 100000;
    long normalizedTime = time + (etime*stdFileSize/size);
    Core.diagnostics.occurrenceContinuous("normalizedSuccessTime", 
                          normalizedTime);
    Core.diagnostics.occurrenceContinuous("successSearchTime",
                          time);
    if(size > 16384)
        Core.diagnostics.occurrenceContinuous("successTransferRate",
                          ((double)size*1000)/
                          etime);
    }
    
    public void dataNotFound(int htl) {
        rt.routeSucceeded(mem);
    terminate(false, true, false);
    // We don't care
    }
    
    protected void reallyTerminate(boolean success, boolean routingRelated,
                                   boolean endOfRoute) {
    super.reallyTerminate(success, routingRelated, endOfRoute);
    if(willSendRequests)
    Core.diagnostics.occurrenceContinuous("routedToChoiceRank",stepCount);

    stepCount = freenet.node.Node.maxRoutingSteps+1; // useless statement?
    }

    /* (non-Javadoc)
     * @see freenet.node.rt.Routing#setShouldIgnoreDNF()
     */
    public void setShouldIgnoreDNF() {
        // We don't care
    }

	public void earlyTimeout(long l) {
		// We don't care
	}

    /* (non-Javadoc)
     * @see freenet.node.rt.Routing#haveRoutedTo(freenet.Identity)
     */
    public boolean haveRoutedTo(Identity identity) {
        return steps.containsKey(identity);
    }

    public void authFailed() {
        // Don't care, not our business
    }

    public void earlyTimeout() {
        rt.earlyTimeout(mem);
    }

    public int countBackedOff() {
        Core.logger.log(this, "countBackedOff() not supported", Logger.NORMAL);
        // Not supported, and irrelevant for treerouting
        return 0;
    }

    public void transferStarted() {
        // Do nothing
    }

    public boolean canRouteTo(Identity identity) {
        // Don't implement rate limiting at present
        throw new UnsupportedOperationException();
    }
}


