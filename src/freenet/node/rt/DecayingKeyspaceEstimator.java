package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;

import freenet.Core;
import freenet.FieldSet;
import freenet.Key;
import freenet.support.Unit;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.graph.Color;
import freenet.support.graph.GDSList;
import freenet.support.graph.GraphDataSet;

public class DecayingKeyspaceEstimator extends NumericKeyKeyspaceEstimator {

    public Object clone() {
        return new DecayingKeyspaceEstimator(this);
    }
    
	protected static final double AGE_MAX = 10.0;
	protected RecentReports recent;
	protected RoutingPointStore store;
	protected final static boolean keepHistory = false;

	private static final int TIMEBYTES = 4, BYTES = Key.KEYBYTES + TIMEBYTES;
	private static final int DEFAULT_ACCURACY = 16;
	private static final BigInteger THREE = BigInteger.valueOf(3);

	public DecayingKeyspaceEstimator(DataInputStream i, Unit type, String name)
		throws IOException {
	    super(type, name);
		int version = i.readInt();
		if (version != 2)
			throw new IOException("unrecognized version " + version);
		if (keepHistory)
			store =
				new HistoryKeepingRoutingPointStore(
					i,
					type.getMax(),
					type.getMin());
		else
			store =
				new RoutingPointStore(
					i,
					type.getMax(),
					type.getMin());
		try {
			recent = new RecentReports(i);
		} catch (IOException e) {
			Core.logger.log(
				this,
				"Failed reading estimator report history from InputStream, using empty history",
				e,
				Logger.ERROR);
			recent = new RecentReports();
			// Not fatal - possibly upgrading
		}

		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	}

	public synchronized void writeDataTo(DataOutputStream o)
		throws IOException {
		o.writeInt(2);
		// current version. Using negative numbers for compatibility.
		store.writeTo(o);
		recent.writeTo(o);
	}

	public int getDataLength() {
		return store.getDataLength();
	}

	public DecayingKeyspaceEstimator(double initTime, int accuracy,
	        Unit type, String name) {
	    super(type, name);
		if (keepHistory)
			store = new HistoryKeepingRoutingPointStore(accuracy, initTime);
		else
			store = new RoutingPointStore(accuracy, initTime);
		recent = new RecentReports();
	}

	/**
	 * Create a DecayingKeyspaceEstimator with an initial specialization
	 * by drawing a flat line then reporting a given value several times.
	 * @param k the key to report. 
	 * @param flatValue the initial flat value.
	 * @param spikeValue the value to report.
	 * @param accuracy
	 */
	public DecayingKeyspaceEstimator(Key k, double flatValue, double spikeValue, int accuracy,
	        Unit type, String name) {
		// Flat line at high, then report low several times
		// Result: we get a nice exponential curve with more points
		// closer to low, which is the specialization, and less points closer
		// to high.
		this(flatValue, accuracy, type, name);
		for (int i = 0; i < 5; i++)
			report(k, spikeValue);
		store.clearAging();
		//		if(keepHistory)
		//			store = new HistoryKeepingRoutingPointStore(k,high,low,accuracy);
		//		else
		//		store = new RoutingPointStore(k,high,low,accuracy);
		//		recent = new RecentReports();
	}

	public DecayingKeyspaceEstimator(int accuracy, Unit type, String name) {
		this(0, accuracy, type, name);
		// Let's evenly distribute the keys across the whole
		// keyspace and leave the time values at zero.
		store.distributeKeysEvenly();
	}

	protected DecayingKeyspaceEstimator(RoutingPointStore rps, Unit type, String name) {
	    super(type, name);
		this.store = rps;
		this.recent = new RecentReports();
	}

	/**
     * @param estimator
     */
    public DecayingKeyspaceEstimator(DecayingKeyspaceEstimator e) {
        super(e);
        this.store = (RoutingPointStore) e.store.clone();
        this.recent = (RecentReports) e.recent.clone();
    }

	public double guessTime(Key k) {
		return guess(k); // resolution 1ms
	}

	public double guessProbability(Key k) {
	    return guess(k);
	}

	/**
	 * Guess the expected transfer rate for a given key.
	 * 
	 * @return the transfer rate in bytes per MILLISECOND
	 */
	public double guessTransferRate(Key k) {
	    return guess(k);
	}

	public void reportTime(Key k, long millis) {
		report(k, millis);
	}

	public void reportProbability(Key k, double p) {
		report(k, p);
	}

	public void reportTransferRate(Key k, double rate) {
		report(k, rate);
	}

	public double guessRaw(Key k) {
		return guess(k);
	}
	
	/**
	 * @param lowerBound
	 *            lowest keypace location of estimator points to modify
	 * @param upperBound
	 *            uppermost keypace location of estimator points to modify
	 * @param center
	 *            keyspace location of report
	 * @param usec
	 *            reported value
	 * @param initialSteps
	 *            number of estimator points which have been modified based on
	 *            this report
	 * @param lowerBoundPos
	 *            position of first possible location in store to modify
	 * @param upperBoundPos
	 *            position of last possible location in store to modify
	 * @return total number of estimator points which have been modified
	 *         (including initialSteps)
	 */
	protected synchronized int reportDecreasing(
		BigInteger lowerBound,
		BigInteger upperBound,
		BigInteger center,
		double usec,
		int initialSteps,
		int lowerBoundPos,
		int upperBoundPos) {
		if (logDEBUG)
			Core.logger.log(
				this,
				"reportDecreasing("
					+ HexUtil.biToHex(lowerBound)
					+ ","
					+ HexUtil.biToHex(upperBound)
					+ ","
					+ HexUtil.biToHex(center)
					+ ","
					+ initialSteps
					+ ","
					+ lowerBoundPos
					+ ","
					+ upperBoundPos
					+ ")",
				new Exception("debug"),
				Logger.DEBUG);
		store.dumpLog();
		upperBoundPos = Math.min(upperBoundPos, store.length() - 1);
		lowerBoundPos = Math.min(lowerBoundPos, store.length() - 1);
		lowerBoundPos = Math.max(lowerBoundPos, 0);
		upperBoundPos = Math.max(upperBoundPos, 0);
		if (usec < 0)
			throw new IllegalArgumentException("usec " + usec + " negative!");
		// Loop from lowerBoundPos to upperBoundPos, inclusive
		// We are increasing
		// inc shiftAmount each time we move a point
		int shiftAmount = initialSteps;

		upperBoundPos =
			store.findFirstSmaller_reverse(upperBound, upperBoundPos);
		if (upperBoundPos < 0)
			return initialSteps;

		lowerBoundPos = store.findFirstLarger(lowerBound, lowerBoundPos);
		if (lowerBoundPos < 0)
			return initialSteps;

		if (lowerBoundPos > upperBoundPos)
			return initialSteps;
		for (int i = lowerBoundPos; i <= upperBoundPos; i++) {
			RoutingPointStore.RoutingPointEditor k = store.checkout(i);
			if (k.getEstimate() < 0)
				Core.logger.log(
					this,
					"time["
						+ i
						+ "] = "
						+ k.getEstimate()
						+ " - NEGATIVE TIME!",
					Logger.ERROR);
			if (logDEBUG)
				Core.logger.log(
					this,
					"Trying key["
						+ i
						+ "]: "
						+ HexUtil.biToHex(k.getKey())
						+ ","
						+ k.getEstimate(),
					Logger.DEBUG);

			moveKeyDecreasing(k, shiftAmount, center);
			moveEstimate(k, shiftAmount, usec);

			if (logDEBUG)
				Core.logger.log(
					this,
					"key["
						+ i
						+ "] now: "
						+ HexUtil.biToHex(k.getKey())
						+ ","
						+ k.getEstimate(),
					Logger.DEBUG);
			if (k.getEstimate() < 0)
				Core.logger.log(
					this,
					"time["
						+ i
						+ "] = "
						+ k.getEstimate()
						+ " - NEGATIVE TIME!",
					Logger.ERROR);
			k.setAge(k.getAge() + 1.0 / (1 << shiftAmount));
			k.commit();
			shiftAmount++;
		}
		store.dumpLog();
		return shiftAmount;
	}

	protected synchronized int reportIncreasing(
		BigInteger lowerBound,
		BigInteger upperBound,
		BigInteger center,
		double usec,
		int initialSteps,
		int lowerBoundPos,
		int upperBoundPos) {
		if (logDEBUG)
			Core.logger.log(
				this,
				"reportIncreasing("
					+ HexUtil.biToHex(lowerBound)
					+ ','
					+ HexUtil.biToHex(upperBound)
					+ ','
					+ HexUtil.biToHex(center)
					+ ','
					+ initialSteps
					+ ','
					+ lowerBoundPos
					+ ','
					+ upperBoundPos
					+ ")",
				new Exception("debug"),
				Logger.DEBUG);
		store.dumpLog();
		upperBoundPos = Math.min(upperBoundPos, store.length() - 1);
		lowerBoundPos = Math.min(lowerBoundPos, store.length() - 1);
		lowerBoundPos = Math.max(lowerBoundPos, 0);
		upperBoundPos = Math.max(upperBoundPos, 0);
		if (usec < 0)
			throw new IllegalArgumentException("usec " + usec + " negative!");
		// Loop from lowerBoundPos to upperBoundPos, inclusive
		// We are increasing
		// inc shiftAmount each time we move a point
		int shiftAmount = initialSteps;

		upperBoundPos =
			store.findFirstSmaller_reverse(upperBound, upperBoundPos);
		if (upperBoundPos < 0)
			return initialSteps;

		lowerBoundPos = store.findFirstLarger(lowerBound, lowerBoundPos);
		if (lowerBoundPos < 0)
			return initialSteps;

		if (lowerBoundPos > upperBoundPos)
			return initialSteps;
		for (int i = upperBoundPos; i >= lowerBoundPos; i--) {
			RoutingPointStore.RoutingPointEditor k = store.checkout(i);
			if (logDEBUG)
				Core.logger.log(
					this,
					"Trying key["
						+ i
						+ "]: "
						+ HexUtil.biToHex(k.getKey())
						+ ','
						+ k.getEstimate(),
					Logger.DEBUG);
			if (k.getEstimate() < 0)
				Core.logger.log(
					this,
					"time["
						+ i
						+ "] = "
						+ k.getEstimate()
						+ " - NEGATIVE TIME!",
					Logger.ERROR);

			moveKeyIncreasing(k, shiftAmount, center);
			moveEstimate(k, shiftAmount, usec);

			if (logDEBUG)
				Core.logger.log(
					this,
					"key["
						+ i
						+ "] now: "
						+ HexUtil.biToHex(k.getKey())
						+ ','
						+ k.getEstimate(),
					Logger.DEBUG);
			k.setAge(k.getAge() + 1.0 / (1 << shiftAmount));
			shiftAmount++;
			if (k.getEstimate() < 0)
				Core.logger.log(
					this,
					"time["
						+ i
						+ "] = "
						+ k.getEstimate()
						+ " - NEGATIVE TIME!",
					Logger.ERROR);
			k.commit();
		}
		store.dumpLog();
		return shiftAmount;
	}

	/**
	 * moves estimator point "leftwards" towards the reportedKey
	 * 
	 * @param k
	 * @param shiftAmount
	 * @param reportedKey
	 */
	void moveKeyDecreasing(
		RoutingPointStore.RoutingPointEditor k,
		int shiftAmount,
		BigInteger reportedKey) {
		k.setAge(Math.min(k.getAge(), AGE_MAX));
		BigInteger diff =
			k.getKey().subtract(reportedKey).mod(Key.KEYSPACE_SIZE);

		// Result = old position + ((old position * age) / (age + 1)
		// >> shiftAmount

		// Use BigDecimal's (yuck!)
		// Shift afterwards
		/*
		 * R = O + ((S-O)/(r+1))*m - where R is the result, O is the old value,
		 * r is the AGE_KLUDGE-adjusted age, m is the shift multiplier
		 */
		// Multiply by 75% to prevent points from collapsing on each other
		BigDecimal fracDiff = new BigDecimal(diff);
		BigDecimal fracAge = new BigDecimal(AGE_KLUDGE(k.getAge()) + 1.0);
		BigDecimal resultDiff = fracDiff.divide(fracAge, BigDecimal.ROUND_DOWN);
		diff =
			resultDiff.toBigInteger().multiply(THREE).shiftRight(
				shiftAmount + 2);

		k.setKey(k.getKey().subtract(diff).mod(Key.KEYSPACE_SIZE));
	}

	/**
	 * moves estimator point "rightwards" towards the reportedKey
	 * 
	 * @param k
	 * @param shiftAmount
	 * @param reportedKey
	 */
	void moveKeyIncreasing(
		RoutingPointStore.RoutingPointEditor k,
		int shiftAmount,
		BigInteger reportedKey) {
		k.setAge(Math.min(k.getAge(), AGE_MAX));

		BigInteger diff =
			reportedKey.subtract(k.getKey()).mod(Key.KEYSPACE_SIZE);
		BigDecimal fracDiff = new BigDecimal(diff);

		BigDecimal fracAge = new BigDecimal(AGE_KLUDGE(k.getAge()) + 1.0);
		BigDecimal resultDiff = fracDiff.divide(fracAge, BigDecimal.ROUND_DOWN);
		diff =
			resultDiff.toBigInteger().multiply(THREE).shiftRight(
				shiftAmount + 2);
		k.setKey(k.getKey().add(diff).mod(Key.KEYSPACE_SIZE));
	}

	// FIXME: Probably would be less alchemical to use Kalman filters instead
	private static final double AGE_KLUDGE_FACTOR = 4;
	// FIXME: Just testing these values right now
	/**
	 * Makes young points even more youthful, while keeping maximum age the
	 * same.
	 */
	private double AGE_KLUDGE(double age) {
		return AGE_MAX * Math.pow(age / AGE_MAX, AGE_KLUDGE_FACTOR);
	}

	// FIXME: Instead of this kludge, we should make DecayingKeyspaceEstimator
	// into an abstract class
	// FIXME: and create Ageing... and SelfAdjustingAgeing... subclasses off of
	// it. (m0davis will do that soon - 2003-12-13)
	double ADJUST_DECAY_FACTOR_KLUDGE(double currentValue) {
		return 1;
		// no adjustment normally (only for probabilities, which should use
		// SelfAdjusting...)
	}

	/**
	 * moves estimator point upwards or downwards towards the reported value
	 * 
	 * @param k
	 * @param shiftAmount
	 * @param reportedValue
	 */
	void moveEstimate(
		RoutingPointStore.RoutingPointEditor k,
		int shiftAmount,
		double reportedValue) {
		double timediff =
			((reportedValue - k.getEstimate())
				* ADJUST_DECAY_FACTOR_KLUDGE(k.getEstimate()));
		if (logDEBUG)
			Core.logger.log(
				this,
				"usec="
					+ reportedValue
					+ ", time[i]="
					+ k.getEstimate()
					+ ", timediff="
					+ timediff,
				Logger.DEBUG);
		if (logDEBUG)
			Core.logger.log(this, "fracTimediff=" + timediff, Logger.DEBUG);
		double resultTimediff = timediff / (AGE_KLUDGE(k.getAge()) + 1.0);
		if (logDEBUG)
			Core.logger.log(
				this,
				"resultTimediff=" + resultTimediff,
				Logger.DEBUG);
		timediff = resultTimediff / (1 << shiftAmount);
		if (logDEBUG)
			Core.logger.log(
				this,
				"timediff after shift = " + timediff,
				Logger.DEBUG);
		timediff = timediff * 3 / 4; 
		if (logDEBUG)
			Core.logger.log(
				this,
				"timediff after multiply by 3/4 = "
					+ timediff
					+ ", estimate = "
					+ k.getEstimate()
					+ ", new estimate = "
					+ k.getEstimate()
					+ timediff,
				Logger.DEBUG);
		k.setEstimate(k.getEstimate() + timediff);
	}

	public double guess(Key k) {
		if (k == null) {
			return 0; //FIXME this is a blind fix to an NPE
		}
		return guess(k.toBigInteger());
	}

	protected final double guess(BigInteger n) {
	    return guess(n, 0);
	}

	public final void report(Key k, double usec) {
		BigInteger n = k.toBigInteger();
		report(n, usec);
	}

	public synchronized void report(BigInteger n, double usec) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);

		if (usec < 0)
			throw new IllegalArgumentException("negative usec in report()");
		if (n == null)
			throw new IllegalArgumentException("invalid key in report()");
		recent.report(n, usec);

		int pos = store.search(n);

		if (logDEBUG)
			Core.logger.log(
				this,
				"report(" + HexUtil.biToHex(n) + ',' + usec + ")",
				Logger.DEBUG);

		store.dumpLog();
		int x = n.compareTo(Key.HALFKEYSPACE_SIZE);
		if (x == 1) {
			if (logDEBUG)
				Core.logger.log(this, "Above half keyspace", Logger.DEBUG);
			// We are above the half way point
			// n ... keyspace, 0 ... n-halfkeyspace moved left
			// n-halfkeyspace ... n moved right
			BigInteger m = n.subtract(Key.HALFKEYSPACE_SIZE);
			int mpos = store.search(m);
			int steps =
				reportDecreasing(
					n,
					Key.KEYSPACE_SIZE,
					n,
					usec,
					0,
					pos + 1,
					store.length() - 1);
			reportDecreasing(BigInteger.ZERO, m, n, usec, steps, 0, mpos);
			reportIncreasing(m, n, n, usec, 0, mpos + 1, store.length() - 1);
			store.sortByKey();
			// if the edge wraps, over the next section, then we orderKeys
			// before the next section is done, we are stuffed
		} else if (x == -1) {
			if (logDEBUG)
				Core.logger.log(this, "Below half keyspace", Logger.DEBUG);

			// We are below the half way point
			// n ... n+halfkeyspace moved left
			// 0 ... n moved right
			// n+halfkeyspace .. keyspace moved right
			BigInteger c = n.add(Key.HALFKEYSPACE_SIZE);
			int cpos = store.search(c);
			reportDecreasing(n, c, n, usec, 0, pos + 1, cpos);
			int steps =
				reportIncreasing(BigInteger.ZERO, n, n, usec, 0, 0, pos);
			reportIncreasing(
				c,
				Key.KEYSPACE_SIZE,
				n,
				usec,
				steps,
				cpos + 1,
				store.length() - 1);
			store.sortByKey();
		} else {
			if (logDEBUG)
				Core.logger.log(this, "Dead on half keyspace", Logger.DEBUG);
			// We are in the exact center
			int mpos = store.search(Key.HALFKEYSPACE_SIZE);
			reportIncreasing(
				BigInteger.ZERO,
				Key.HALFKEYSPACE_SIZE,
				Key.HALFKEYSPACE_SIZE,
				usec,
				0,
				0,
				mpos);
			reportDecreasing(
				Key.HALFKEYSPACE_SIZE,
				Key.KEYSPACE_SIZE,
				Key.HALFKEYSPACE_SIZE,
				usec,
				0,
				mpos + 1,
				store.length() - 1);
			store.sortByKey();
		}
		if (logDEBUG)
			Core.logger.log(
				this,
				"report(" + HexUtil.biToHex(n) + ',' + usec + ") completed",
				Logger.DEBUG);
		// Descending...
		//         if (pos > 0) {
		//             for (int g = 0, i = pos; ++g < KEYBYTES << 3 && i >= 0; i--)
		//                 key[i] = key[i].add(n.subtract(key[i]).shiftRight(g));

		//             for (int g = 0, i = pos; ++g < 32 && i >= 0; i--)
		//                 time[i] += (usec - time[i]) >> g;
		//         }
	}

	protected double guess(BigInteger n, int age) {
		RoutingPointStore.NeighbourRoutingPointPair b;
		if (age == 0)
			b = store.findKeyBeforeAndAfter(n);
		else if (store instanceof HistoryKeepingRoutingPointStore)
			b =
				(
					(
						HistoryKeepingRoutingPointStore) store)
							.findKeyBeforeAndAfter(
					n,
					age);
		else
			throw new IllegalArgumentException(
				"Cannot use age='" + age + "' since no history is being kept");

		if (b.includesKeyspaceWrap)
			if (b.nPos == store.length() - 1)
				//TODO: What to do to get rid of this uglyness. Can we just
				// skip it maybe?
				n = n.subtract(Key.KEYSPACE_SIZE);

		// Solve for the time given key and two bounding points.
		return ((b.right.getEstimate() - b.left.getEstimate()) *
			(n.subtract(b.left.getKey())).doubleValue() /
			(b.right.getKey().subtract(b.left.getKey())).doubleValue()) +
			b.left.getEstimate();
	}

	public static KeyspaceEstimatorFactory factory() {
		return factory(DEFAULT_ACCURACY);
	}

	public static KeyspaceEstimatorFactory factory(int accuracy) {
		return new DecayingKeyspaceEstimatorFactory(accuracy);
	}

	public static void main(String[] args) {
		Random r = new Random();
		DecayingKeyspaceEstimator rte = new DecayingKeyspaceEstimator(1024, TIME, "TestEstimator");
		rte.store.print();
		byte[] b = new byte[5];
		r.nextBytes(b);
		Key k = new Key(b);
		rte.report(k, 64);
		rte.store.print();
		/*
		 * for (int i = 0; i < 1000; i++) { rte.report(new Key(r),
		 * r.nextInt(Integer.MAX_VALUE)); } for (int i = 0; i < 10000; i++)
		 */
		/*
		 * DecayingKeyspaceEstimator two = new
		 * DecayingKeyspaceEstimator(rte.serialize());
		 */
		/*
		 * for (;;) { int i = r.nextInt(Integer.MAX_VALUE);
		 * //System.out.println("\nADDED "+i); rte.report(new Key(r), i);
		 * rte.print();
		 */
	}

	/*
	 * @see freenet.node.rt.KeyspaceEstimator#getReport()
	 */
	public KeyspaceEstimator.HTMLReportTool getHTMLReportingTool() {
		return new DecayingKeyspaceHTMLReportTool(this);
	}

	class DecayingKeyspaceHTMLReportTool extends StandardHTMLReportTool {
        DecayingKeyspaceHTMLReportTool(DecayingKeyspaceEstimator estimator) {
            super(estimator);
	}

        protected void dumpHtmlMiddle(java.io.PrintWriter pw) {
	        ((DecayingKeyspaceEstimator)estimator).store.dumpHtml(pw, type);
			}

        protected void dumpLog() {
			((DecayingKeyspaceEstimator)estimator).store.dumpLog();
			}

        protected int columnCount() {
            return 3;
		}
	}

	public double highest() {
		return type.ofRaw(store.highestRaw());
	}

	public double lowest() {
		return type.ofRaw(store.lowestRaw());
	}

	public String lowestString() {
		return type.rawToString(store.lowestRaw());
	}

	public String highestString() {
		return type.rawToString(store.highestRaw());
	}

	public GDSList createGDSL(
		int samples,
		boolean drawHistoryIfPossible,
		Color lineCol) {

		GDSList gdsl = new GDSList();

	    if(logDEBUG)
	        Core.logger.log(this, "createGDSL("+
	                samples+","+drawHistoryIfPossible+","+lineCol+" on "+
	                this, Logger.DEBUG);
		
		int maxAge = 0;
		if (drawHistoryIfPossible
			&& store instanceof HistoryKeepingRoutingPointStore
			&& ((HistoryKeepingRoutingPointStore) store).historySize() > 0) {
			HistoryKeepingRoutingPointStore hstore =
				(HistoryKeepingRoutingPointStore) store;
			maxAge = hstore.historySize();
		}

		for (int i = 0; i <= maxAge; i++) {
			GraphDataSet gds = createGDS(samples, i);
			gdsl.add(gds, lineCol);
			if (i == 0) //First time through the loop
				lineCol = Color.add(lineCol, new Color(50, 50, 50));
			//Make a larger step when we initially go into history
			// values
			else
				lineCol = Color.add(lineCol, new Color(4, 4, 4));
		}
		if(logDEBUG)
		    Core.logger.log(this, "Range now: "+gdsl.lowest+" to "+gdsl.highest,
		            Logger.DEBUG);
		return gdsl;
	}

	public int maxAge() {
		HistoryKeepingRoutingPointStore hstore =
			(HistoryKeepingRoutingPointStore) store;
		int x = hstore.historySize();
		return x;
		}

	public FieldSet toFieldSet() {
		FieldSet fs = new FieldSet();
		fs.put("Implementation", "DecayingKeyspaceEstimator");
		fs.put("Version", "1");
		fs.put("Store", store.toFieldSet());
		return fs;
			}

    /* (non-Javadoc)
     * @see freenet.node.rt.NumericKeyKeyspaceEstimator#recentReports()
     */
    public RecentReports recentReports() {
        return recent;
		}

    double rawToProbability(double x) {
        return x;
    }

    double intToRate(int x, String prefix) {
		double p = ((double) x + 1) / (16 * 1000);
		//1/16th second resolution. Return in bytes per millisecond.
		if (x <= 1)
			log(Logger.MINOR, prefix+": Transfer rate very low!");
		return p;
		}

    double rawToRate(double x) {
        return intToRate((int)x, "rawToRate");
		}

    protected Object getGDSSync() {
        return store;
		}

    public final double guessRaw(Key k, int age) {
        return guess(k.toBigInteger(), age);
	}

    protected double guessRaw(BigInteger b, int age) {
        return guess(b, age);
	}

    public boolean noReports() {
        return this.store.noReports();
    }

    public int countReports() {
        throw new UnsupportedOperationException("no countReports");
    }

    public void getBucketDistribution(BucketDistribution bd) {
        throw new UnsupportedOperationException("getBucketDistribution not implemented by DecayingKeyspaceEstimator");
    }
}
