package freenet.node.rt;

import java.io.Serializable;

import javax.servlet.http.HttpServletResponse;

import freenet.FieldSet;
import freenet.Key;
import freenet.support.DataObject;
import freenet.support.Unit;
import freenet.support.Unit.*;
import freenet.support.graph.Color;
import freenet.support.graph.GDSList;
import freenet.support.graph.GraphDataSet;

/**
 * Approriate units for each type:
 * 
 * TIME: milliseconds
 * 
 * PROBABILITY: none (probability)
 * 
 * TRANSFER_RATE: bytes per millisecond
 */
public interface KeyspaceEstimator extends DataObject, Serializable {

    /** Clone us */
    public Object clone();
    
	// FIXME: Remove guessRaw. It is wrong to demand that the internal raw
	// values be stored as long. The subclass DecayingKeyspaceEstimator doesn't
	// even use long:s for its raw values! It uses int:s.
	public double guessRaw(Key k);
    public static final Unit TRANSFER_RATE = new noConvert(0.0, 10000.0, new milliRatePrint());
    public static final Unit PROBABILITY = new noConvert(0.0, 1.0, new probPrint());
    public static final Unit TIME = new noConvert(0.0, Float.POSITIVE_INFINITY, new milliPrint());

	/** Report an event by its key/value */
	//TODO: Replace all other report...() with: public void report(Key k,
	// double value);

	/** Return the guessed value, converted to the appropriate units. */
	// TODO: Replace all other guess...() with: public double guess(Key k);

	// FIXME: It should not be possible to report a time to a probability
	// estimator! So, remove guess..., etc. as well as report... They are
	// unnecessary and dangerous. Subclasses should keep track of how to
	// convert to raw format.

	/** How many old snapshots do we have? */
	public int maxAge();
	/** Raw guess for the given key, possibly using an old snapshot. */
	public double guessRaw (Key k, int age);
	/** Return the guessed time for the given key, in milliseconds */
	public double guessTime(Key k);
	/** Return the guessed probability for the given key */
	public double guessProbability(Key k);
	/**
	 * Return the guessed transfer rate for the given key. Bytes per
	 * MILLIsecond.
	 */
	public double guessTransferRate(Key k);
	public void reportTime(Key k, long millis);
	public void reportProbability(Key k, double p);
	/** Report transfer rate in bytes per millisecond. */
	public void reportTransferRate(Key k, double rate);
	/**
	 * Returns a tool which can be used for generating HTML reports of this
	 * estimator.
	 */
	public KeyspaceEstimator.HTMLReportTool getHTMLReportingTool();
	public double lowest();
	public double highest();
	/**
	 * An interface which represents a tool that gives the user of the
	 * KeyspaceEstimator some help with rendering the estimator to HTML
	 */
        public GraphDataSet createGDS(int samples, int age);

        public GDSList createGDSL(int samples, boolean drawHistoryIfPossible, Color lineCol);

		public String lowestString();
		public String highestString();

	interface HTMLReportTool {
		/**
		 * Draws the estimation graph or the recent reports crosses or both of
		 * them on the supplied
		 */
		public void drawGraphBMP(
			int width,
			int height,
			boolean dontClipPoints,
			HttpServletResponse resp)
			throws java.io.IOException;
		public void dumpHtml(java.io.PrintWriter pw)
			throws java.io.IOException;
	}
	/**
	 * Pack into FieldSet for transport over FNP or in seednodes.
	 */
	public FieldSet toFieldSet();
	
    /**
     * @return true if we have absolutely no reports from which to make
     * a guess.
     */
    public boolean noReports();
    /**
     * @return The total number of reports on this KeyspaceEstimator.
     */
    public int countReports();
    /**
     * Return some stats on distribution of reports across the keyspace.
     */
    public void getBucketDistribution(BucketDistribution bd);
}
