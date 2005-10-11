/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.rt;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Hashtable;
import java.util.LinkedList;

import javax.servlet.http.HttpServletResponse;

import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.node.NodeReference;
import freenet.support.DataObject;
import freenet.support.PropertyArray;
import freenet.support.graph.Color;
import freenet.support.graph.GDSList;
import freenet.support.graph.GraphDataSet;

abstract public class NodeEstimator implements DataObject {
    final Identity id;
	private NodeReference ref; //Use the setReference() method to update this field 
	Object refSync = new Object();
	RoutingMemory mem;
	/** The number of keys that we have calculated estimates for, 
	 * excluding the ones that we don't want stats for. Reset to -1
	 * whenever the NodeEstimator is modified.
	 */
	int counter = 0;
	
	/** Should be called EVERY TIME ANYTHING CHANGES that affects
	 * the output of longEstimate().
	 */
	protected final void dirtied() {
	    counter = -1;
	}
	
	public NodeEstimator(RoutingMemory mem, Identity i, NodeReference nr) {
	    if(i == null && nr != null) i = nr.getIdentity();
	    this.id = i;
	    this.mem = mem;
	    setReference(nr);
	}
	
	public NodeReference getReference() {
		return ref;
	}

	abstract public long lastConnectTime();
	abstract public long lastConnectTryTime();
	abstract public int connectTries();
	abstract public int connectSuccesses();
	abstract public int consecutiveFailedConnects();
	abstract public int successes();
	abstract public long lastSuccessTime();
	abstract public long lastAccessedTime();
	abstract public long createdTime();
	abstract public double lastEstimate();

	/**
	 * @return the number of times this node has been accessed for routing
	 */
	abstract public long routeAccesses();

	public RoutingMemory memory() {
		return mem;
	}

	/**
	 * Estimate the time in milliseconds to find a given key
	 * 
	 * @param ignoreForStats
	 * 			  if true, the request will not influence typicalEstimate() 
	 * @param pDataNonexistant
	 *            the probability that the Data does Not Exist
	 * @param typicalSize
	 * 			  the typical file size (for stats purposes)
	 */
	public final double estimate(
	        Key k,
	        int htl,
	        long size,
	        long typicalSize,
	        double pDataNonexistant,
	        boolean ignoreForStats,
	        RecentRequestHistory rrh) {
	    Estimate e = makeNewEstimate();
	    longEstimate(k, htl, size, typicalSize, pDataNonexistant,
	            e, ignoreForStats, rrh);
	    double v = e.value;
	    checkEstimateIn(e);
	    return v;
	}

	private final LinkedList estimatePool = new LinkedList();
	private boolean isVeryOld = true; //Wether or not the node is to old to reference
	
	/**
     * @param e
     */
    private final void checkEstimateIn(Estimate e) {
        e.clear();
        synchronized(estimatePool) {
            estimatePool.add(e);
        }
    }

    private final Estimate makeNewEstimate() {
        synchronized(estimatePool) {
            if(!estimatePool.isEmpty())
                return (Estimate)estimatePool.removeFirst();
        }
        return new Estimate();
    }

    public final Estimate longEstimate(
		Key k,
		int htl,
		long size,
	        long typicalSize,
	        double pDataNonexistant,
	        boolean ignoreForStats, 
	        RecentRequestHistory rrh) {
	    Estimate e = new Estimate();
	    longEstimate(k, htl, size, typicalSize, pDataNonexistant,
	            e, ignoreForStats, rrh);
	    return e;
	}
	
	/**
	 * @return the average estimate for this node, based on the
	 * typical file size info fed to estimate().
	 */
	abstract public double typicalEstimate(RecentRequestHistory h, 
	        long typicalSize);
	
	/**
	 * @return the average estimate for this node, based on the
	 * actual file sizes (only useful if all nodes get called on
	 * every routing, and maybe less accurate than typicalEstimate()).
	 * @param typicalSize typical file size. Won't influence return
	 * value but is needed in case we have to update typical as well
	 * as raw values.
	 */
	abstract public double averageEstimate(RecentRequestHistory h,
	        long typicalSize);

	/**
	 * Estimate success time for a key
	 */
	abstract public double totalTSuccess(Key k, long size);

	/**
	 * Estimate the time to get a key, including failures and retries.
	 * @param k the key
	 * @param htl the HTL
	 * @param size the size of the key 
	 * @param typicalSize the size of a typical key
	 * @param pDataNonexistant the probability that the data is not
	 * reachable no matter who you route to
	 * @param toFillIn an Estimate structure to fill in
	 * @param ignoreForStats if true, don't log it for typicalEstimate().
	 * @param rrh structure containing last N requests, including this 
	 * one, passed in for stats purposes. rrh.counter should be 1 ahead 
	 * of this.counter when calling longEstimate().
	 */
	public abstract void longEstimate(
		Key k,
		int htl,
		long size,
		long typicalSize,
		double pDataNonexistant /* , double pQueryRejected */,
		Estimate toFillIn,
		boolean ignoreForStats, 
		RecentRequestHistory rrh);

	/**
	 * @return the probability of DataNotFound, given that the other failure
	 *         modes do not happen
	 */
	abstract public double dnfProbability(Key k);

	/**
	 * @return the probability of DataNotFound, overall Used for other things
	 *         dnfProbability(k)
	 */
	abstract public double pDNF();

	// Successful connection
	abstract public void routeConnected(long time);

	abstract public void connectFailed(long time);
	// failed to connect for whatever reason
	//abstract public void timedOut(long time); // connection timed out, or we
	// timed out waiting for an answer, or we got QueryRejected, or we timed
	// out mid transfer - any failure that is not connection, and does not
	// depend on the HTL or the doc size
	// Use searchFailed
	abstract public void searchFailed(long time); // failure/timeout
	abstract public void transferFailed(Key key, long time, long size);
	// failure in the middle of a transfer

	/**
	 * Full blown successful request
	 * 
	 * @param rate
	 *            the transfer rate, bytes/second, negative if we don't want to
	 *            report it.
	 */
	abstract public void transferSucceeded(
		Key key,
		long searchTime,
		int htl,
		double rate);

	abstract public void dataNotFound(Key key, long searchTime, int htl);

	/**
	 * Get diagnostic properties Uses parameters to do an estimate()
	 */
	abstract public void getDiagProperties(
		PropertyArray pa,
		RecentRequestHistory rrh,
		long typicalFileSize);

	/**
	 * Returns a tool which can be used for generating HTML reports of this
	 * estimator.
	 */
	abstract public NodeEstimator.HTMLReportTool getHTMLReportingTool();

	/**
	 * An interface which represents a tool that gives the user of the
	 * NodeEstimator some help with rendering the estimator to HTML
	 */
	public interface HTMLReportTool {
		abstract public void toHtml(PrintWriter pw, String imageLinkPrefix)
			throws IOException;
		abstract public String graphNiceName(String type);

		abstract public KeyspaceEstimator getEstimator(String type);
		/**
		 * Draws all estimator graphs on the supplied bitmap. lineColors is
		 * expected to map from estimator names to Colors If a color for any
		 * type of estimator is missing then the graph for that estimator won't
		 * be drawn to the bmp If any of the supplied colors is a color for the
		 * 'combined' graph then the parameter 'drawMode' will effect the
		 * drawing of that graph: drawMode == 0 -> Use common case values for
		 * all of the estimation parameters drawMode == 1 -> Draw the graph for
		 * a couple of different HTLs, use common case values for all of the
		 * rest of the estimation parameters drawMode == 2 -> Draw the graph
		 * for a couple of different sizes, use common case values for all of
		 * the rest of the estimation parameters
		 */
		public void drawCombinedGraphBMP(
			int width,
			int height,
			HttpServletResponse resp)
			throws IOException;
	}

	abstract public GraphDataSet createGDS ( int samples,
											 int htl,
											 long size,
											 double pDataNonexistant );

	abstract public GDSList createGDSL( int samples,
								 int graphMode,
								 Color c);

	abstract public GDSList createGDSL2(int samples,
										Hashtable lineColors);

	/**
	 * @return peak successful search time
	 */
	abstract public long highestEstimatedSearchTime();

	/**
	 * @return peak time to DNF
	 */
	abstract public long highestDNFTime();

	/**
	 * Write to a FieldSet for FNP transfer. Leave out anything unsuitable for
	 * transfer.
	 */
	abstract public FieldSet toFieldSet();

	/**
	 * @return Whether we are currently backed off
	 */
	abstract public boolean isBackedOff();

	/**
	 * @return The period of time remaining on the current backoff, or 0 if we
	 *         are not backed off.
	 */
	abstract public int backoffLength();

	/**
	 * Report the fact that we received a QueryRejected from this node
	 * 
	 * @param time
	 *            the time taken to receive the QR
	 */
	abstract public void queryRejected(long time);

	/**
	 * Report a timeout awaiting a QueryRejected or Accepted, whether or not
	 * the send of the request has definitely succeeded.
	 */
	abstract public void earlyTimeout(long l);

    /**
     * @return true if we can route to this node, at this moment.
     * Does not take into account connectibility - this is just backoff 
     * and rate limiting. We also do the report of trying to send to this
     * node here, ("trying" including discovering that we can't because 
     * of rate limiting), because it has to be done below NGRouting, due
     * to upper levels not knowing about nodes that are skipped because 
     * of isAvailable().
     * @param reportSent if true, if we are available, log us as having sent
     * a message, and if we are not, count the fact that we were not.
     */
    abstract public boolean isAvailable(boolean reportSent);

    /**
     * Update the minimum request interval
     * @param d the new interval
     */
    abstract public void updateMinRequestInterval(double d);

    /**
     * @param nr
     */
    public void updateReference(NodeReference nr) {
        if(nr == null) return;
        synchronized(refSync) {
            if(nr.supersedes(ref)) setReference(nr);
        }
    }
    
    protected void setReference(NodeReference ref){
    	synchronized(refSync){
    		this.ref = ref;
    		isVeryOld = (this.ref != null && ref.badVersion());
        }
    }

    /**
     * @param period the period in milliseconds over which we want
     * data for.
     * @return the number of requests attempted to be sent to this node
     * in the last period ms. 
     */
    abstract public double countRequestAttempts(int period);

    /**
     * @param period the period in milliseconds over which we want
     * data for. 
     * @return the number of requests allowed to be sent to this node
     * in the last period ms.
     */
    abstract public double countAllowedRequests(int period);

    /**
     * @return
     */
    public long age() {
        return System.currentTimeMillis() - createdTime();
    }

    /**
     * @return the current minimum request interval
     */
    abstract public double minRequestInterval();

    /**
     * @return true if the node is too old to route to
     */
    public boolean isVeryOld() {
    	return isVeryOld;
    }

    public Identity getIdentity() {
        return id;
    }

    public String toString() {
        if(ref != null)
            return super.toString() + ":"+ref.toString();
        else
            return super.toString() + ":"+id;
    }
    
	/**
	 * @return the probability of a request being successful
	 */
	abstract public double pSuccess();

    /**
     * @return the total number of requests that have completed,
     * timed out, etc. Always greater than routingHits().
     */
    abstract public long totalHits();
    
    /**
     * Time until next time we can send a request.
     * If a NodeEstimator does not implement rate limiting, or if
     * we can send one now, return 0.
     */
    abstract public long timeTillNextSendWindow(long now);
}
