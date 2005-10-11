package freenet.node.rt;

import freenet.Core;
import freenet.support.Logger;

/**
 * Class contains stats on an NGRoutingTable
 * @author amphibian
 */
public class StandardNodeStats implements NodeStats {
	public double maxPConnectFailed;
	public double maxPTransferFailed;
	public double maxPSearchFailed;
	public double maxPDNF;
	public double minPDNF;
	public long maxConnectFailTime;
	public long maxConnectSuccessTime;
	public double minTransferFailedRate;
	public long maxSearchFailedTime;
	public long maxSuccessSearchTime;
	public long maxDNFTime;
	public double minTransferRate;
	public double maxTQueryRejected;
	public double maxPQueryRejected;
	public double maxTEarlyTimeout;
	public double maxPEarlyTimeout;

	/* (non-Javadoc)
	 * @see freenet.node.rt.NodeStats#reportStatsToDiagnostics()
	 */
	public void reportStatsToDiagnostics() {
		Core.diagnostics.occurrenceContinuous("rtStatsMaxPDNF", maxPDNF);
		Core.diagnostics.occurrenceContinuous("rtStatsMinPDNF", minPDNF);
		Core.diagnostics.occurrenceContinuous("rtStatsMaxDNFTime", maxDNFTime);
		Core.diagnostics.occurrenceContinuous("rtStatsMaxSuccessSearchTime", maxSuccessSearchTime);
		Core.diagnostics.occurrenceContinuous("rtStatsMaxPTransferFailed", maxPTransferFailed);
		Core.diagnostics.occurrenceContinuous("rtStatsMinTransferFailedRate", minTransferFailedRate);
		Core.diagnostics.occurrenceContinuous("rtStatsMinTransferRate", minTransferRate);
	}
	
	public String toString() {
		return super.toString() +
		"maxPConnectFailed="+maxPConnectFailed+
		" maxPTransferFailed="+maxPTransferFailed+
		" maxPSearchFailed="+maxPSearchFailed+
		" maxPDNF="+maxPDNF+
		" minPDNF="+minPDNF+
		" maxConnectFailTime="+maxConnectFailTime+
		" maxConnectSuccessTime="+maxConnectSuccessTime+
		" minTransferFailedRate="+minTransferFailedRate+
		" maxSearchFailedTime="+maxSearchFailedTime+
		" maxSuccessSearchTime="+maxSuccessSearchTime+
		" maxDNFTime="+maxDNFTime+
		" minTransferRate="+minTransferRate+
		" maxTQueryRejected="+maxTQueryRejected+
		" maxPQueryRejected="+maxPQueryRejected+
		" maxTEarlyTimeout="+maxTEarlyTimeout+
		" maxPEarlyTimeout="+maxPEarlyTimeout;
	}
	
	/**
	 * Create an instance with pessimistic default values
	 */
	public static StandardNodeStats createPessimisticDefault() {
		StandardNodeStats st = new StandardNodeStats();
		st.maxPConnectFailed = 0.9;
		st.maxPTransferFailed = 0.9;
		st.maxPDNF = 0.99;
		st.minPDNF = 0.98;
		st.maxPSearchFailed = 0.9;
		st.maxConnectFailTime = 1800*1000;
		st.maxConnectSuccessTime = 1800*1000; // 30 mins
		st.minTransferFailedRate = 1; // 1 byte per second
		st.maxSearchFailedTime = 600*1000; // 10 minutes
		st.maxSuccessSearchTime = 600*1000;
		st.maxDNFTime = 1800*1000;
		st.minTransferRate = 1; // 1 byte per second
		st.maxTQueryRejected = Core.hopTime(1, 0);
		st.maxPQueryRejected = 0.9;
		st.maxTEarlyTimeout = Core.hopTime(1, 0);
		st.maxPEarlyTimeout = 0.9;
		return st;
	}
	
	public StandardNodeStats() {
		reset();
	}
	
	public void register(StandardNodeEstimator ne) {
	    long l;
		double d = ne.pConnectFailed();
		d = checkProbability(d, ne);
		if(maxPConnectFailed < d)
			maxPConnectFailed = d;
		d = ne.pSearchFailed();
		d = checkProbability(d, ne);
		if(maxPSearchFailed < d)
			maxPSearchFailed = d;
		d = ne.pDNF();
		d = checkProbability(d, ne);
		if(maxPDNF < d)
			maxPDNF = d;
		if(minPDNF > d) {
			minPDNF = d;
			if(Core.logger.shouldLog(Logger.DEBUG,this)) Core.logger.log(this, "minPDNF now: "+minPDNF+" because of "+ne,
					new Exception("debug"), Logger.DEBUG);
		}
		l = ne.tConnectFailed();
		if(maxConnectFailTime < l)
			maxConnectFailTime = l;
		l = ne.tConnectSucceeded();
		if(maxConnectSuccessTime < l)
			maxConnectSuccessTime = l;
		l = ne.tSearchFailed();
		if(maxSearchFailedTime < l)
			maxSearchFailedTime = l;
		
		// These use the minimum value, because they are doubled up in new nodes
		// This is because higher is worse.
		// They are the only two estimator times used
		l = ne.minTSuccessSearch();
		if(maxSuccessSearchTime < l)
			maxSuccessSearchTime = l;
		l = ne.minDNFTime();
		if(maxDNFTime < l)
			maxDNFTime = l;
		d = ne.minPTransferFailed();
		d = checkProbability(d, ne);
		if(maxPTransferFailed < d)
			maxPTransferFailed = d;
		d = ne.maxTransferFailedRate();
		if(minTransferFailedRate > d)
			minTransferFailedRate = d;
		
		// Estimator for transfer rate. Higher is better.
		// To prevent cascading downwards, take the min of the maxima
		// This should still be adequate since we initialize to
		// { minTransferRate , minTransferRate / 2 }
		d = ne.maxTransferRate();
		if(minTransferRate > d)
			minTransferRate = d;
		d = ne.tQueryRejected();
		if(maxTQueryRejected < d)
			maxTQueryRejected = d;
		d = ne.pQueryRejected();
		d = checkProbability(d, ne);
		if(maxPQueryRejected < d)
			maxPQueryRejected = d;
		d = ne.pEarlyTimeout();
		d = checkProbability(d, ne);
		if(maxPEarlyTimeout < d)
			maxPEarlyTimeout = d;
		d = ne.tEarlyTimeout();
		if(maxTEarlyTimeout < d)
			maxTEarlyTimeout = d;
	}

    private double checkProbability(double d, NodeEstimator ne) {
        if(d < 0.0 || d > 1.0) {
            Core.logger.log(this, "Illegal probability: "+d+" in "+this+
                    " from "+ne, new Exception("debug"), Logger.ERROR);
            return Math.min(Math.max(d, 1.0), 0.0);
        }
        return d;
    }

	public void reset() {
		// Ridiculous values... if we get ANY hits, these should change
		maxPConnectFailed = 0.0;
		maxPTransferFailed = 0.0;
		maxPDNF = 0.0;
		minPDNF = 1.0;
		maxConnectFailTime = 0;
		maxConnectSuccessTime = 0;
		minTransferFailedRate = 1000.0*1000.0*1000.0*1000.0;
		maxSearchFailedTime = 0;
		maxSuccessSearchTime = 0;
		maxDNFTime = 0;
		minTransferRate = 1000.0*1000.0*1000.0*1000.0; // 1TB/sec!
		maxTQueryRejected = 0;
		maxPQueryRejected = 0.0;
		maxTEarlyTimeout = 0;
		maxPEarlyTimeout = 0;
	}

	public void register(NodeEstimator ne) {
		if(ne instanceof StandardNodeEstimator)
			register((StandardNodeEstimator)ne);
		else
			throw new IllegalArgumentException("Unrecognized estimator type");
	}

	public double minPDNF() {
		return minPDNF;
	}

}
