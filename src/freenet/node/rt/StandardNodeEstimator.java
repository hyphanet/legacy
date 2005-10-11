/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.rt;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.Enumeration;
import java.util.Hashtable;

import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.crypt.RandomSource;
import freenet.node.NodeReference;
import freenet.support.Comparator;
import freenet.support.DataObjectPending;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.PropertyArray;
import freenet.support.graph.Bitmap;
import freenet.support.graph.Color;
import freenet.support.graph.DibEncoder;
import freenet.support.graph.GDSList;
import freenet.support.graph.GraphDataSet;
import freenet.support.io.WriteOutputStream;
import freenet.support.servlet.HtmlTemplate;

class StandardNodeEstimator extends NodeEstimator {
	// Apologies for the notation, but read the estimator function!

	private boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	private boolean logMINOR = Core.logger.shouldLog(Logger.MINOR, this);

	/*
	 * Estimated probabilities, times, and rates for various failure/success
	 * modes 1st prefix char r=RunningAverage e=KeyspaceEstimator
	 * 
	 * 2nd prefix char p = probability t = time (in ms) r = rate (in bytes per
	 * second) FIXME: 1. Verify 2. Change all units to be consistent with one
	 * another. That means this should be bytes/ms
	 * 
	 * All failure probabilities are conditional on the all prior failure modes
	 * NOT HAVING HAPPENED Failure modes are listed in order.
	 */

	// FIXME: remove this failure mode? Open-connections-only routing is
	// unlikely to be reverted
	RunningAverage rpConnectFailed;
	RunningAverage rtConnectFailed;
	RunningAverage rtConnectSuccess; 
	
	/**
	 * QueryRejected Kept per node, on the basis that even if it gets high, it
	 * WILL go back down if it should, because we will route to the node, not
	 * because it's our first choice, but because we keep going until we run
	 * out of nodes on a failed request!
	 * <p>
	 * DOES NOT count 'late' i.e. post-Accepted QueryRejected's (which are
	 * usually caused by RNFs). This is so we can have a reasonably accurate
	 * tQR.
	 * </p>
	 */
	RunningAverage rpQueryRejected;
	RunningAverage rtQueryRejected;

	RunningAverage rpEarlyTimeout;
	RunningAverage rtEarlyTimeout;
	
	/**
	 * SearchFailed This is:
	 * <ul>
	 * <li>A transfer fail receiving the Storables - so we haven't sent any
	 * data.</li>
	 * <li>A timeout after we have received an Accepted.</li>
	 * <li>A QueryRejected after we have received an Accepted.</li>
	 * <li>Any other timeouts (are there any?).</li>
	 * </ul>
	 */
	RunningAverage rtSearchFailed;
	RunningAverage rpSearchFailed;

	/** DNF=Data Not Found */
	RunningAverage rpDNF;
	RunningAverage rpTransferFailed;
	KeyspaceEstimator epDNF;
	KeyspaceEstimator etDNF;
	/**
	 * Estimated time taken for a successful search, up to when we get the
	 * Storables. Does not include time to transfer the trailer. Conditional on
	 * !DNF && !SearchFailed && !EarlyTimeout && !QueryRejected
	 */
	KeyspaceEstimator etSuccessSearch;
	
	KeyspaceEstimator epTransferFailed;
	/**
	 * rate = total file size divided by the time to fail TODO: Provide a
	 * rationale for rate calculation
	 */
	KeyspaceEstimator erTransferFailed;   
	
	KeyspaceEstimator erTransferSuccess;
	
	// epDNFGivenConnectionAndNotRejected - probability of DNF given that we
	// were not ConnectionFailed, and not QueryRejected or otherwise
	// searchFailed; we can still get transferFailed, or success, or DNF
	// tTransferRate is the rate, in bytes per second
	NGRoutingTable ngrt;
	long lastConnectTime = -1;
	long lastConnectTryTime = -1;
	long lastAccessedTime = -1;
	long routeAccesses = 0; // excludes connections
	long totalHits = 0;
	long lastSuccessTime = -1;
	final long createdTime;
	int connectTries = 0;
	int connectSuccesses = 0;
	int consecutiveFailedConnects = 0;
	int successes = 0;
	boolean needConnection;
	double lastEstimate = -1;
	double lastTypicalEstimate = -1;

	public double pSuccess() {
		double pQR = rpQueryRejected.currentValue();
		double pEarlyTimeout = rpEarlyTimeout.currentValue();
		double pSearchFailed = rpSearchFailed.currentValue();
		double pDNF = rpDNF.currentValue();
		double pTransferFailed = rpTransferFailed.currentValue();
		double pFail = pQR;
		pFail = pFail + (1-pFail) * pEarlyTimeout;
		pFail = pFail + (1-pFail) * pSearchFailed;
		pFail = pFail + (1-pFail) * pDNF;
		pFail = pFail + (1-pFail) * pTransferFailed;
		return 1-pFail;
	}
	
	/**
	 * Backoff This is, of course, alchemy. All attempts to do it any other way
	 * have either failed experimentally or have unresolvable theoretical
	 * issues For example, estimating pQR per node is very hard because it is
	 * time dependant - we don't want pQR to go down when a node gets
	 * temporarily overloaded, and then stay up because the node is never
	 * retried. Exponential randomized backoff doesn't work either - the node
	 * will QR because it has no un-backed-off routes, and then this will cause
	 * the same thing to happen in the requesting node, and pretty soon the
	 * network is dead. At least that's my explanation for what happened to the
	 * unstable net around 8 Dec 2003.
	 */
	long enteredBackoffTime = -1;
	int defaultBackoffPeriod = 1000; // ms
	double successBackoffDivisor = 1.5;
	int currentBackoffPeriod = defaultBackoffPeriod;

	/**
	 * Formulas: original: Failure: t -> t + defaultT successTFactor
	 * Analytically, this stabilizes at a backoff of: QRFraction / (1-
	 * QRFraction)) / (successBackoffDivisor-1) QRFraction 0.5 -> 2s 0.75 ->
	 * 60s 0.9 -> 18s 0.99 -> 198s 0.999 -> 1998s Formulas: randomized:
	 * rand(1.0) rand(1.0)) Simulated: 0.5 -> 2.6s +/- 1.4s 0.9 -> 24s +/- 7s
	 * 0.99 -> 263s +/- 48s 0.999 -> 2406 +/- 135s
	 */

	RandomSource random;
	long lastPartialSuccessTime;

	/**
	 * Rate limiting
	 */
	/**
	 * Absolute time the last request was sent, defined as when
	 * notifySuccess()/notifyFailure() called on the HLPPM
	 */
	long lastRequestSentTime = -1;
	
	/**
	 * The current minimum request interval, from messages received from the
	 * other node
	 */
	double minRequestInterval = 1000; // 1 req/sec default
	double origMinRequestInterval = 1000; // before randomization
	/** Random +0%/+10% to apply to minRequestInterval to avoid
	 * potential problems with oscillation.
	 */
	double mriRandomFactor = 1.0 + Math.random() * 0.1;
	
	/**
	 * The last several request intervals
	 */
	ExtrapolatingRunningAverage averageRequestInterval =
	    new SimpleRunningAverage(5, minRequestInterval);
	
	Object rateLimitingSync = new Object();
	private TemporalEventCounter attemptedSends =
		new TemporalEventCounter(5000, 60);
	private AllowedRequestsByIntervalCounter allowedSends =
		new AllowedRequestsByIntervalCounter(5000, 60, 1000);
    private ClearableRunningAverage averageTypicalEstimate =
        new ClearableExponentialProxyingRunningAverage(new SimpleRunningAverage(20, -1));
    private ClearableRunningAverage averageEstimate =
        new ClearableExponentialProxyingRunningAverage(new SimpleRunningAverage(20, -1));

    public synchronized double typicalEstimate(RecentRequestHistory rrh,
            long typicalSize) {
        /** Firstly, if the counters are equal, we have not been 
         * modified, so just return the average */
        if(logDEBUG)
            Core.logger.log(this, "typicalEstimate("+rrh+","+typicalSize+" on "+this+" - counter = "+counter, 
                    Logger.DEBUG);
        synchronized(rrh) {
            int rctr = rrh.counter;
        	int max = rctr+2;
        	int min = rctr - rrh.getUnconfirmedCount()-2;
        	if(min <= 0) min = 0;
        	if(counter >= min && counter <= max && counter != -1)
            	return averageTypicalEstimate.currentValue();
        }
        /** We HAVE been modified... or we've lost track. Either way,
         * we need to recalculate from the history. */
        rrh.regenerateAverages(averageTypicalEstimate, averageEstimate, 
                this, typicalSize);
        counter = rrh.counter;
        return averageTypicalEstimate.currentValue();
    }
    
    public synchronized double averageEstimate(RecentRequestHistory rrh,
            long typicalSize) {
        if(rrh.counter == counter)
            return averageEstimate.currentValue();
        typicalEstimate(rrh, typicalSize);
        return averageEstimate.currentValue();
    }

	public void updateMinRequestInterval(double d) {
	    if(Double.isInfinite(d)) throw new IllegalArgumentException("infinite MRI");
	    if(Double.isNaN(d)) throw new IllegalArgumentException("NaN MRI");
		if(logMINOR && (d != minRequestInterval || logDEBUG))
		Core.logger.log(
			this,
			"Updating minRequestInterval to "
				+ d
				+ " on "
				+ this
				+ " was "
				+ minRequestInterval,
			new Exception("debug"),
			d != origMinRequestInterval ? Logger.MINOR : Logger.DEBUG);
	    if(d < 0) {
		    String ver = "(null)";
		    NodeReference nr = getReference(); 
		    if(nr != null) {
		        ver = nr.getVersion();
		    }
			if (logMINOR)
				Core.logger.log(
					this,
					"updateMinRequestInterval(" + d + ")!: ver=" + ver,
					new Exception("debug"),
					Logger.MINOR);
	        return;
	    }
		if (d < 1)
			d = 1;
	    synchronized(rateLimitingSync) {
			origMinRequestInterval = d;
			minRequestInterval = d * mriRandomFactor;
	        allowedSends.updateRequestInterval(minRequestInterval);
	    }
		if(logDEBUG)
		Core.logger.log(this, "Updated mri to "+minRequestInterval+"="+
				origMinRequestInterval+"*"+mriRandomFactor, Logger.DEBUG);
        Core.diagnostics.occurrenceContinuous("incomingRequestInterval", d);
	}

	public long timeTillNextSendWindow(long now) {
	    double curMRI, fixedMRI, realMRI;
	    if(logDEBUG)
	        Core.logger.log(this, "timeTillNextSendWindow("+now+") on "+this, Logger.DEBUG);
	    long lastReq;
	    synchronized(rateLimitingSync) {
	        if(lastRequestSentTime < 0) {
	            if(logDEBUG)
	                Core.logger.log(this, "Hasn't sent any requests yet", Logger.DEBUG);
	            return 0; // can send now
	        }
	        curMRI = minRequestInterval;
	        fixedMRI = 
	            averageRequestInterval.minReportForValue(minRequestInterval);
	        lastReq = lastRequestSentTime;
	    }
	    realMRI = Math.max(curMRI, fixedMRI);
	    if(logDEBUG)
	        Core.logger.log(this, "curMRI="+curMRI+", fixedMRI="+fixedMRI+
	                ", realMRI="+realMRI, Logger.DEBUG);
	    if(realMRI <= 0) return 0;
	    double diff = lastReq + realMRI - now;
	    if(logDEBUG)
	        Core.logger.log(this, "diff="+diff, Logger.DEBUG);
	    if(diff <= 0) return 0;
	    return (long) diff;
	}
	
	public boolean isAvailable(boolean report) {
	    if(isVeryOld()) return false;
		if (logMINOR) {
			Core.logger.log(
				this,
				"isAvailable("
					+ report
					+ "): minRequestInterval="
					+ minRequestInterval
					+ ", lastRequestSentTime = "
					+ lastRequestSentTime
					+ ", averageRequestInterval="
					+ averageRequestInterval
					+ " on "
					+ this,
				new Exception("debug"),
				Logger.MINOR);
		}
	    // If we are backed off, we are not available
	    if(isBackedOff()) {
			if (logMINOR) {
	            Core.logger.log(this, "Backed off", Logger.MINOR);
			}
	        return false;
	    }
	    long now = System.currentTimeMillis();
	    if(report)
	        attemptedSends.logEvent();
	    synchronized(rateLimitingSync) {
	        // Rate limiting
	        long diff = now - lastRequestSentTime;
			double r = diff;
			if (lastRequestSentTime <= 0
				|| ((averageRequestInterval.valueIfReported(r)
					> minRequestInterval)
	                 && diff >= minRequestInterval)) {
	            if(report) {
	                if(lastRequestSentTime > 0)
	                    averageRequestInterval.report(r);
	                lastRequestSentTime = now;
					// Reset the random factor
					mriRandomFactor = 1.0 + Math.random() * 0.1;
					minRequestInterval = origMinRequestInterval * mriRandomFactor;
	            }
				if (logMINOR) {
					Core.logger.log(
						this,
						"Returning true in isAvailable("
							+ report
							+ ") ("
							+ this
							+ ")",
						Logger.MINOR);
				}
	            return true;
	        } else {
				if (logMINOR) {
					Core.logger.log(
						this,
						"Did not send request: isAvailable("
							+ report
							+ ") to "
							+ this,
						Logger.MINOR);
				}
	        }
	    }
	    return false;
	}
	
	public double countRequestAttempts(int period) {
		return attemptedSends.countEvents(period);
	}

	public double countAllowedRequests(int period) {
	    return allowedSends.count(period);
	}
	
	public double pConnectFailed() {
		return rpConnectFailed.currentValue();
	}

	public double minPTransferFailed() {
	    return epTransferFailed.lowest();
	}

	public double maxPTransferFailed() {
	    return  epTransferFailed.highest();
	}

	public double pSearchFailed() {
		return rpSearchFailed.currentValue();
	}

	public long tConnectFailed() {
		return (long) rtConnectFailed.currentValue();
	}

	public long tConnectSucceeded() {
		return (long) rtConnectSuccess.currentValue();
	}

	public long tSearchFailed() {
		return (long) rtSearchFailed.currentValue();
	}

	public long maxTSuccessSearch() { 
	    return (long) etSuccessSearch.highest();
	}

	public long minTSuccessSearch() { 
	    return (long) etSuccessSearch.highest();
	}

	public long maxDNFTime() {
		return (long) etDNF.highest();
	}

	public long minDNFTime() {
		return (long) etDNF.lowest();
	}

	public double maxTransferFailedRate() {
		return erTransferFailed.lowest();
	}

	public double minTransferRate() {
		return erTransferSuccess.lowest();
	}

	public double maxTransferRate() {
		return erTransferSuccess.highest();
	}

	public FieldSet toFieldSet() {
		FieldSet fs = new FieldSet();
		fs.put("Version", "9");
		fs.maybePut("rpConnectFailed", rpConnectFailed.toFieldSet());
		fs.maybePut("epTransferFailed", epTransferFailed.toFieldSet());
		fs.maybePut("rpSearchFailed", rpSearchFailed.toFieldSet());
		fs.maybePut("rtQueryRejected", rtQueryRejected.toFieldSet());
		fs.maybePut("rpQueryRejected", rpQueryRejected.toFieldSet());
		fs.maybePut("rtEarlyTimeout", rtEarlyTimeout.toFieldSet());
		fs.maybePut("rpEarlyTimeout", rpEarlyTimeout.toFieldSet());
		fs.maybePut("rpDNF", rpDNF.toFieldSet());
		fs.maybePut("rpTransferFailed", rpTransferFailed.toFieldSet());
		fs.maybePut("rtConnectFailed", rtConnectFailed.toFieldSet());
		fs.maybePut("rtConnectSuccess", rtConnectSuccess.toFieldSet());
		fs.maybePut("erTransferFailed", erTransferFailed.toFieldSet());
		fs.maybePut("rtSearchFailed", rtSearchFailed.toFieldSet());
		fs.maybePut("etSuccessSearch", etSuccessSearch.toFieldSet());
		fs.maybePut("erTransferSuccess", erTransferSuccess.toFieldSet());
		fs.maybePut("epDNF", epDNF.toFieldSet());
		fs.maybePut("etDNF", etDNF.toFieldSet());
		return fs;
	}

	public StandardNodeEstimator(
		NGRoutingTable ngrt,
		Identity i, NodeReference ref,
		RoutingMemory mem,
		RunningAverageFactory rafProbability,
		RunningAverageFactory rafTime,
		KeyspaceEstimatorFactory rtef,
		DataObjectPending dop,
		boolean needConnection,
		RandomSource rand)
		throws IOException {
	    super(mem, i, ref);
		Core.logger.log(
			this,
			"Serializing in StandardNodeEstimator",
			Logger.MINOR);
		this.random = rand;
		if (rand == null)
			throw new NullPointerException();
		this.ngrt = ngrt;

		if (logDEBUG) {
		Core.logger.log(
			this,
			"NGRT " + ngrt + ", ref " + ref + ", mem " + mem,
			Logger.DEBUG);
		}

		DataInputStream dis = dop.getDataInputStream();
		if (logDEBUG) {
		Core.logger.log(this, "DataInputStream " + dis, Logger.DEBUG);
		}
		int version = dis.readInt();
		if (version < 3 || version > 9)
			throw new IOException("Unrecognized version " + version);
		rpConnectFailed = rafProbability.create(dis);
		rpSearchFailed = rafProbability.create(dis);
		rpDNF = rafProbability.create(dis);
		if(version >= 7)
			rpTransferFailed = rafProbability.create(dis);
		else
			rpTransferFailed = rafProbability.create(0.5);
		rtConnectFailed = rafTime.create(dis);
		rtConnectSuccess = rafTime.create(dis);
		rtSearchFailed = rafTime.create(dis);
		if (version >= 4)
			rtQueryRejected = rafTime.create(dis);
		else
			rtQueryRejected = rafTime.create(Core.hopTime(1, 0));
		if (version >= 5)
			rpQueryRejected = rafProbability.create(dis);
		else
			rpQueryRejected = rafProbability.create(0.9);
		// shouldn't be too common now
		// We can't set it to 1.0 because of the way
		// SelfAdjustingDecayingRunningAverage works
		// Also we don't want it to take too long to adjust - again see SADRA
		if (version == 6 || version >= 8) {
			rtEarlyTimeout = rafTime.create(dis);
			rpEarlyTimeout = rafProbability.create(dis);
		} else {
			rtEarlyTimeout = rafTime.create(Core.hopTime(1, 0));
			rpEarlyTimeout = rafProbability.create(0.9);
		}
		Core.logger.log(
			this,
			"Serialized in all RunningAverage's",
			Logger.DEBUG);
		etSuccessSearch = rtef.createTime(dis, "etSuccessSearch");
		Core.logger.log(this, "Serialized in etSuccessSearch", Logger.DEBUG);
		erTransferSuccess =
			rtef.createTransferRate(dis, "erTransferSuccess");
		Core.logger.log(this, "Serialized in erTransfer", Logger.DEBUG);
		erTransferFailed =
			rtef.createTransferRate(dis, "erTransferFailed");
		Core.logger.log(this, "Serialized in erTransferFailed", Logger.DEBUG);
		epTransferFailed = rtef.createProbability(dis, "epTransferFailed");
		Core.logger.log(this, "Serialized in epTransferFailed", Logger.DEBUG);
		epDNF = rtef.createProbability(dis, "epDNF");
		Core.logger.log(this, "Serialized in epDNF", Logger.DEBUG);
		etDNF =	rtef.createTime(dis, "etDNF");
		//0 millisecond to ~20 minutes
		if (logDEBUG) {
		Core.logger.log(this, "Serialized in " + this, Logger.MINOR);
		}
		successes = dis.readInt();
		lastSuccessTime = dis.readLong();
		lastAccessedTime = dis.readLong();
		createdTime = dis.readLong();
		routeAccesses = dis.readLong();
		if(version == 9) {
		    totalHits = dis.readLong();
		} else {
		    totalHits = routeAccesses +
		    	rpEarlyTimeout.countReports() +
		    	rpQueryRejected.countReports();
		}
		dop.resolve(this);
		if (logDEBUG) {
		Core.logger.log(this, "Resolved " + this, Logger.DEBUG);
		}
		this.needConnection = needConnection;
	}

	/**
	 * Create one from scratch for a completely new node, using pessimistic
	 * defaults @argument k the initial specialization @argument
	 * initTransferRate initial transfer rate in bytes per second @argument
	 * maxPConnectFailed the highest pConnectFailed from a currently
	 * contactable, respectable node
	 */
	public StandardNodeEstimator(
		NGRoutingTable ngrt,
		Identity id,
		NodeReference ref,
		RoutingMemory mem,
		FieldSet e,
		RunningAverageFactory rafProbability,
		RunningAverageFactory rafTime,
		KeyspaceEstimatorFactory rtef,
		Key k,
		StandardNodeStats stats,
		boolean needConnection,
		RandomSource rand) {
	    super(mem, id, ref);
		if (logMINOR) {
			Core.logger.log(
				this,
				"New node in routing table (StandardNodeEstimator impl) for  "
					+ ref
					+ " ("
					+ ngrt
					+ "), details:"
					+ "mem = "
					+ mem
					+ ", key="
					+ k
					+ ", stats="
					+ stats
					+ ", needConnection="
					+ needConnection
					+ ", e="
					+ e,
				Logger.MINOR);
		}
		this.random = rand;
		if (rand == null)
			throw new NullPointerException();
		this.ngrt = ngrt;
		setReference(ref);
		this.mem = mem;
		// Try to read in from e
		boolean serialized = false;
		if (e != null) {
			if (logMINOR) {
			Core.logger.log(
				this,
				"Deserializing node estimators from network: " + e,
				Logger.MINOR);
			}
			try {
				String v = e.getString("Version");
				if (v == null)
					throw new EstimatorFormatException("no Version");
				try {
					int version = Fields.hexToInt(v);
					if (version != 9)
						throw new EstimatorFormatException(
							"Unsupported version " + version,
							false);
				} catch (NumberFormatException ex) {
					EstimatorFormatException ee =
						new EstimatorFormatException(
							"Odd version: " + v + " (" + ex + ")");
					ee.initCause(ex);
					throw ee;
				}

				rpConnectFailed =
					rafProbability.create(e.getSet("rpConnectFailed"));
				rpSearchFailed =
					rafProbability.create(e.getSet("rpSearchFailed"));
				rpDNF = rafProbability.create(e.getSet("rpDNF"));
				FieldSet fs = e.getSet("rpTransferFailed");
				if(fs == null)
					rpTransferFailed = rafProbability.create(stats.maxPTransferFailed);
				else
					rpTransferFailed = rafProbability.create(fs);
				rtConnectFailed = rafTime.create(e.getSet("rtConnectFailed"));
				rtConnectSuccess = rafTime.create(e.getSet("rtConnectSuccess"));
				rtSearchFailed = rafTime.create(e.getSet("rtSearchFailed"));
				rtQueryRejected = rafTime.create(e.getSet("rtQueryRejected"));
				rpQueryRejected =
					rafProbability.create(e.getSet("rpQueryRejected"));
				rtEarlyTimeout = rafTime.create(e.getSet("rtEarlyTimeout"));
				rpEarlyTimeout =
					rafProbability.create(e.getSet("rpEarlyTimeout"));

				etSuccessSearch =
					rtef.createTime(e.getSet("etSuccessSearch"), "etSuccessSearch");
				//0 millisecond to ~20 minutes
				epTransferFailed =
					rtef.createProbability(
						e.getSet("epTransferFailed"), "epTransferFailed");
				// Rate of 0.0 doesn't matter
				erTransferSuccess =
					rtef.createTransferRate(e.getSet("erTransferSuccess"),"erTransferSuccess");
				//0.001 byte/s to ~10 megabyte/s
				erTransferFailed =
					rtef.createTransferRate(
						e.getSet("erTransferFailed"), "erTransferFailed");
				epDNF =	rtef.createProbability(e.getSet("epDNF"), "epDNF");
				etDNF =	rtef.createTime(e.getSet("etDNF"), "etDNF");
				//0 millisecond to ~20 minutes
				serialized = true;
			} catch (EstimatorFormatException x) {
				if (x.important || logMINOR) {
				Core.logger.log(
					this,
					"Caught " + x + " reading " + ref + " from FieldSet :(",
					x,
					x.important ? Logger.NORMAL : Logger.MINOR);
				}
				serialized = false;
			}
		}
		if (!serialized) {
			Core.logger.log(
				this,
				"Creating StandardNodeEstimator from scratch",
				Logger.MINOR);
			// Reasonable pessimism
			rpConnectFailed = rafProbability.create(stats.maxPConnectFailed);
			rpSearchFailed = rafProbability.create(stats.maxPSearchFailed);
			rtQueryRejected = rafTime.create(stats.maxTQueryRejected);
			rpQueryRejected = rafProbability.create(stats.maxPQueryRejected);
			rtEarlyTimeout = rafTime.create(stats.maxTEarlyTimeout);
			rpEarlyTimeout = rafProbability.create(stats.maxPEarlyTimeout);
			rpDNF = rafProbability.create(stats.maxPDNF);
			rpTransferFailed = rafProbability.create(stats.maxPTransferFailed);
			rtConnectFailed = rafTime.create(stats.maxConnectFailTime);
			rtConnectSuccess = rafTime.create(stats.maxConnectSuccessTime);
			rtSearchFailed = rafTime.create(stats.maxSearchFailedTime);

			// Now the RTEs
			if (k == null) {
				if (logDEBUG) {
				Core.logger.log(
					this,
					"Creating node estimators from " + k,
					Logger.DEBUG);
				}
				epDNF = rtef.createProbability(stats.maxPDNF, "epDNF");
				etSuccessSearch = rtef.createTime((long)stats.maxSuccessSearchTime, "etSuccessSearch");
				Core.logger.log(
					this,
					"createInitTransfer(" + stats.minTransferRate / 1000 + ")",
					Logger.DEBUG);
				erTransferSuccess =
					rtef.createInitTransfer(stats.minTransferRate / 1000, "erTransferSuccess");
				Core.logger.log(
					this,
					"Initializing transfer fail rate to "
						+ stats.minTransferFailedRate / 1000,
					Logger.DEBUG);
				erTransferFailed =
					rtef.createInitTransfer(stats.minTransferFailedRate / 1000, "erTransferFailed");
				epTransferFailed =
					rtef.createProbability(stats.maxPTransferFailed, "epTransferFailed");
				etDNF = rtef.createTime((long)stats.maxDNFTime, "etDNF");
			} else {
				Core.logger.log(
					this,
					"Creating node estimators from scratch",
					Logger.DEBUG);
				epDNF = rtef.createProbability(k, 1.0, stats.maxPDNF, "epDNF");
				etSuccessSearch =
					rtef.createTime(
						k,
						2 * stats.maxSuccessSearchTime,
						stats.maxSuccessSearchTime, "etSuccessSearch");
				erTransferSuccess =
					rtef.createTransfer(
						k,
						stats.minTransferRate / 2000,
						stats.minTransferRate / 1000, "erTransferSuccess");
				erTransferFailed =
					rtef.createTransfer(
						k,
						stats.minTransferFailedRate / 2000,
						stats.minTransferFailedRate / 1000, "erTransferFailed");
				epTransferFailed =
					rtef.createProbability(k, 1.0, stats.maxPTransferFailed, "epTransferFailed");
				etDNF =
					rtef.createTime(k, 2 * stats.maxDNFTime, stats.maxDNFTime, "etDNF");
			}
		}
		this.needConnection = needConnection;
		createdTime = lastAccessedTime = System.currentTimeMillis();
	}

	public double pDNF() {
		return rpDNF.currentValue();
	}

	public double dnfProbability(Key k) {
		return epDNF.guessProbability(k);
	}

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
	public void longEstimate(
		Key k,
		int htl,
		long size,
		long typicalSize,
		double pDataNonexistant,
		Estimate fillIn,
		boolean ignoreForStats,
		RecentRequestHistory rrh) {
		// All probabilities are CONDITIONAL ON PRIOR ERROR MODE NOT OCCURING
		// unless otherwise stated.
		// Error Modes:
		// 1. QueryRejected
		// 2. EarlyTimeout
		// 3. SearchFailed
		// 4. DNF
		// 5. TransferFailed

		double pQueryRejected = rpQueryRejected.currentValue();
		double tQueryRejected = rtQueryRejected.currentValue();
		double pNotQueryRejected = 1.0 - pQueryRejected;

		double pEarlyTimeout = rpEarlyTimeout.currentValue();
		double tEarlyTimeout = rtEarlyTimeout.currentValue();
		double pNotEarlyTimeout = 1.0 - pEarlyTimeout;

		double pSearchFailed = rpSearchFailed.currentValue();
		double tSearchFailed = rtSearchFailed.currentValue();
		
		double tSuccessSearch = etSuccessSearch.guessTime(k);
		
		double pNotSearchFailed = 1.0 - pSearchFailed;

		double pDNF = epDNF.guessProbability(k);
		if(pDNF > 1.0) {
		    Core.logger.log(this, "epDNF returned "+pDNF+"! ("+epDNF+")", Logger.ERROR);
		    pDNF = 1.0;
		}
		double tDNF = etDNF.guessTime(k) * htl;

		// FIXME: Not really all that good... but pDataNonexistant is constant
		// across nodes so we're ok...
		double pDNFGivenDataExists =
			pDataNonexistant < 1.0
				? Math.max(0, (pDNF - pDataNonexistant)) / (1 - pDataNonexistant)
				: pDNF;
		double pNotDNFGivenKeyExists = 1.0 - pDNFGivenDataExists;

		double pTransferFailed = epTransferFailed.guessProbability(k);
		if(pTransferFailed > 1.0) {
		    Core.logger.log(this, "epTransferFailed returned "+pTransferFailed+"! ("+
		            epTransferFailed, Logger.ERROR);
		    pTransferFailed = 1.0;
		}
		double transferRate = erTransferSuccess.guessTransferRate(k);
		transferRate =
			Math.max(transferRate, 1.0 / 16000.0);
		// at least 1/16th byte per second
		// rationale: we don't use floating point, so we have to impose minima
		// this minimum happens to be the lowest representable value for rate
		// FIXME - don't hardcode it!
		// FIXME: make them floating point
		
		// FIXME: pTransferFailed should be per meg or something similar -
		// assuming a constant density of failures over a given volume of data.
		double rTransferFailed;
		if(!erTransferFailed.noReports()) rTransferFailed =
			Math.max(erTransferFailed.guessTransferRate(k), 1.0 / 16000.0);
		else
		    // No experience, so assume it fails right at the end, at the normal transfer rate
		    rTransferFailed = transferRate;
		// at least 1/16th byte per second
		double tTransferFailed = size > 0 ? size / rTransferFailed : 0;
		tTransferFailed += tSuccessSearch;
		double tTypicalTransferFailed = typicalSize > 0 ? typicalSize / rTransferFailed : 0;
		tTypicalTransferFailed += tSuccessSearch;
		double pNotTransferFailed = 1.0 - pTransferFailed;

		double tFailure = 0.0;
		double tTypicalFailure = 0.0;
		tFailure += pTransferFailed * tTransferFailed;
		tTypicalFailure += pTransferFailed * tTypicalTransferFailed;

		tFailure *= (1 - pDNFGivenDataExists);
		tFailure += pDNFGivenDataExists * tDNF;
		tTypicalFailure *= (1- pDNFGivenDataExists);
		tTypicalFailure += pDNFGivenDataExists * tDNF;

		tFailure *= (1 - pSearchFailed);
		tFailure += pSearchFailed * tSearchFailed;
		tTypicalFailure *= (1 - pSearchFailed);
		tTypicalFailure += pSearchFailed * tSearchFailed;

		tFailure *= (1 - pEarlyTimeout);
		tFailure += pEarlyTimeout * tEarlyTimeout;
		tTypicalFailure *= (1 - pEarlyTimeout);
		tTypicalFailure += pEarlyTimeout * tEarlyTimeout;

		// TODO: Use the global probability or the node's probability? m0davis
		// recommends using node's probability (2003-12-23)
		tFailure *= (1 - pQueryRejected);
		tFailure += pQueryRejected * tQueryRejected;
		tTypicalFailure *= (1 - pQueryRejected);
		tTypicalFailure += pQueryRejected * tQueryRejected;

		double pSuccess =
			pNotQueryRejected
				* pNotEarlyTimeout
				* pNotSearchFailed
				* pNotDNFGivenKeyExists
				* pNotTransferFailed;
		double tSuccess;
		double tTypicalSuccess;
		if (pSuccess > 1 || pSuccess < 0) {
			Core.logger.log(this, "pSuccess = " + pSuccess, Logger.ERROR);
			tSuccess = Long.MAX_VALUE;
			tTypicalSuccess = Long.MAX_VALUE;
		} else {
			tSuccess =
				totalTSuccess(k, pDNF, transferRate, tSuccessSearch, size);
			tTypicalSuccess =
			    totalTSuccess(k, pDNF, transferRate, tSuccessSearch, typicalSize);
		}

		double estimate = tSuccess + tFailure / pSuccess;
		double typicalEstimate = tTypicalSuccess + tTypicalFailure / pSuccess;

		lastEstimate = estimate;
		lastTypicalEstimate = typicalEstimate;
		synchronized(this) {
		    if(rrh != null && !ignoreForStats) {
		        averageTypicalEstimate.report(typicalEstimate);
		        averageEstimate.report(estimate);
		        counter++;
		        if(logDEBUG)
		            Core.logger.log(this, "Added "+estimate+":"+typicalEstimate+
		                    " for "+this+" ("+rrh+"): counter="+counter, Logger.DEBUG);
		    } else {
		        if(logDEBUG)
		            Core.logger.log(this, "Not adding point for "+this+": "+rrh+
		                    ", counter="+counter, Logger.DEBUG);
		        // otherwise no point as will be ignored
		    }
		}
		if (successes > 0
			&& (Math.abs(estimate) > (long) (Math.pow(10, 13)) || estimate < 0)) {
			// Some of these will have the initial pessimistic estimators!
			// It can be well below 0, because of new nodes with pDNF <<
			// pDataNonexistant
			if (logMINOR) {
			Core.logger.log(
				this,
				"Unreasonable estimate: " + estimate + " for " + this,
				Logger.MINOR);
		}
		}
		fillIn.ne = this;
		fillIn.value = estimate;
		fillIn.normalizedValue = typicalEstimate;
		fillIn.searchSuccessTime = (long) tSuccessSearch;
		fillIn.transferRate = transferRate;
		if (logMINOR) {
			Core.logger.log(
				this,
				toString()
					+ ".estimate(): needConn="
					+ needConnection
					+ ", size="
					+ size
					+ ", typicalSize="
					+ typicalSize
					+ ", key="
					+ k
					+ ", htl="
					+ htl
					+ ", pQueryRejected="
					+ pQueryRejected
					+ ", tQueryRejected="
					+ tQueryRejected
					+ ", pEarlyTimeout="
					+ pEarlyTimeout
					+ ", tEarlyTimeout="
					+ tEarlyTimeout
					+ ", pSearchFailed="
					+ pSearchFailed
					+ ", tSearchFailed="
					+ tSearchFailed
					+ ", pTransferFailed="
					+ pTransferFailed
					+ ", rTransferFailed="
					+ rTransferFailed
					+ ", pDNF="
					+ pDNF
					+ ", tDNF="
					+ tDNF
					+ ", pDataNonexistant="
					+ pDataNonexistant
					+ ", pDNFGivenKeyExists="
					+ pDNFGivenDataExists
					+ ", tSuccessSearch="
					+ tSuccessSearch
					+ ", tSuccess="
					+ tSuccess
					+ ", pSuccess="
					+ pSuccess
					+ ", estimate="
					+ estimate
					+ "ms, typicalEstimate="
					+ typicalEstimate
					+ "ms",
				Logger.MINOR);
		}
	}

	public double totalTSuccess(Key k, long size) {
		return totalTSuccess(
			k,
			epDNF.guessProbability(k),
			erTransferSuccess.guessTransferRate(k),
			etSuccessSearch.guessTime(k),
			size);
	}

	protected double totalTSuccess(
		Key k,
		double pDNF,
		double transferRate,
		double tSuccessSearch,
		long size) {
		if (transferRate < (1.0 / 16000.0)) // 1/16th byte/sec, the resolution
			transferRate = (1.0 / 16000.0);
		double tSuccess = tSuccessSearch + (size / transferRate);

		if (logMINOR) {
			Core.logger.log(
				this,
				toString()
					+ ": k="
					+ k
					+ ", size="
					+ size
					+ ", pDNF="
					+ pDNF
					+ ", transferRate="
					+ transferRate
					+ ", tSuccessSearch="
					+ tSuccessSearch
					+ ": tSuccess="
					+ tSuccess,
				Logger.MINOR);
		}
		return tSuccess;
	}

	public double lastEstimate() {
		return lastEstimate;
	}

	public void routeConnected(long time) {
		synchronized (this) {
			lastConnectTime = lastConnectTryTime = System.currentTimeMillis();
			connectTries++;
			connectSuccesses++;
			consecutiveFailedConnects = 0;
		}
		rpConnectFailed.report(0.0);
		rtConnectSuccess.report(time);
		if (logDEBUG) {
			Core.logger.log(
				this,
				"Connected in " + time + "ms on " + getReference(),
				new Exception("debug"),
				Logger.DEBUG);
		}
		lastAccessedTime = System.currentTimeMillis();
		// FIXME: do we want this in SNE?
		dirtied();
	}

	public void connectFailed(long time) {
		synchronized (this) {
			lastConnectTryTime = System.currentTimeMillis();
			connectTries++;
			consecutiveFailedConnects++;
		}
		rpConnectFailed.report(1.0);
		rtConnectFailed.report(time);
		if (logDEBUG) {
			Core.logger.log(
				this,
				"Connect failed in " + time + "ms on " + getReference(),
				new Exception("debug"),
				Logger.DEBUG);
	}
		dirtied();
	}

	public void queryRejected(long time) {
		if (time > 15 * 60 * 1000) {
			Core.logger.log(
				this,
				"Took " + time + "ms to get QueryRejected!",
				new Exception("debug"),
				Logger.NORMAL);
		}
		if (time < 0) {
			Core.logger.log(
				this,
				"Negative QR time!: " + time + " on " + this,
				new Exception("debug"),
				Logger.NORMAL);
		} else {
			rtQueryRejected.report(time);
			ngrt.singleHopTQueryRejected.report(time);
		}
		rpQueryRejected.report(1.0);
		// rpEarlyTimeout conditional on !QR, so don't report
		ngrt.singleHopPQueryRejected.report(1.0);
		dirtied();
		synchronized(this) {
		    totalHits++;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.rt.NodeEstimator#earlyTimeout(long)
	 */
	public void earlyTimeout(long time) {
		if (time > 15 * 60 * 1000) {
			Core.logger.log(
				this,
				"Took " + time + "ms to get EarlyTimeout!",
				new Exception("debug"),
				Logger.NORMAL);
		}
		if (time < 0) {
			Core.logger.log(
				this,
				"Negative ET time!: " + time + " on " + this,
				new Exception("debug"),
				Logger.NORMAL);
		} else {
			rtEarlyTimeout.report(time);
			ngrt.singleHopTEarlyTimeout.report(time);
		}
		rpEarlyTimeout.report(1.0);
		ngrt.reportEarlyTimeout(true, this);
		rpQueryRejected.report(0.0);
		ngrt.singleHopPQueryRejected.report(0.0);
		dirtied();
		synchronized(this) {
		    totalHits++;
		}
	}

	public void searchFailed(long time) {
		// A later timeout
		rpQueryRejected.report(0.0);
		rpEarlyTimeout.report(0.0);
		ngrt.singleHopPQueryRejected.report(0.0);
		ngrt.reportEarlyTimeout(false, this);
		rpSearchFailed.report(1.0);
		rtSearchFailed.report(time);
		ngrt.singleHopPSearchFailed.report(1.0);
		ngrt.singleHopTSearchFailed.report(time);
		if (logDEBUG) {
			Core.logger.log(
				this,
				"Search failed in " + time + "ms on " + getReference(),
				new Exception("debug"),
				Logger.DEBUG);
		}
		long now = System.currentTimeMillis();
		lastAccessedTime = now;
		synchronized (this) {
			routeAccesses++;
			totalHits++;
			// Exponential backoff
			//			if(enteredBackoffTime + currentBackoffPeriod > now) {
			//				// Ignore, already backed off
			//				if(logDEBUG)
			//					Core.logger.log(this, "Ignored a searchFailed because already
			// backed off on "+this,
			//							Logger.DEBUG);
			//			} else {
			//				// tBackoff -> tBackoff + defaultBackoff * rand
			//				// Success reduces backoff, failure increases it
			//				currentBackoffPeriod = currentBackoffPeriod +
			//					random.nextInt(defaultBackoffPeriod);
			//				if(currentBackoffPeriod > Integer.MAX_VALUE/2)
			//					currentBackoffPeriod = Integer.MAX_VALUE/2;
			//				enteredBackoffTime = now;
			//				Core.logger.log(this, "Backing off "+this+" for
			// "+currentBackoffPeriod+
			//						"ms", Logger.MINOR);
			//			}
		}
		dirtied();
	}

	public void transferFailed(Key key, long time, long size) {
		ngrt.singleHopPQueryRejected.report(0.0);
		rpTransferFailed.report(1.0);
		ngrt.reportEarlyTimeout(false, this);
		rpQueryRejected.report(0.0);
		rpEarlyTimeout.report(0.0);
		epTransferFailed.reportProbability(key, 1.0);
		rpSearchFailed.report(0.0); // the search succeeded
		ngrt.singleHopPSearchFailed.report(0);
		epDNF.reportProbability(key, 0);
		rpDNF.report(0.0);
		// transferFailed is a peer event to dataNotFound, transferSucceeded
		// and searchFailed
		if (time > 10 && size > 16384)
			erTransferFailed.reportTransferRate(
				key,
				((double) size) / ((double) time));
		if (logDEBUG) {
			Core.logger.log(
				this,
				"Transfer failed in "
					+ time
					+ "ms ("
					+ size
					+ " bytes) on "
					+ getReference(),
				new Exception("debug"),
				Logger.DEBUG);
		}
		lastAccessedTime = lastPartialSuccessTime = System.currentTimeMillis();
		synchronized (this) {
			routeAccesses++;
			totalHits++;
			reduceBackoff(lastAccessedTime);
		}
		dirtied();
	}

	public void dataNotFound(Key key, long searchTime, int htl) {
		ngrt.singleHopPQueryRejected.report(0.0);
		rpQueryRejected.report(0.0);
		rpEarlyTimeout.report(0.0);
		ngrt.reportEarlyTimeout(false, this);
		epDNF.reportProbability(key, 1.0F);
		rpDNF.report(1.0F);
		rpSearchFailed.report(0.0);
		ngrt.singleHopPSearchFailed.report(0.0);
		// htl IS > 0
		etDNF.reportTime(key, searchTime / htl);
		if (logDEBUG) {
			Core.logger.log(
				this,
				"Data Not Found in " + searchTime + "ms on " + getReference(),
				new Exception("debug"),
				Logger.DEBUG);
		}
		lastAccessedTime = lastPartialSuccessTime = System.currentTimeMillis();
		synchronized (this) {
			routeAccesses++;
			totalHits++;
			reduceBackoff(lastAccessedTime);
		}
		dirtied();
	}

	public void transferSucceeded(
		Key key,
		long searchTime,
		int htl,
		double rate) {
		ngrt.singleHopPQueryRejected.report(0.0);
		rpQueryRejected.report(0.0);
		rpEarlyTimeout.report(0.0);
		rpTransferFailed.report(0.0);
		epTransferFailed.reportProbability(key, 0.0);
		ngrt.reportEarlyTimeout(false, this);
		if (logDEBUG) {
			Core.logger.log(
				this,
				"transferSucceeded("
					+ key
					+ ","
					+ searchTime
					+ ","
					+ htl
					+ ","
					+ rate
					+ ") on "
					+ this,
				Logger.DEBUG);
		}
		lastSuccessTime =
			lastPartialSuccessTime =
				lastAccessedTime = System.currentTimeMillis();
		synchronized (this) {
			successes++;
			routeAccesses++;
			totalHits++;
			reduceBackoff(lastAccessedTime);
		}
		epDNF.reportProbability(key, 0);
		rpDNF.report(0.0);
		etSuccessSearch.reportTime(key, searchTime);
		rpSearchFailed.report(0.0); // the search succeeded
		ngrt.singleHopPSearchFailed.report(0.0);
		if (rate > 0.0) {
			if (logDEBUG) {
				Core.logger.log(
					this,
					"Logging transfer rate of " + rate + " bytes per second",
					rate > 400000 ? Logger.NORMAL : Logger.DEBUG);
			}
			erTransferSuccess.reportTransferRate(key, rate * 0.001);
			// bytes/ms
		}
		dirtied();
	}

	/**
	 * If we are not currently backed off, reduce the backoff interval. If we
	 * are, do nothing. Call synchronized.
	 */
	synchronized private void reduceBackoff(long now) {
		if (!(enteredBackoffTime + currentBackoffPeriod > now)) {
			// Not currently backed off
			// Reduce backoff interval
			// Formula: tBackoff -> tBackoff / (1 + rand)
			double factor =
				1.0 + (random.nextDouble() * (successBackoffDivisor - 1.0));
			currentBackoffPeriod =
				(int) (currentBackoffPeriod
					/ (1.0 + random.nextDouble()));
			if (currentBackoffPeriod < defaultBackoffPeriod)
				currentBackoffPeriod = defaultBackoffPeriod;
			if(logMINOR) Core.logger.log(
				this,
				"Reducing backoff period to "
					+ currentBackoffPeriod
					+ "ms for "
					+ this,
				Logger.MINOR);
		}
	}

	public long lastConnectTime() {
		return lastConnectTime;
	}

	public long lastConnectTryTime() {
		return lastConnectTryTime;
	}

	public int successes() {
		return successes;
	}

	public long lastSuccessTime() {
		return lastSuccessTime;
	}

	public int connectTries() {
		return connectTries;
	}

	public int connectSuccesses() {
		return connectSuccesses;
	}

	public int consecutiveFailedConnects() {
		return consecutiveFailedConnects;
	}

	public long lastAccessedTime() {
		return lastAccessedTime;
	}

	public long createdTime() {
		return createdTime;
	}

	public void writeDataTo(DataOutputStream out) throws IOException {
		if(logDEBUG)
			Core.logger.log(this, "Writing data for "+this, Logger.DEBUG);
		out.writeInt(9); // version number
		rpConnectFailed.writeDataTo(out);
		rpSearchFailed.writeDataTo(out);
		rpDNF.writeDataTo(out);
		rpTransferFailed.writeDataTo(out);
		rtConnectFailed.writeDataTo(out);
		rtConnectSuccess.writeDataTo(out);
		rtSearchFailed.writeDataTo(out);
		rtQueryRejected.writeDataTo(out);
		rpQueryRejected.writeDataTo(out);
		rtEarlyTimeout.writeDataTo(out);
		rpEarlyTimeout.writeDataTo(out);
		etSuccessSearch.writeDataTo(out);
		erTransferSuccess.writeDataTo(out);
		erTransferFailed.writeDataTo(out);
		epTransferFailed.writeDataTo(out);
		epDNF.writeDataTo(out);
		etDNF.writeDataTo(out);
		out.writeInt(successes);
		out.writeLong(lastSuccessTime);
		out.writeLong(lastAccessedTime);
		out.writeLong(createdTime);
		out.writeLong(routeAccesses);
		out.writeLong(totalHits);
	}

	public int getDataLength() {
		return INT_SIZE 
		+ rpConnectFailed.getDataLength()
		+ rpConnectFailed.getDataLength()
		+ rpSearchFailed.getDataLength()
		+ rpDNF.getDataLength()
			+ rpTransferFailed.getDataLength()
		+ rtConnectFailed.getDataLength()
		+ rtConnectSuccess.getDataLength()
		+ rtSearchFailed.getDataLength()
		+ rtQueryRejected.getDataLength()
		+ rpQueryRejected.getDataLength()
		+ etSuccessSearch.getDataLength()
		+ erTransferSuccess.getDataLength()
		+ erTransferFailed.getDataLength()
		+ epTransferFailed.getDataLength()
		+ epDNF.getDataLength()
		+ etDNF.getDataLength()
		+ INT_SIZE
		+ LONG_SIZE
		+ LONG_SIZE
		+ LONG_SIZE
		+ LONG_SIZE;
	}

	//if you change this, change REF_COMPARATOR's indices!!!
	public final static String[] REF_PROPERTIES =
		//public because it's accessed from NGRouting's getSnapshot()
	{
		"Address",
		"Typical Normalized Estimate",
		"Average Raw Estimate",
		"Connection Probability",
		"Estimate Graph",
		"Consecutive Failures",
		"Connection Attempts",
		"Successful Connections",
		"Last Attempt",
		"Successful Transfers",
		"Connection Fail Time",
		"Connection Success Time",
		"NodeReference",
		"Node Version",
		"Search died probability",
		"Lowest transfer died probability",
		"Highest transfer died probability",
		"Search died time",
		"Open Outbound Connections",
		"Open Inbound Connections",
		"Minimum Transfer Rate",
		"Maximum Transfer Rate",
		"Last Request Time",
		"Backed Off Until",
		"Minimum Request Interval",
		"Average Request Interval",
		"Identity" };

	public final static class REF_COMPARATOR implements Comparator {
		public int compare(Object o1, Object o2) {
			if (o1 instanceof Object[] && o2 instanceof Object[]) {
				Object[] oa1 = (Object[]) o1;
				Object[] oa2 = (Object[]) o2;
				final int connA =
					((Integer) (oa1[18])).intValue()
						+ ((Integer) (oa1[19])).intValue();
				final int connB =
					((Integer) (oa2[18])).intValue()
						+ ((Integer) (oa2[19])).intValue();
				// Connected nodes go at the top
				if (connA == 0 && connB > 0)
					return 1;
				if (connA > 0 && connB == 0)
					return -1;
				// Next sort by successes
				final Integer succA = ((Integer) oa1[9]);
				final Integer succB = ((Integer) oa2[9]);
				return succB.compareTo(succA);
			} else
				return 0;
		}
	}

	public void getDiagProperties(
		PropertyArray pa,
		RecentRequestHistory rrh,
		long typicalFileSize) {
		long now = System.currentTimeMillis();
	    if(logDEBUG)
	        Core.logger.log(this, "getDiagProperties() on "+this+" at "+
	                now, Logger.DEBUG);
		NodeReference nr = getReference();
		pa.addToBuilder("Address",
				nr == null ? "(null)" : nr.firstPhysicalToString());
		pa.addToBuilder(
				"Typical Normalized Estimate",
				new Double(typicalEstimate(rrh, typicalFileSize)));
		pa.addToBuilder("Average Raw Estimate",
		        new Double(averageEstimate(rrh, typicalFileSize)));
		pa.addToBuilder(
			"Connection Probability",
			new Float(1.0 - rpConnectFailed.currentValue()));
		pa.addToBuilder(
			"Consecutive Failures",
			new Long(consecutiveFailedConnects));
		pa.addToBuilder("Connection Attempts", new Long(connectTries));
		pa.addToBuilder("Successful Connections", new Long(connectSuccesses));
		long secsSinceLastAttempt = -1;
		if (connectTries > 0) {
			secsSinceLastAttempt = (now - lastConnectTryTime) / 1000;
		}
		pa.addToBuilder("Last Attempt", new Long(secsSinceLastAttempt));
		pa.addToBuilder("Successful Transfers", new Integer(successes));
		pa.addToBuilder(
			"Connection Fail Time",
			new Integer((int) rtConnectFailed.currentValue()));
		pa.addToBuilder(
			"Connection Success Time",
			new Integer((int) rtConnectSuccess.currentValue()));
		pa.addToBuilder("NodeReference", nr);
		pa.addToBuilder("Node Version",
		        nr == null ? "unknown" : nr.getVersion());
		pa.addToBuilder(
			"Search died probability",
			new Float(rpSearchFailed.currentValue()));
		pa.addToBuilder(
			"Lowest transfer died probability",
			new Float(minPTransferFailed()));
		pa.addToBuilder(
			"Highest transfer died probability",
			new Float(maxPTransferFailed()));
		pa.addToBuilder(
			"Search died time",
			new Float(rtSearchFailed.currentValue()));
		pa.addToBuilder(
			"Open Outbound Connections",
			new Integer(ngrt.countOutboundConnections(id)));
		pa.addToBuilder(
			"Open Inbound Connections",
			new Integer(ngrt.countInboundConnections(id)));
		pa.addToBuilder(
			"Minimum Transfer Rate",
			erTransferSuccess.lowestString());
		pa.addToBuilder(
			"Maximum Transfer Rate",
			erTransferSuccess.highestString());
		synchronized(this.rateLimitingSync) {
			long diff = lastRequestSentTime <= 0 ? 0 :
			    Math.max(now - lastRequestSentTime, 0);
			pa.addToBuilder("Last Request Time", new Long(diff));
			pa.addToBuilder("Backed Off Until", new Long(timeTillNextSendWindow(now)));
			pa.addToBuilder("Minimum Request Interval", new Double(minRequestInterval));
			pa.addToBuilder("Average Request Interval", new Double(this.averageRequestInterval.currentValue()));
		}
		pa.addToBuilder("Identity", id);
		pa.commitBuilder();
	}

	public GraphDataSet createGDS(
		int samples,
									int htl,
									long size,
									double pDataNonexistant	) {
		GraphDataSet gds = new GraphDataSet();
		
		BigInteger at = BigInteger.ZERO;
		BigInteger keyspaceLastKey = Key.KEYSPACE_SIZE.subtract(BigInteger.ONE);
		BigInteger keyspaceStepLength =
			keyspaceLastKey.divide(BigInteger.valueOf(samples));
		
		//Prevent everyone from affecting the estimator while we pull data out
		//A change during the sampling makes the graphs look really strange.
		synchronized (StandardNodeEstimator.this) {
			for (int i = 0; i < samples; i++) {
				double y =
					estimate(
						new Key(at),
									  htl,
									  size,
						0,
						pDataNonexistant,
						true,
						null);
				gds.addPoint(i, y);
				at = at.add(keyspaceStepLength);
			}
		}
		return gds;
	}
	
	public GDSList createGDSL (int samples, int graphMode, Color c) {
		GDSList gdsl = new GDSList();
		
		if (c == null)
			c = new Color(0, 0, 0);

		//Pregenerate these values to save us some later calculations
		int htl = 10;
		int size = 10000;
		Key half = new Key(Key.HALFKEYSPACE_SIZE);
		double pDataNonexistant = ngrt.pDataNonexistant(half);
		
		switch (graphMode) {
		case 0 :
			{
					gdsl.add(
						createGDS(
							samples,
									   htl,
									   size,
									   pDataNonexistant ),
						   c );
				break;
			}
		case 1 :
			{
				for (int i = 0; i <= 25; i = i + 2) {
						gdsl.add(createGDS(samples, i, /* htl => i */
						        size, pDataNonexistant), c);
					c = Color.add(c, new Color(10, 10, 10));
					//The larger the htl, the lighter the color
				}
				break;
			}
		case 2 :
			{
				for (int i = 1024; i <= 1024 * 1024; i = i * 2) {
						gdsl.add(createGDS(samples, htl, i, /* size => i */
						        pDataNonexistant), c);
					c = Color.add(c, new Color(10, 10, 10));
					//The larger the keysize, the lighter the color
				}
				break;
			}
		}
		return gdsl;
	}
	
	public KeyspaceEstimator getEstimator(String graphName) {
		if (graphName.equals("tSuccessSearch"))
			return etSuccessSearch;
		else if (graphName.equals("rTransferSuccess"))
			return erTransferSuccess;
		else if (graphName.equals("pDNF"))
			return epDNF;
		else if (graphName.equals("tDNF"))
			return etDNF;
		else if (graphName.equals("rTransferFailed"))
			return erTransferFailed;
		else if (graphName.equals("pTransferFailed"))
			return epTransferFailed;
		else
			return null;
	}
	
	public GDSList createGDSL2(int samples, Hashtable lineColors) {
		GDSList gdsl = new GDSList();

		for(Enumeration e = lineColors.keys(); e.hasMoreElements(); ) {
			String graphName = (String) e.nextElement(); 
			Color c = (Color) lineColors.get(graphName);
			KeyspaceEstimator es = getEstimator(graphName);
			if (es != null) {
				gdsl.add ( es.createGDS ( samples, 0 ), c );
			}
			//FIXME what to do when es is null?
		}
		return gdsl;
	}
	
	protected class StandardHTMLReportTool
		implements NodeEstimator.HTMLReportTool {

		String[] estimatorNames =
			new String[] {
				"tSuccessSearch",
				"rTransferSuccess",
				"pDNF",
				"tDNF",
				"rTransferFailed",
				"pTransferFailed" };

		public void toHtml(PrintWriter pw, String imagePrefix)
			throws IOException {

		    HtmlTemplate boxTemplate=null;
		    HtmlTemplate titleBoxTemplate=null;
		    try {
		        boxTemplate=HtmlTemplate.createTemplate("box.tpl");
			    titleBoxTemplate=HtmlTemplate.createTemplate("titleBox.tpl");
		    } catch (IOException e) {
		        Core.logger.log(this,"Couldn't load templates", e, Logger.NORMAL);
		    }
		    
		    StringWriter psw=new StringWriter();
		    PrintWriter ppw=new PrintWriter(psw);
		    
		    ppw.println("<table class=\"nodeStats\">");
		    ppw.println("<tr><td>Last connected</td>");
			long now = System.currentTimeMillis();
			ppw.println(
				"<td>"
					+ (lastConnectTime == -1
						? "never"
						: (Long.toString((now - lastConnectTime) / 1000)
							+ "s ago"))
					+ "</td></tr>");
			ppw.println("<tr><td>Last attempted</td>");
			ppw.println(
				"<td>"
					+ (lastConnectTryTime == -1
						? "never"
						: (Long.toString((now - lastConnectTryTime) / 1000)
							+ "s ago"))
					+ "</td></tr>");
			ppw.println("<tr><td>Connection attempts</td>");
			ppw.println("<td>" + connectTries + "</td></tr>");
			ppw.println("<tr><td>Connection successes</td>");
			ppw.println("<td>" + connectSuccesses + "</td></tr>");
			ppw.println("<tr><td>Consecutive failed connections</td>");
			ppw.println("<td>" + consecutiveFailedConnects + "</td></tr>");
			ppw.println("<tr><td>Probability of connection failure</td>");
			ppw.println("<td>" + rpConnectFailed.currentValue() + "</td></tr>");
			ppw.println("<tr><td>Estimated time for connection failure</td>");
			ppw.println(
				"<td>"
					+ (int) (rtConnectFailed.currentValue())
					+ "ms</td></tr>");
			ppw.println("<tr><td>Estimated time for connection success</td>");
			ppw.println(
				"<td>"
					+ (int) (rtConnectSuccess.currentValue())
					+ "ms</td></tr>");
			ppw.println("<tr><td>Probability of QueryRejected</td>");
			ppw.println("<td>" + toHumanString(rpQueryRejected) + "</td></tr>");
			ppw.println("<tr><td>Estimated time for QueryRejected</td>");
			ppw.println("<td>" + toHumanString(rtQueryRejected) + "</td></tr>");
			ppw.println("<tr><td>Probability of early timeout</td>");
			ppw.println("<td>" + toHumanString(rpEarlyTimeout) + "</td></tr>");
			ppw.println("<tr><td>Estimated time for early timeout</td>");
			ppw.println("<td>" + toHumanString(rtEarlyTimeout) + "</td></tr>");
			ppw.println(
				"<tr><td>Probability of search failure (late timeout)</td>");
			ppw.println("<td>" + toHumanString(rpSearchFailed) + "</td></tr>");
			ppw.println("<tr><td>Estimated time for search failure</td>");
			ppw.println(
				"<td>"
					+ (int) (rtSearchFailed.currentValue())
					+ "ms</td></tr>");
			ppw.println("<tr><td>Overall probability of DataNotFound</td>");
			ppw.println("<td>" + toHumanString(rpDNF) + "</td></tr>");
			ppw.println("<tr><td>Overall probability of transfer failure given transfer</td>");
			ppw.println("<td>" + toHumanString(rpTransferFailed) + "</td></tr>");
			ppw.println("<tr><td>Successful transfers</td>");
			ppw.println("<td>" + successes + "</td></tr>");
			ppw.println("<tr><td>Age</td>");
			long age = now - createdTime;
			ppw.println(
				"<td>"
					+ freenet.support.TimeConverter.durationToString(age)
					+ "</td></tr>");
			ppw.println("<tr><td>Last access</td>");
			ppw.println(
				"<td>"
					+ freenet.support.TimeConverter.durationToString(
						now - lastAccessedTime)
					+ "</td></tr>");
			ppw.println("<tr><td>Last success</td>");
			ppw.println(
				"<td>"
					+ freenet.support.TimeConverter.durationToString(
						now - lastSuccessTime)
					+ "</td></tr>");
			ppw.println("<tr><td>Routing hits</td>");
			ppw.println("<td>" + Long.toString(routeAccesses) + "</td></tr>");
			ppw.println("<tr><td>Total hits</td>");
			ppw.println("<td>" + Long.toString(totalHits) + "</td></tr>");
			ppw.println("</table>");

			boxTemplate.set("CONTENT", psw.toString());
			boxTemplate.toHtml(pw);

			psw=new StringWriter();
			ppw=new PrintWriter(psw);
			
			titleBoxTemplate.set("TITLE","Keyspace estimates");
			
			
			// FIXME: reinstate when it doesn't crash the JVM
			//			pw.println("<img src=\""+imagePrefix+"overlayedcomposite"+
			//					"&width=1024&height=480\" width=\"1024\" height=\"480\"><br/>");
			
			GDSList gdsl = createGDSL(20, 0, null);
			//TODO: Ineffectve. Fix when appropriate
			NumberFormat nfp = NumberFormat.getPercentInstance();

			ppw.println("estimate()<br />");
			ppw.println("Minimum: " + String.valueOf(gdsl.lowest) + "ms at "+
			        nfp.format(((double)gdsl.lowestPointX) / 20) + "<br />");
			ppw.println("Maximum: " + String.valueOf(gdsl.highest) + "ms at "+
			        nfp.format(((double)gdsl.highestPointX) / 20) + "<br />");
			ppw.println("Clipped, showing estimate detail<br />");
			ppw.println(
				"<img src=\""
					+ imagePrefix
					+ "composite"
					+ "&width=640&height=480&clippoints=true\" width=\"640\" height=\"480\" /><br />");

			titleBoxTemplate.set("CONTENT",psw.toString());
			titleBoxTemplate.toHtml(pw);

			psw=new StringWriter();
			ppw=new PrintWriter(psw);

			titleBoxTemplate.set("TITLE","Estimators");

			for (int i = 0; i < estimatorNames.length; i++) {
				String name = estimatorNames[i];
				ppw.println(name + "<br />");
				KeyspaceEstimator te = getEstimator(name);
				KeyspaceEstimator.HTMLReportTool reportTool =
					te.getHTMLReportingTool();
				ppw.println("Minimum: " + te.lowestString() + "<br />");
				ppw.println("Maximum: " + te.highestString() + "<br />");
				reportTool.dumpHtml(ppw);
				ppw.println("With last 64 points:<br/>");
				ppw.println(
					"<img src=\""
						+ imagePrefix
						+ name
						+ "\" width=\"640\" height=\"480\" /><br />");
				ppw.println("Clipped, showing estimator detail:<br/>");
				ppw.println(
					"<img src=\""
						+ imagePrefix
						+ name
						+ "&clippoints=true\" width=\"640\" height=\"480\" /><br />");
			}

			titleBoxTemplate.set("CONTENT",psw.toString());
			titleBoxTemplate.toHtml(pw);

			psw=new StringWriter();
			ppw=new PrintWriter(psw);

			titleBoxTemplate.set("TITLE","Estimators as FieldSet");
			
			
			ppw.println("<pre>");
			FieldSet fs = toFieldSet();
			ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
			fs.writeFields(new WriteOutputStream(baos));
			String s = baos.toString();
			ppw.println(s);
			ppw.println("</pre>");
			ppw.println("(" + s.length() + " bytes)");

			titleBoxTemplate.set("CONTENT",psw.toString());
			titleBoxTemplate.toHtml(pw);
		}

		/**
		 * @param rpDNF
		 * @return
		 */
		private String toHumanString(RunningAverage rp) {
			if(rp instanceof ExtraDetailRunningAverage) {
				String s = ((ExtraDetailRunningAverage)rp).extraToString();
				if(s.length() > 0)
					return rp.currentValue() + " (" + s + ")";
			}
			return Double.toString(rp.currentValue());
		}

		public KeyspaceEstimator getEstimator(String graphName) {
			if (graphName.equals("tSuccessSearch"))
				return etSuccessSearch;
			else if (graphName.equals("rTransferSuccess"))
				return erTransferSuccess;
			else if (graphName.equals("pDNF"))
				return epDNF;
			else if (graphName.equals("tDNF"))
				return etDNF;
			else if (graphName.equals("rTransferFailed"))
				return erTransferFailed;
			else if (graphName.equals("pTransferFailed"))
				return epTransferFailed;
			else
				return null;
		}

//		public int estimatorType(String graphName) {
//			if (graphName.equals("tSuccessSearch"))
//				return KeyspaceEstimator.TIME;
//			else if (graphName.equals("rTransferSuccess"))
//				return KeyspaceEstimator.TRANSFER_RATE;
//			else if (graphName.equals("pDNF"))
//				return KeyspaceEstimator.PROBABILITY;
//			else if (graphName.equals("tDNF"))
//				return KeyspaceEstimator.TIME;
//			else if (graphName.equals("rTransferFailed"))
//				return KeyspaceEstimator.TRANSFER_RATE;
//			else if (graphName.equals("pTransferFailed"))
//				return KeyspaceEstimator.PROBABILITY;
//			else
//				throw new IllegalArgumentException();
//		}
//
		public void drawCombinedGraphBMP(
			int width,
			int height,
			HttpServletResponse resp)
			throws IOException {
			Bitmap bmp = new Bitmap(width, height);
			GDSList gdsl = createGDSL(width, 0, new Color(0,0,0));
			gdsl.drawGraphsOnImage(bmp);
			DibEncoder.drawBitmap(bmp, resp);
		}
		
		/*
		 * (non-Javadoc)
		 * 
		 * @see freenet.node.rt.NodeEstimator.HTMLReportTool#graphNiceName(int)
		 */
		public String graphNiceName(String graphName) {
			if (graphName.equals("tSuccessSearch"))
				return "Time for successful search";
			else if (graphName.equals("rTransferSuccess"))
				return "Transfer rate";
			else if (graphName.equals("pDNF"))
				return "Probability of DNF";
			else if (graphName.equals("tDNF"))
				return "Time for DNF"; 
			else if (graphName.equals("rTransferFailed"))
				return "Effective transfer rate on failure";
			else if (graphName.equals("pTransferFailed"))
				return "Probability of transfer failure";
			else
				return null;
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.rt.NodeEstimator#getHTMLReportingTool()
	 */
	public HTMLReportTool getHTMLReportingTool() {
		return new StandardHTMLReportTool();
	}

	public long highestEstimatedSearchTime() {
		return (long) etSuccessSearch.highest();
	}

	public long highestDNFTime() {
		return (long) etDNF.highest();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.rt.NodeEstimator#isBackedOff()
	 */
	public boolean isBackedOff() {
		long now = System.currentTimeMillis();
		if (enteredBackoffTime + currentBackoffPeriod > now)
			return true;
		else
			return false;
	}

	public int backoffLength() {
		long now = System.currentTimeMillis();
		long end = enteredBackoffTime + currentBackoffPeriod;
		if (end < now)
			return 0;
		return (int) (end - now);
	}

	public long routeAccesses() {
		return routeAccesses;
	}

	public long totalHits() {
	    return totalHits;
	}

	public double tQueryRejected() {
		return rtQueryRejected.currentValue();
	}

	public double pQueryRejected() {
		return rpQueryRejected.currentValue();
	}

	public double pEarlyTimeout() {
		return rpEarlyTimeout.currentValue();
	}

	public double tEarlyTimeout() {
		return rtEarlyTimeout.currentValue();
	}

    public double minRequestInterval() {
        return minRequestInterval;
    }
}
