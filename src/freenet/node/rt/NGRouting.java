/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.rt;

import java.util.HashSet;
import java.util.Hashtable;

import freenet.Core;
import freenet.Identity;
import freenet.Key;
import freenet.node.Node;
import freenet.support.Logger;

/**
 * Iterates over a provided NodeEstimator[]
 * 
 * @author amphibian
 */
public class NGRouting extends TerminableRouting {
	private final NGRoutingTable ngrt;
	private final HashSet routedTo = new HashSet();
	private final Hashtable routes = new Hashtable();
	private final ForgettingEstimateList list;
	private Estimate lastEstimate = null;
	private final int maxSteps;
	private int count = 0;
	private volatile long lastTime;
	private static int logDEBUGRefreshCounter=0;
	private boolean ignoreDNF = false;
	private boolean ignoreThisResult = false;
	private boolean hasSearchFailed = false;
	private boolean hasRouteFailed = false;
	private final long origStartTime = System.currentTimeMillis();
	private boolean isInsert;
	private boolean isAnnouncement;
	private int searchFailedCount = 0;
	private int backedOffCount = 0;
	private int noConnCount = 0;
	private boolean didNotQuicklyRNF = false;

	NGRouting(
		NGRoutingTable ngrt,
		Estimate[] list,
		int maxLongSteps, // does not include nodes not connected to!
		int maxTotalSteps, // length to read from list - includes disconnected nodes
		Key k,
		Node n,
		boolean isInsert,
		boolean isAnnouncement,
		boolean wasLocal,
		boolean willSendRequests) {
		super(k, wasLocal, n, willSendRequests);
		this.list = new ForgettingEstimateList(list, maxTotalSteps);
		for(int i=0;i<list.length;i++) {
		    Estimate e = list[i];
		    if(e == null) continue;
		    routes.put(e.ne.id, e);
		}
		this.maxSteps = maxLongSteps;
		this.isAnnouncement = isAnnouncement;
		this.ngrt = ngrt;
		this.isInsert = isInsert;
		if(logDEBUGRefreshCounter%1000 == 0) //Dont refresh the flag too often, saves us some CPU
			logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		logDEBUGRefreshCounter++;
	}
	
	//TODO: Remove these and related code when appropriate!
	private final Object flagLock = new Object();
	private int processingCount = 0;

    public boolean canRouteTo(Identity id) {
		boolean hasConn = freeConn(id);
		if(!hasConn) {
		    Core.logger.log(this, "No free conn: "+id, Logger.MINOR);
		    return false;
		}
	    count++;
	    Estimate e = (Estimate)routes.get(id);
	    boolean available = e.ne.
	        isAvailable(willSendRequests);
	    if (available) {
			hasSearchFailed = false;
			hasRouteFailed = false;
			lastTime = System.currentTimeMillis();
			logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
			routedTo.add(id);
			lastEstimate = e;
			return true;
	    } else
			backedOffCount++;
	    Core.logger.log(this, "Not available: "+id, Logger.MINOR);
		return false;
    }
    
	public Identity getNextRoute() {
		int myNumber;
		synchronized(flagLock){
			processingCount++;
			myNumber = processingCount;
			if(processingCount>1){
				Core.logger.log(this,"Please report this to devl@freenetproject.org: I am the #"+myNumber+" thread entering getNextRoute() in "+this,new Exception("debug"),Logger.ERROR);
			}
		}
		try{
		while (true) {
			Estimate next = list.nextEstimate();
			if (next == null || count >= maxSteps) {
				terminate(false, true, true);
				return null;
			}
			lastEstimate = next;
			
			if(logDEBUG) Core.logger.log(
				this,
				toString()
					+ ".getNextRoute() iteration "
					+ count
					+ " got "
					+ lastEstimate.ne
					+ " (estimate "
					+ lastEstimate.value
					+ ") - "
					+ list,
				Logger.DEBUG);
			lastTime = System.currentTimeMillis();
			Identity id = lastEstimate.ne.id;
			boolean hasConn = freeConn(id);
			if(hasConn) {
			    count++;
			    boolean available = lastEstimate.ne.isAvailable(willSendRequests);
			    if (available) {
						hasSearchFailed = false;
						hasRouteFailed = false;
						lastTime = System.currentTimeMillis();
						logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
						routedTo.add(id);
						return id;
				} else
					backedOffCount++;
			} else 
					noConnCount++;
		}
		}finally{
			synchronized(flagLock){
				if(processingCount>1){
					Core.logger.log(this,"Please report this to devl@freenetproject.org: I was the #"+myNumber+" thread in getNextRoute() in "+this,new Exception("debug"),Logger.ERROR);
				}
				processingCount--;
			}
		}
	}

	public boolean haveRoutedTo(Identity identity) {
	    return routedTo.contains(identity);
	}
	
	public double lastEstimatedTime() {
	    if(!checkHasRoutedAtLeastOnceButNotTerminated()) {
	        // Routes ended
	        return Double.MAX_VALUE;
	    }
		return lastEstimate.value;
	}

	public void ignoreRoute() {
		count--;
	}

	public Identity getLastIdentity() {
		 if(!checkHasRoutedAtLeastOnceButNotTerminated())
		 	return null;
		return lastEstimate.ne.id;
	}

	public void authFailed() {
		if(!checkHasRoutedAtLeastOnceButNotTerminated())
		 	return;
		lastEstimate.ne.connectFailed(System.currentTimeMillis() - lastTime);
	}

	public void timedOut() {
		// ignore, see searchFailed
		// FIXME: announcement.ReplyPending uses this and not the other
		// FIXME: remove the old methods, convert TreeRouting to use the new?
	}

	public void routeAccepted() {
		// ignore
	}

	public void routeSucceeded() {
		// ignore, see message*
	}

	public void verityFailed() {
		// Called if transfer fails in initialization
		// So we haven't actually transferred anything except the storables
		// So lets treat as search failed
		// FIXME!
		searchFailed();
	}

	// Nothing should be reported on inserts or announcements, because 
	// most of the timings are different, and the probabilities MAY be
	// different, and the endings are different...
	
	public void queryRejected(long attenuation, boolean afterAccepted) {
		if(!checkHasRoutedAtLeastOnceButNotTerminated())
		 	return;
		if(isInsert||isAnnouncement) return;
		long now = System.currentTimeMillis();
		long diff = now - lastTime;
		searchFailedCount++;
		if (!hasSearchFailed) {
			if (diff < 0) {
				Core.logger.log(
					this,
					"WTF? queryRejected causing negative searchFailed time: now="
						+ now
						+ ", lastTime="
						+ lastTime
						+ ", diff="
						+ diff
						+ " on "
						+ this,
					Logger.ERROR);
			}
			hasSearchFailed = true;
			if(afterAccepted)
				lastEstimate.ne.searchFailed(diff);
			else
				lastEstimate.ne.queryRejected(diff);
		} else {
			Core.logger.log(
				this,
				"Search failed twice for " + this,
				new Exception("debug"),
				Logger.MINOR);
		}
		lastTime = now;
	}

	public void earlyTimeout() {
		if(!checkHasRoutedAtLeastOnceButNotTerminated())
		 	return;
		if(isInsert||isAnnouncement) return;
	    long now = System.currentTimeMillis();
	    long l = now - lastTime;
	    lastTime = now;
	    searchFailedCount++;
		if (!hasSearchFailed) {
			hasSearchFailed = true;
			lastEstimate.ne.earlyTimeout(l);
		} else {
			Core.logger.log(
				this,
				"Search failed twice for " + this,
				new Exception("debug"),
				Logger.NORMAL);
		}
	}

	public void searchFailed() {
		if(!checkHasRoutedAtLeastOnceButNotTerminated())
		 	return;
		if(isInsert||isAnnouncement) return;
	    long now = System.currentTimeMillis();
	    long time = now - lastTime;
	    lastTime = now;
		searchFailedCount++;
		if (!hasSearchFailed) {
			hasSearchFailed = true;
			lastEstimate.ne.searchFailed(time);
		} else {
			Core.logger.log(
				this,
				"Search failed twice for " + this,
				new Exception("debug"),
				Logger.NORMAL);
		}
	}

	boolean hadTransfer = false;
	
	public void transferFailed(
		long time,
		int htl,
		long size,
		long transferTime) {
	    hadTransfer = true;
		if(!checkHasRoutedAtLeastOnceButNotTerminated())
		 	return;
		// DO NOT report transfer failure.
		// It is in the wrong direction!
		// When requests report TF, it's because a RECEIVE failed.
		if(isInsert||isAnnouncement) return;
	    reportTransfer(true);
		ngrt.singleHopPTransferFailedGivenTransferEstimator.reportProbability(
			k,
			1.0);
		ngrt.singleHopPTransferFailedGivenTransfer.report(1.0);
		ngrt.singleHopPDataNotFoundEstimator.reportProbability(k, 0.0);
		ngrt.singleHopPDataNotFound.report(0.0);
		if (size > 16384 && time > 10)
			ngrt.singleHopRTransferFailedEstimator.reportTransferRate(
				k,
				(double) size / (double) time);
		didNotQuicklyRNF = true;
		long now = System.currentTimeMillis();
		long diff = now - lastTime;
		if (diff < 0 || diff > 24 * 3600 * 1000) { // :(
			Core.logger.log(
				this,
				"transferFailed("
					+ time
					+ ","
					+ htl
					+ ","
					+ size
					+ ") on "
					+ this
					+ " got unreasonable time: "
					+ diff
					+ " (now="
					+ now
					+ ", lastTime="
					+ lastTime
					+ ")",
				Logger.NORMAL);
		}
		if (diff > 0 && diff < 3600 * 1000)
			lastEstimate.ne.transferFailed(k, diff, size);
		lastTime = now;
		// transferFailed is usually not terminal
	}

	public void transferSucceeded(
		long searchTime,
		int htl,
		long size,
		long transferTime) {
	    hadTransfer = true;
		if(!checkHasRoutedAtLeastOnceButNotTerminated())
		 	return;
		if(isInsert||isAnnouncement) return;
		Core.diagnostics.occurrenceContinuous(
            "receivedTransferSize",
			size);
	    reportTransfer(true);
		ngrt.singleHopPDataNotFoundEstimator.reportProbability(k, 0.0);
		ngrt.singleHopPDataNotFound.report(0.0);
		ngrt.singleHopPTransferFailedGivenTransferEstimator.reportProbability(
			k,
			0.0);
		ngrt.singleHopPTransferFailedGivenTransfer.report(0.0);
		didNotQuicklyRNF = true;
		long stdFileSize;
		if (node.dir.countKeys() > 16)
			stdFileSize = node.dir.used() / node.dir.countKeys();
		else
			stdFileSize = 131072;
		long normalizedTime = searchTime + (transferTime * stdFileSize / size);
		if(logDEBUG)
		Core.logger.log(this, "Search time: "+searchTime+", transferTime: "+transferTime+
		        ", stdFileSize: "+stdFileSize+", size: "+size+", htl: "+htl, Logger.DEBUG);
		long now = System.currentTimeMillis();
		if (lastEstimate.searchSuccessTime > 0) {
		    Core.diagnostics.occurrenceContinuous(
		        "requestTransferTime",
		        transferTime);
		    Core.diagnostics.occurrenceContinuous(
		        "requestCompletionTime",
		        now - origStartTime);
			Core.diagnostics.occurrenceContinuous(
				"normalizedSuccessTime",
				normalizedTime);
			Core.diagnostics.occurrenceContinuous(
				"successSearchTime",
				searchTime);
			long diffSearchTime = searchTime - lastEstimate.searchSuccessTime;
			Core.diagnostics.occurrenceContinuous(
				"diffSearchSuccessTime",
				diffSearchTime);
			Core.diagnostics.occurrenceContinuous(
				"absDiffSearchSuccessTime",
				Math.abs(diffSearchTime));
		}
		// we only want successes here - reporting everythings causes us to
		// route to nodes that will just Query Reject. edt
		// The global search time is the time since routing to the start of the
		// successful transfer
		long fullSearchTime =
			now - (origStartTime + transferTime);
		ngrt.globalSearchTimeEstimator.reportTime(k, fullSearchTime);
		Core.diagnostics.occurrenceContinuous("fullSearchTime", fullSearchTime);
		double rate;
		if (size > 16384 /* must be multi-segment */
			&& transferTime > 10 /* sanity check */
			) {
			rate = ((double) size * 1000) / transferTime;
			if (lastEstimate.transferRate > 0) {
				double diffRate = rate - (lastEstimate.transferRate * 1000);
				Core.diagnostics.occurrenceContinuous(
					"diffTransferRate",
					diffRate);
				Core.diagnostics.occurrenceContinuous(
					"absDiffTransferRate",
					Math.abs(diffRate));
			}
			Core.diagnostics.occurrenceContinuous("successTransferRate", rate);
			ngrt.globalTransferRateEstimator.reportTransferRate(k, rate / 1000);
			ngrt.reportRate(rate);
		} else {
			rate = -1.0;
			Core.logger.log(
				this,
				"Not logging transfer rate because size="
					+ size
					+ ", transferTime="
					+ transferTime,
				Logger.MINOR);
		}
		lastEstimate.ne.transferSucceeded(k, searchTime, htl, rate);
		terminate(true, true, false);
	}

	boolean alreadyReportedTransfer = false;
	
	private void reportTransfer(boolean transfer) {
	    if(!(isInsert || isAnnouncement || wasLocal || alreadyReportedTransfer)) {
	        alreadyReportedTransfer = true;
	        ngrt.fullRequestPTransfer.report(transfer ? 1.0 : 0.0);
	    }
	}
	
	protected void reallyTerminate(
		boolean success,
		boolean routingRelated,
		boolean endOfRoute) {
		list.clear();
	    lastEstimate = null;
	    reportTransfer(hadTransfer || success);
		// Still counts in the stats, but not in the routing stats
		if (ignoreThisResult) {
			routingRelated = false;
			ignoreThisResult = false;
		}
		super.reallyTerminate(success, routingRelated, endOfRoute);
		if (logDEBUG)
			Core.logger.log(
				this,
				"reallyTerminating("
					+ success
					+ ","
					+ routingRelated
					+ ","
					+ noDiag
					+ ") "
					+ toString(),
				Logger.DEBUG);
		count = maxSteps + 1;
		if(willSendRequests) {
		    if (routingRelated && didNotQuicklyRNF && 
		            (!(isInsert || isAnnouncement))) {
			Core.diagnostics.occurrenceContinuous(
				"searchFailedCount",
				searchFailedCount);
			Core.diagnostics.occurrenceContinuous(
				"backedOffCount",
				backedOffCount);
			Core.diagnostics.occurrenceContinuous("noConnCount", noConnCount);
			Core.diagnostics.occurrenceContinuous(
				"routedToChoiceRank",
				searchFailedCount + backedOffCount + noConnCount);
			if(!endOfRoute)
			    freenet.node.Main.node.routingRequestEndedWithBackedOffCount(success, backedOffCount);
		}
		if (isInsert && (!noDiag)) {
			if (routingRelated)
				Core.diagnostics.occurrenceBinomial(
					"insertRoutingSuccessRatio",
					1,
					(success ? 1 : 0));
			else
				Core.diagnostics.occurrenceBinomial(
					"insertNonRoutingSuccessRatio",
					1,
					(success ? 1 : 0));
			if (!success)
				Core.diagnostics.occurrenceBinomial(
					"insertFailRoutingNonRoutingRatio",
					1,
					(routingRelated ? 1 : 0));
		}
	}
	}

	public void dataNotFound(int htl) {
		if(!checkHasRoutedAtLeastOnceButNotTerminated())
		 	return;
		if(isInsert||isAnnouncement) return;
		ngrt.singleHopPDataNotFoundEstimator.reportProbability(k, 1.0);
		ngrt.singleHopPDataNotFound.report(1.0);
		long now = System.currentTimeMillis();
		ngrt.singleHopTDataNotFoundEstimator.reportTime(k, now - lastTime);
		didNotQuicklyRNF = true;
		if (!ignoreDNF) {
			lastEstimate.ne.dataNotFound(k, now - lastTime, htl);
		} else {
			node.ft.ignoredDNF(k);
			ignoreThisResult = true;
		}
		lastTime = now;
		terminate(false, true, false);
	}

	public String toString() {
		return super.toString()
			+ " ("
			+ k
			+ ','
			+ (isInsert ? "insert" : "request")
			+ "), EstimateList="+list+", noConnCount="+noConnCount+", backedOffCount="+backedOffCount;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.rt.Routing#setShouldIgnoreDNF()
	 */
	public void setShouldIgnoreDNF() {
		ignoreDNF = true;
	}
	
	//Returns true iff getNextRoute() has been called at least once and
	//terminate hasn't yet been called.. if this is not the case
	//the method will log an error message indicating the situation and return false
	private boolean checkHasRoutedAtLeastOnceButNotTerminated(){
		//Order of the two tests are important since
		//termination also sets lastEstimate to null
		if(terminated){
			Core.logger.log(this,"Action cannot be taken after termination on "+this,
			        new Exception("debug"),Logger.ERROR);
			return false;
		}
		if(lastEstimate == null){
			Core.logger.log(this,"Action cannot be taken before first routing on "+this,
			        new Exception("debug"),Logger.ERROR);
			return false;
		}
		return true;
	}

    public int countBackedOff() {
        return backedOffCount;
    }

    public void transferStarted() {
	    reportTransfer(true);
    }

    public double getEstimate(Identity id) {
        return ((Estimate) (routes.get(id))).value;
    }

    public boolean hasNode(Identity id) {
        return routes.get(id) != null;
    }
}
