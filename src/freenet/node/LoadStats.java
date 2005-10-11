/* -*- Mode: java; c-basic-indent: 4; indent-tabs-mode: nil -*- */
package freenet.node;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;

import freenet.Core;
import freenet.diagnostics.Diagnostics;
import freenet.diagnostics.DiagnosticsCategory;
import freenet.fs.dir.FileNumber;
import freenet.support.Checkpointed;
import freenet.support.Comparator;
import freenet.support.DataObject;
import freenet.support.DataObjectStore;
import freenet.support.DataObjectUnloadedException;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.Logger;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.QuickSorter;

/*
 * This code is part of the Java Adaptive Network Client by Ian Clarke. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Helper class to keep track of the data needed to do intra-node load
 * balancing.
 * 
 * @author giannij
 * @author oskar
 */
public class LoadStats implements Checkpointed {

    private final NodeReference myRef;

    private final DataObjectStore table;

    private final DoublyLinkedListImpl lru = new DoublyLinkedListImpl();

    private final int maxTableSize;

    private final Diagnostics diag;

    private final double defaultResetProbability;

    // Accesses to these values must be inside
    // synchronized(LoadStats.this), or in synchronized methods.
    private double globalQueryTraffic;

    private double resetProbability;

    private long lastCheckpoint = 0;

    // Accesses to these must be inside synchronized(timesLock)
    private final Object timesLock = new Object();

    private long[] times;

    private int timesPos;

    private int ratio;

    private final double halfLifeHours;

    private DecayingTimeWeightedAverage queriesPerHour;

    private final boolean logDEBUG;

    private final boolean logMINOR;

    /**
     * Create a new loadstats object.
     * 
     * @param myRef
     *            The NodeReference of this node.
     * @param table
     *            The DataStoreObject providing load entries.
     * @param lsMaxTableSize
     *            The maximum number of peers to use for calculating global
     *            queries/hour.
     * @param lsAcceptRatioSamples
     *            Number of query fates saved for acceptRatio, and number of
     *            queries used for localQueryTraffic.
     * @param lsHalfLifeHours
     *            The half life in hours of the time weighted decaying average
     *            used to calculate queries/hour.
     * @param diag
     *            The node's diagnostics object. A category named "Network
     *            Load" will be added with the following fields: Binomial
     *            localQueryTraffic Continuous globalQueryTrafficMean
     *            Continuous globalQueryTrafficMedian Continuous
     *            globalQueryTrafficDeviation Continuous resetProbability
     *                      Binomial          resetRatio
     * @param parent
     *            The category to make the parent of the new category, or null
     *            to leave it at the top.
     * @param defaultResetProbability
     *            The default value for resetProbability. This value is also
     *            used in calculating resetProbability. If load balancing is
     *            turned off, it is resetProbability.
     */
    public LoadStats(NodeReference myRef, DataObjectStore table, int lsMaxTableSize, int lsAcceptRatioSamples, double lsHalfLifeHours,
            Diagnostics diag, DiagnosticsCategory parent, double defaultResetProbability) throws IOException {
        this.myRef = myRef;
        this.table = table;
        this.maxTableSize = lsMaxTableSize;
        this.halfLifeHours = lsHalfLifeHours;
        this.diag = diag;
        this.defaultResetProbability = defaultResetProbability;

        this.times = new long[lsAcceptRatioSamples];
        this.timesPos = 0;
        this.ratio = lsAcceptRatioSamples; // acceptRatio starts at 1.0.
        this.logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
        this.logMINOR = Core.logger.shouldLog(Logger.MINOR,this);
        {
            // Average queries per hour with a half life as specified.
            // 3600 * 1000 is an hour in milliseconds.
            // Initial value is the last recorded value for this node.
            LoadEntry myLE = getLoadEntry(new FileNumber(myRef.getIdentity().fingerprint()));
            double initialValue = 1.0;
            if (myLE != null) initialValue = myLE.qph;
            this.queriesPerHour = new DecayingTimeWeightedAverage(lsHalfLifeHours, 3600 * 1000, initialValue);
          
            int lesLength = 0;
            for (Enumeration e = table.keys(true); e.hasMoreElements(); e.nextElement()) {
                lesLength++;
            }
            LoadEntry les[] = new LoadEntry[lesLength];
            
            int i = 0;
            for (Enumeration e = table.keys(true); e.hasMoreElements(); i++) {
                les[i] = getLoadEntry((FileNumber) e.nextElement());
            }
            
            // Sort by the when Updated field so oldest will be discarded
            // first.
            QuickSorter.quickSort(new ArraySorter(les, loadEntryWhenUpdatedComparator));
            
            for (i = 0; i < les.length; i++) {
                int j = les.length - 1 - i;
                if (i < maxTableSize) {
                    lru.push(les[j]);
                } else {
                    // maxTableSize is smaller than it was when file was
                    // written.
                    table.remove(les[j].fn);
                }
            }
        }
        
        DiagnosticsCategory traffic = diag.addCategory("Network Load", "Measurements related to the local and global " + "network load.", parent);

        diag.registerBinomial("localQueryTraffic", Diagnostics.MINUTE, "The amount of queries received, and the " + "number that are not rejected.",
                                traffic);
        diag.registerContinuous("globalQueryTrafficMean", Diagnostics.HOUR, "The mean traffic of the known peers, " + "measured regularly.", traffic);
        diag.registerContinuous("globalQueryTrafficMedian", Diagnostics.HOUR, "The median traffic of the known peers, " + "measured regularly.",
                              traffic);
        diag.registerContinuous("globalQueryTrafficDeviation", Diagnostics.HOUR, "The standard deviation in traffic of the "
                + "known peers, measured regularly.", traffic);
        diag.registerContinuous("resetProbability", Diagnostics.HOUR, "The probability of reseting the datasource "
                + "of a reply to point to us if load " + "balancing is used.", traffic);
        diag.registerBinomial("resetRatio", Diagnostics.MINUTE, "The actual ratio of times we actually do "
                + "reset the DataSource to data responses.", traffic);

        checkpoint();
    }

    /**
     * Call to increment the query count. @boolean Whether the query was
     * initially accepted by the node.
     */
    public final void receivedQuery(boolean accepted) {
        long now = System.currentTimeMillis();
        boolean averageIt = false;
        diag.occurrenceBinomial("localQueryTraffic", 1,  accepted ? 1 : 0);

        synchronized(timesLock) {
            if (times[timesPos] >= 0) // note =, since we start with 1
                ratio--;

            times[timesPos] = accepted ? now : -now;
            timesPos = (timesPos + 1) % times.length;
            averageIt = timesPos == 0;

            if (accepted) ratio++;
        }
        if (averageIt) {
            long start;
            int queries = times.length;
            synchronized(timesLock) {
                start = Math.abs(times[timesPos]);
            }
            // submit the value each time timesPos wraps.
            queriesPerHour.average(now, start, queries);
        }
    }

    /**
     * Call to update the table used to estimate global network load.
     */
    public final synchronized void storeTraffic(NodeReference nr, long requestsPerHour) {
        if (nr == null || (requestsPerHour == -1)) {
            // These cases are legal NOPs.
        return; }

        LoadEntry le = new LoadEntry(nr.getIdentity().fingerprint(), requestsPerHour, System.currentTimeMillis());
        LoadEntry oldle = null;

        try {
            oldle = getLoadEntry(le.fn);
        } catch (IOException e) {
            // already logged
        }
        
        if (oldle != null) {
            lru.remove(oldle);
        }
        
        table.set(le.fn, le);
        lru.push(le);
        
        // Don't let the table grow without bound.
        while (lru.size() > maxTableSize) {
            LoadEntry last = (LoadEntry) lru.shift();
            table.remove(last.fn);
        }
    }
    
    private class QueryTrafficInfo {

        final int queries;

        final double seconds;

        final double hours;

        final double instantaneousRate;

        final double acceptRatio;

        final double localQueryTraffic;

        final LoadEntry[] les;

        final int tooOld;

        final int tooSmall;

        final int notTooBig;

        final double globalQueryTrafficMean;

        final double globalQueryTrafficMedian;

        final double globalQueryTrafficDeviation;

        final double relProb;

        final double resetProbability;

        QueryTrafficInfo() {
            long start;
            long now = System.currentTimeMillis();
            int x;
            int y = times.length;
            int queries = times.length;
            synchronized(timesLock) {
                start = Math.abs(times[timesPos]);
                x = ratio;
                if (start == 0) {
                    start = Math.abs(times[0]);
                    queries = timesPos;
                    int missing = times.length - timesPos;
                    x -= missing;
                    y -= missing;
                }
            }
            this.queries = queries;
            this.seconds = (now - start) / 1000.0;
            this.hours = this.seconds / 3600.0;
            this.instantaneousRate = this.queries / this.hours;
            this.acceptRatio = (y == 0 ? 1.0 : (double)x / y);
            this.localQueryTraffic = Math.max(1.0, queriesPerHour.average(now, start, this.queries));
            // localQueryTraffic will be saved in the checkpoint file.
            storeTraffic(myRef, (long)(this.localQueryTraffic + 0.5));

            synchronized(LoadStats.this) {
                this.les = new LoadEntry[lru.size()];
                int i = 0;
                for (Enumeration e = lru.elements(); e.hasMoreElements();i++) {
                    les[i] = (LoadEntry) e.nextElement();
                }
            }

            // Sort the data by age.
            QuickSorter.quickSort(new ArraySorter(les, loadEntryWhenUpdatedComparator));

            // drop the oldest 5% of the data.
            int size = les.length;
            this.tooOld = size / 20;
            size -= tooOld;

            // Sort the not too old data by the queries per hour value.
            QuickSorter.quickSort(new ArraySorter(les, tooOld, size));

            // Ignore the outliers: When the table is less than half full,
            // ignore the smallest and largest 10% of the entries.  When
            // the table is more than half full, ignore the maxTableSize /
            // 20 smallest and largest values.  When the table is exactly
            // half full, size / 10 == maxTableSize / 20.

            int ignore = Math.min(maxTableSize / 20, size / 10);
            size -= 2*ignore;
            this.tooSmall = tooOld + ignore;
            this.notTooBig = tooSmall + size;

            double total = 0.0;
            double totalSquared = 0.0;
            double n = size;
            for (int i = tooSmall ; i < notTooBig; i++) {
                total += les[i].qph;
                totalSquared += les[i].qph * les[i].qph;
            }

            double mean = n == 0.0 ? 0.0 : total / n;
            long median = (n == 0) ? 0 : (size % 2 == 1 ? les[size / 2 + tooSmall].qph
                    : ((les[size / 2 - 1 + tooSmall].qph + les[size / 2 + tooSmall].qph) / 2));
            // estimate standard deviation of population from sample of size n.
            double deviation = n <= 1.0 ? 0.0 : // bias corrected
                Math.sqrt(totalSquared / (n - 1.0) - (total * total) / (n * (n - 1.0)));
        
            this.relProb = Math.min(1.0, defaultResetProbability * (mean / localQueryTraffic));
        
            // Probability of resetting the DataSource
        
            // resetProbability = acceptRatio ^ 5 * relProb,
            // with some bounds to avoid degeneration:
            // resetProbability is at least 2%, no more than 50%.

            // Currently, default defaultResetProbability is 0.05.

            // When local is over 2.5 times global, prob is always 2%.

            // When local is 1 times global, acceptRatio must
            // be over 0.80 for prob to exceed 2%, and it only
            // rises to 5% (i.e. defaultResetProbability) at 1.0

            // When local is 1/10 of global, prob starts rising
            // around 0.5 acceptance and reaches 50% at 1.0.

            // resetProbability as function of local/global and acceptRatio,
            // assuming defaultResetProbability = 0.05.
            //
            //  local    accepted Query Ratio
            // ------  0.0 0.2 0.4 0.6   0.8   1.0
            // global
            //  0.1    2%  2%  2%  3.9% 16.4% 50.0%
            //  0.3    2%  2%  2%  2%    5.5% 16.7%
            //  0.5    2%  2%  2%  2%    3.3% 10.0%
            //  0.7    2%  2%  2%  2%    2.3%  7.1%
            //  0.9    2%  2%  2%  2%    2%    5.6%
            //  1.1    2%  2%  2%  2%    2%    4.5%
            //  1.3    2%  2%  2%  2%    2%    3.8%
            //  1.5    2%  2%  2%  2%    2%    3.3%
            //  1.7    2%  2%  2%  2%    2%    2.9%
            //  1.9    2%  2%  2%  2%    2%    2.6%
            //  2.1    2%  2%  2%  2%    2%    2.4%
            //  2.3    2%  2%  2%  2%    2%    2.2%
            //  2.5    2%  2%  2%  2%    2%    2%
        
            double prob = Math.pow(acceptRatio, 5) * relProb;
            if (prob > 0.5) prob = 0.5;
            if (prob < 0.02) prob = 0.02;
            this.globalQueryTrafficMean = mean;
            this.globalQueryTrafficMedian = median;
            this.globalQueryTrafficDeviation = deviation;
            this.resetProbability = prob;
        
            diag.occurrenceContinuous("globalQueryTrafficMean", mean);
            diag.occurrenceContinuous("globalQueryTrafficMedian", median);
            diag.occurrenceContinuous("globalQueryTrafficDeviation", deviation);
            diag.occurrenceContinuous("resetProbability", prob);
        
            synchronized (LoadStats.this) {
                LoadStats.this.globalQueryTraffic = mean;
                LoadStats.this.resetProbability = prob;
            }
        }
    }

    /**
     * @return The number of outbound requests per hour made from this node,
     *         but not less than 1.0.
     */
    public final double localQueryTraffic() {
        long start;
        long now = System.currentTimeMillis();
        int queries = times.length;
        synchronized(timesLock) {
            start = Math.abs(times[timesPos]);
            if (start == 0) {
                start = Math.abs(times[0]);
                queries = timesPos;
            }
        }
        return Math.max(1.0, queriesPerHour.average(now, start, queries));
    }
    
    /**
     * Class to maintain a decaying time weighted average of events per unit
     * time. If the value was a at time t = t0, and subsequently values of b
     * are submitted, then the value will decay to (a+b)/2 at time t = t0 +
     * halfLife. Since exp(a+b) = exp(a)exp(b), it follows that this is true
     * regardless of whether a single call is made at time t0 + halfLife, or if
     * any number of calls are made during the interval, provided that the
     * events per unit time provided was always b.
     *
     *  newAvg = (1/2)^(dt/hl) * oldAvg + (1 - (1/2)^(dt/hl)) * data
     *
     * For dt = 0.000 hl, newAvg == 1.000 oldAvg + 0.000 data For dt = 0.415
     * hl, newAvg == 0.750 oldAvg + 0.250 data For dt = 0.500 hl, newAvg ==
     * 0.707 oldAvg + 0.293 data For dt = 1.000 hl, newAvg == 0.500 oldAvg +
     * 0.500 data For dt = 2.000 hl, newAvg == 0.250 oldAvg + 0.750 data For dt =
     * 10.00 hl, newAvg == 0.001 oldAvg + 0.999 data For dt = 11.00 hl, newAvg ==
     * 0.000 oldAvg + 1.000 data
     *
     *  @author ejhuff
     */
 
    // To eliminate clutter, suppose halfLife is 1.0
    // The result of submitting data after time p:
    //       -p         oldAvg
    // (1 - 2  ) data + ------
    //                     p
    //                    2
    // The result of submitting data after time p and then
    // submitting the same data again after time q:

    //                        -p         oldAvg
    //                  (1 - 2  ) data + ------
    //                                      p
    //       -q                            2
    // (1 - 2  ) data + -----------------------
    //                             q
    //                            2
    // But the above is equal to the result of submitting
    // the data once, after time p + q:

    //       -p - q          -p - q
    // (1 - 2      ) data + 2       oldAvg
    //
    // The result of submitting data after time p and then
    // submitting the same data again after time q and again
    // after time r:
    //                                         -p         oldAvg
    //                                   (1 - 2  ) data + ------
    //                                                       p
    //                        -q                            2
    //                  (1 - 2  ) data + -----------------------
    //                                              q
    //       -r                                    2
    // (1 - 2  ) data + ----------------------------------------
    //                                      r
    //                                     2
    // Again, this is the same as submitting data once after
    // time p + q + r:
    //       -p - q - r          -p - q - r
    // (1 - 2          ) data + 2           oldAvg
    //
    // Hence, except for round-off errors, it doesn't matter how often
    // you submit the same data.  
    // If the data fluctuates randomly about a mean, submitting the
    // random values frequently is about the same as submitting their
    // mean once (example not shown).
    private class DecayingTimeWeightedAverage {

        private final double msPerUnitTime;

        private final double coef; // -log(2) / (half life in milliseconds)

        private double decayingAverage = 0.0;

        private long then;

        /**
         *  Create a new DecayingTimeWeightedAverage object.
         * 
         * @param halfLife
         *            halfLife as a fraction of a time unit.
         * @param msPerUnitTime
         *            The number of milliseconds in a time unit.
         * @param initialValue
         *            Initial value of the average at current time.
         */

        DecayingTimeWeightedAverage(double halfLife, long msPerUnitTime, double initialValue) {
            this.msPerUnitTime = msPerUnitTime; // milliseconds.
            this.coef = Math.log(2.0) / (-halfLife * msPerUnitTime);
            this.decayingAverage = initialValue;
            this.then = System.currentTimeMillis();
            // System.err.println("halfLife = " + halfLife);
            // System.err.println("msPerUnitTime = " + msPerUnitTime);
            // System.err.println("coef = " + this.coef);
        }
        
        /**
         * Calculate the decaying time weighted average given the present time,
         * the time when the last interval started, and the number of events
         * which occurred during that interval. Any subsequent calls that
         * specify current time earlier than this call will be ignored.
         * 
         * @param now
         *            A recent value of System.currentTimeMillis()
         * @param start
         *            Time of start of interval, millis.
         * @param events
         *            Number of events which occurred during the interval.
         * @return Average events / unit time, decaying with the half-life
         *         specifed to the constructor.
         */

        synchronized double average(long now, long start, int events) {

            //      millisecond  event
            //      ----------- --------
            //       unitTime   interval        event
            //     ----------------------- ==> --------
            //           millisecond           unitTime
            //           -----------
            //            interval
            
            double dt = (now - start); // dt in ms / interval
            double eventPerUnitTime = (msPerUnitTime * events) / dt;

            // System.err.println("now = " + now + " then = " + then);
            // System.err.println("dt = " + dt + " eventPerUnitTime = " +
            // eventPerUnitTime);

            if (dt <= 0.0) {
                // System clock ran backwards.  Ignore the data.
                eventPerUnitTime = decayingAverage;
            }
            // Calculate a time weighted decaying average
            // with half-life of halfLife milliseconds. 
            // damping 0 would mean it decays instantly.
            // 0 < damping < 0.5 if dt > halfLife.
            // damping == 0.5 if dt == halfLife.
            // 0.5 < damping < 1 if dt < halfLife.
            // damping 1 means it never decays.
            if (now > then) {
                // This is a different time interval than above.
                // It is milliseconds since last calculation,
                // not milliseconds in the interval during which
                // <code> events </code> events occurred.
                dt = (now - then); // dt > 0, coef < 0
                double damping = Math.exp(coef * dt); // 0 < damping < 1
                // System.err.println("dt = " + dt + " damping = " + damping);
                // System.err.println("damping * decayingAverage = " + damping
                // * decayingAverage);
                // System.err.println("(1.0 - damping) * eventPerUnitTime = " +
                // (1.0 - damping) * eventPerUnitTime);

                decayingAverage = damping * decayingAverage + (1.0 - damping) * eventPerUnitTime;
                // System.err.println("decayingAverage = " + decayingAverage);
            }
            then = now; // Always reset then.  If system clock stepped
            // backwards, this assumes that the previous
            // average is the best guess.
            return decayingAverage;
        }
    }

    /**
     * @return An estimate of the global per node network load in requests per
     *         hour.
     */
    public final synchronized double globalQueryTraffic() {
        return globalQueryTraffic;
    }

    /**
     * Rolls to see if the DataSource of a message should be reset. This
     * respects the setting of Node.doLoadBalance.
     */
    public final boolean shouldReset() {
        double p = Node.getRandSource().nextFloat();
        double resetProbability = Node.doLoadBalance ? resetProbability() : defaultResetProbability;
        boolean b = p < resetProbability;
        if (b && logMINOR)
                Core.logger.log(this, "Telling a response to reset DataSource. " + "Current probability " + resetProbability, Logger.MINOR);

        diag.occurrenceBinomial("resetRatio", 1, b ? 1 : 0);
        return b;
    }

    /**
     * Calculates the probability of resetting the DataSource that should be
     * used when sending StoreData messages.
     * <p>
     * This is how the node does intra-node load balancing.
     * <p>
     * The current formula is as follows: Take
     * <p>
     * p_1 = min(r * M / m, 1)
     * <p>
     * where M is estimate of the global traffic, m is the local traffic, and r
     * is the mean reset ratio on the network. "r" is currently set .05 since
     * that is old unbalanced probability used by everyone, it should be more
     * or less self fullfilling if everybody uses the same formula (*). We then
     * take:
     * <p>
     * p_2 = a^5 * p_1
     * <p>
     * where a is the ratio of requests currently being accepted rather then
     * rejected because of load issues. This is so overloaded nodes will
     * advertise less.
     * <p>
     * Finally, we cut off the value from above and below to keep the network
     * from degenerating in the worst case scenarios:
     * <p>
     * resetProbability = min(0.5, max(0.02, p_2));
     * <p>
     * (*) The justification for this formula (which isn't great) is that if we
     * imagine the state of the network taking discreet steps (with all
     * references replaced at every step), then the amount of traffic we get
     * after one step should be the current traffic, times the probability of
     * reseting, times one over the chance of other nodes reseting (which gives
     * the expected number of nodes that our reset will reach). So if we want
     * the traffic we get in one step to be the global mean, then that gives
     * the formula for p_1 above.
     */
    public final synchronized double resetProbability() {
        return resetProbability;
    }

    /**
     * @return  "Calculate and report global load averages"
     */
    public String getCheckpointName() {
        return "Calculate and report global load averages";
    }

    /**
     * @return  10 min
     */
    public synchronized long nextCheckpoint() {
        return lastCheckpoint + (10 * 60 * 1000);
    }

    /**
     * Execute a maintenance checkpoint.
     */
    public void checkpoint() {
        if (logDEBUG) Core.logger.log(this, "Executing checkpoint in LoadStats", Logger.DEBUG);

        synchronized (this) {
            this.lastCheckpoint = System.currentTimeMillis();
        }
        new QueryTrafficInfo();
        try {
            table.flush();
        } catch (IOException e) {
            Core.logger.log(this, "Error flushing load stats!", e, Logger.ERROR);
        }
        if (logDEBUG) Core.logger.log(this, "Finished executing checkpoint on loadStats", Logger.DEBUG);
    }

    public final void dump(PrintWriter pw) {
        QueryTrafficInfo info = new QueryTrafficInfo();
        pw.println("# " + new Date().toString());
        pw.println("# entries: " + info.les.length);
        pw.println("# mean globalRequestsPerHourPerNode: " + info.globalQueryTrafficMean);
        pw.println("# median globalRequestsPerHourPerNode: " + info.globalQueryTrafficMedian);
        pw.println("# standard deviation globalRequestsPerHourPerNode: " + info.globalQueryTrafficDeviation);
        pw.println("# smoothed localRequestsPerHour: " + info.localQueryTraffic);
        pw.println("# smoothing half life (hours): " + halfLifeHours);
        pw.println("# instantaneous localRequestsPerHour: " + info.instantaneousRate);
        pw.println("# The last " + info.queries + " queries arrived in " + info.seconds + " seconds.");
        pw.println("# Current proportion of requests being accepted: " + info.acceptRatio);
        pw.println("# Current advertise probability: " + info.resetProbability);
        pw.println("# Traffic data from " + info.les.length + " nodes (max allowed " + maxTableSize + "),");
        pw.println("# of which " + (info.notTooBig - info.tooSmall) + " were used.");
        // FIXME print same data as dumpHTML.
        pw.println("# format: <class> <requests per hour> <as of> <node fingerprint>");                   
        for (int i = 0; i < info.les.length; i++) {
            // FIXME use the method that writes DSA(xxxx xxxx ...)
            String flag;
            if (i < info.tooOld)
                flag = "Old\t";
            else if (i < info.tooSmall)
                flag = "Small\t";
            else if (i < info.notTooBig)
                flag = "Used\t";
            else
                flag = "Big\t";
            pw.println(flag + info.les[i].qph + "\t" + info.les[i].whenUpdated + "\t" + "\"" + info.les[i].fn.toString() + "\"");
        }
    }

    public final void dumpHtml(PrintWriter pw) {
        QueryTrafficInfo info = new QueryTrafficInfo();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z");
        NumberFormat nf = NumberFormat.getInstance();
        nf.setGroupingUsed(false);
        NumberFormat nf3 = NumberFormat.getInstance();
        nf3.setMaximumFractionDigits(3);
        nf3.setMinimumFractionDigits(3);
        nf3.setGroupingUsed(false);
        pw.println("<b>" + sdf.format(new Date()) + "</b><br />");
        pw.println("<ul>");
        pw.println("<li> Smoothed local mean traffic (queries per hour): " + nf3.format(info.localQueryTraffic) + "</li>");
        pw.println("<li> Smoothing half life (lsHalfLifeHours): " + nf3.format(halfLifeHours) + " hour.");
        pw.println("That is, the rate decays completely to a new value after " + nf3.format(halfLifeHours * 10.0) + " hour.</li>");
        pw.println("<li> Instantaneous local traffic:  " + nf3.format(info.instantaneousRate) + " queries per hour.");
        pw.println("That is, the last " + info.queries + " queries arrived in " + nf3.format(info.seconds) + " second or " + nf3.format(info.hours)
                + " hour.</li>");
        pw.println("<li> Current proportion of requests being accepted: " + nf3.format(info.acceptRatio) + "</li>");
        pw.println("<li> Current advertise probability: " + nf3.format(info.resetProbability) + "</li>");
        pw.println("<li> Traffic data from " + info.les.length + " nodes (max allowed " + maxTableSize + "), ");
        pw.println("of which " + (info.notTooBig - info.tooSmall) + " were used.");
        pw.println("The oldest 5% and the smallest and largest 5% (up to 10% if");
        pw.println("the table is not full) are not used in computing statistics.</li>");
        pw.println("<li> Global mean traffic per node (queries per hour): " + nf3.format(info.globalQueryTrafficMean) + "</li>");
        pw.println("<li> Global median traffic per node (queries per hour): " + nf3.format(info.globalQueryTrafficMedian) + "</li>");
        pw.println("<li> Standard Deviation of global traffic per node: " + nf3.format(info.globalQueryTrafficDeviation) + "</li>");
        pw.println("</ul>");
        pw.println("<table border=\"1\"><tr>" + "<th>Class</th>" + "<th>Queries per hour</th>" + "<th>as of</th>" + "<th>Node fingerprint</th></tr>");

        for (int i = 0; i < info.les.length; i++) {
            String rate = nf.format(info.les[i].qph);
            String asof = sdf.format(new Date(info.les[i].whenUpdated));
            String key = info.les[i].fn.toString(); // FIXME: use the right
                                                    // string.
            String flag;
            if (i < info.tooOld)
                flag = "Old";
            else if (i < info.tooSmall)
                flag = "Small";
            else if (i < info.notTooBig)
                flag = "Used";
            else
                flag = "Big";
            pw.println("<tr>" + "<td align=right>" + flag + "</td>" + "<td align=right>" + rate + "</td>" + "<td>" + asof + "</td>" + "<td>" + key
                    + "</td>" + "</tr>");
        }
        pw.println("</table>");
    }

    private LoadEntry getLoadEntry(FileNumber key) throws IOException {
        try {
            return (LoadEntry) table.get(key);
        } catch (DataObjectUnloadedException dp) {
            try {
                return new LoadEntry(dp.getDataInputStream());
            } catch (IOException e) {
                Core.logger.log(this, "Error restoring load stats", e, Logger.ERROR);
                throw e;
            }
        }
    }

    private class LoadEntry extends DoublyLinkedListImpl.Item implements Comparable, DataObject {

        private final FileNumber fn;

        private final long qph;

        private final long whenUpdated;

        private LoadEntry(byte[] b, long qph, long whenUpdated) {
            this.fn = new FileNumber(b);
            this.qph = qph;
            this.whenUpdated = whenUpdated;
        }

        private LoadEntry(DataInputStream in) throws IOException {
            byte[] bs = new byte[in.readInt()];
            long whenUpdated;
            in.readFully(bs);
            fn = new FileNumber(bs);
            qph = in.readLong();
            try {
                whenUpdated = in.readLong();
            } catch (IOException e) {
                whenUpdated = 0; // reading an old backing store.
            }
            this.whenUpdated = whenUpdated;
        }

        public void writeDataTo(DataOutputStream out) throws IOException {
            byte[] bs = fn.getByteArray();
            out.writeInt(bs.length);
            out.write(bs);
            out.writeLong(qph);
            out.writeLong(whenUpdated);
        }

        public int getDataLength() {
        	return INT_SIZE + fn.getByteArray().length + LONG_SIZE + LONG_SIZE;
        }

        public int compareTo(Object o) {
            long qph2 = ((LoadEntry) o).qph;
            return qph < qph2 ? -1 : qph == qph2 ? 0 : 1;
        }
    }

    private class LoadEntryWhenUpdatedComparator implements Comparator {

        public int compare(Object a, Object b) {
            long aWhenUpdated = ((LoadEntry) a).whenUpdated;
            long bWhenUpdated = ((LoadEntry) b).whenUpdated;
            return aWhenUpdated < bWhenUpdated ? -1 : aWhenUpdated == bWhenUpdated ? 0 : 1;
        }
    }

    private LoadEntryWhenUpdatedComparator loadEntryWhenUpdatedComparator = new LoadEntryWhenUpdatedComparator();

}
