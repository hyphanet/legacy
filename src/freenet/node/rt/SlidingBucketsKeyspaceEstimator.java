package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.BigInteger;

import freenet.Core;
import freenet.FieldSet;
import freenet.Key;
import freenet.support.Unit;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.graph.Color;
import freenet.support.graph.GDSList;
import freenet.support.graph.GraphDataSet;


/**
 * A KeyspaceEstimator based on the following algorithm:
 * We have N RunningAverages, r1...rN
 * 
 * We have N dividing keys, k1...kN (with wrap-around: k(N+1) == k(1)).
 * If we get a report between kX and k(X+1), we direct that report to
 * R(X). We also move kX and k(X+1) towards the reported key by a
 * fixed amount.
 * 
 * Should have pluggable interpolation, based initially on assuming that the
 * middle of the sector is a point with the value of the RunningAverage.
 * @author amphibian
 * Concept from Ian.
 * @see FastSlidingBucketsKeyspaceEstimator - whenever this file is changed,
 * there should be corresponding changes in that file; please do a diff if you
 * change this or that file, and ensure that the only differences are small code
 * changes because of this class using BigInteger, and that using double for keys.
 */
public class SlidingBucketsKeyspaceEstimator extends NumericKeyKeyspaceEstimator {

    private static final int SERIAL_MAGIC = 0x2534;

    public class SlidingBucketsHTMLReportingTool extends StandardHTMLReportTool {

        /**
         * @param estimator
         */
        public SlidingBucketsHTMLReportingTool(
                NumericKeyKeyspaceEstimator estimator) {
            super(estimator);
        }

        protected void dumpHtmlMiddle(PrintWriter pw) {
            if(logDEBUG)
                dump("dumpHtmlMiddle", Logger.DEBUG, false);
            pw.println("<tr><th>Key</th><th>Raw value</th><th>Smoothed value</th><th>Reports</th><th>Detail</th>");
            synchronized(this) { // REDFLAG: we ARE writing into a stringwriter, right?
                for(int i=0;i<accuracy;i++) {
                    pw.println("<tr><td>");
                    pw.println(HexUtil.biToHex(leftEdge[i]));
                    pw.println("</td><td>");
                    pw.println(type.rawToString(ra[i].currentValue()));
                    pw.println("</td><td>");
                    pw.println(type.rawToString(getBucketValue(i)));
                    pw.println("</td><td>");
                    pw.println(ra[i].countReports());
                    pw.println("</td><td>");
                    pw.println(ra[i].toString());
                    pw.println("</td></tr>\n");
                }
            }
        }

        protected void dumpLog() {
        }

        protected int columnCount() {
            return 5;
        }

    }
    /*
     * leftEdge are the boundaries.
     * centers are the centers.
     * So ra[i] serves the sector formed by boundaries 
     * leftEdge[i] and leftEdge[i+1] with center
     * centers[i].
     * KEPT IN STRICTLY INCREASING ORDER.
     */
    
    final RunningAverage[] ra;
    final BigInteger[] leftEdge;
    final BigInteger[] centers;
    final RecentReports recent;
    final BigDecimal movementFactor;
    final int accuracy;
    final int TARGET_REPORTS = 10;
    final boolean doSmoothing;

    /** Construct blank, with equally spaced dividing keys, and initial value of 0
     * for RunningAverage's.
     * @param factory RunningAverageFactory to use for the RunningAverage's.
     * @param accuracy the number of sectors. Each is bounded by two keys, and
     * has a value represented by a RunningAverage. 
     */
    public SlidingBucketsKeyspaceEstimator(RunningAverageFactory factory, int accuracy,
            BigDecimal movementFactor, double initValue, Unit type, String name, boolean doSmoothing) {
        super(type, name);
        this.movementFactor = movementFactor;
        this.accuracy = accuracy;
        this.doSmoothing = doSmoothing;
        ra = new RunningAverage[accuracy];
        leftEdge = new BigInteger[accuracy];
        centers = new BigInteger[accuracy];
        // Initial dividing keys: gap should be keyspace / accuracy
		BigInteger a = Key.KEYSPACE_SIZE.subtract(BigInteger.ONE);
		BigInteger b = a.divide(BigInteger.valueOf(accuracy));
		BigInteger c = b.shiftRight(1);
        for(int i=(accuracy-1);i>=0;i--) {
            a = a.subtract(b);
            ra[i] = factory.create(initValue);
            leftEdge[i] = a;
            centers[i] = a.add(c);
        }
        recent = new RecentReports();
        dump("Initialized flat", Logger.MINOR, false);
    }

    // Serialization to disk and to FieldSet: version 2.
    // Version 1 had lots of bugs!
    
    /**
     * Create a SlidingBucketsKeyspaceEstimator from one that has been
     * serialized to disk.
     * @param raf RunningAverageFactory to use for the RunningAverage's.
     * @param accuracy the number of sectors. Each is bounded by two keys, and
     * has a value represented by a RunningAverage.
     * @param mf
     * @param dis the stream to read from
     * @param maxValue the maximum permitted value 
     * @param minValue the minimum permitted value
     */
    public SlidingBucketsKeyspaceEstimator(RunningAverageFactory raf, int accuracy,
            BigDecimal mf, DataInputStream dis, Unit type, String name, boolean doSmoothing) 
            throws IOException {
        super(type, name);
        this.movementFactor = mf;
        int magic = dis.readInt();
        this.doSmoothing = doSmoothing;
        if(magic != SERIAL_MAGIC)
            throw new IOException("Invalid magic");
        int ver = dis.readInt();
        if(ver != 2)
            throw new IOException("Unrecognized version: "+ver+" - possible format change");
        int acc = dis.readInt();
        if(acc != accuracy)
            throw new IOException("Accuracy changed");
        this.accuracy = accuracy;
        centers = new BigInteger[accuracy];
        leftEdge = new BigInteger[accuracy];
        ra = new RunningAverage[accuracy];
        for(int i=0;i<accuracy;i++) {
            centers[i] = readBigInteger(dis);
            leftEdge[i] = readBigInteger(dis);
            if(i != 0 && leftEdge[i].compareTo(centers[i]) == 1)
                throw new IOException("Border greater than center on "+i);
            ra[i] = raf.create(dis);
            if(!type.withinRange(ra[i].currentValue()))
                throw new IOException("Value out of range: "+ra[i].currentValue()+" from "+ra[i]+" should be "+type.getMin()+"< x <"+type.getMax());
        }
        recent = new RecentReports();
        correctSlide();
        String s = checkConsistency();
        if(s != null)
            throw new IOException("Serialized inconsistent (and uncorrectable) estimator: "+s);
    }

    /**
     * @param rafSmooth
     * @param set
     * @param minAllowedTime
     * @param maxAllowedTime
     */
    public SlidingBucketsKeyspaceEstimator(RunningAverageFactory raf, BigDecimal mf, int accuracy,
            FieldSet set, Unit type, String name, boolean doSmoothing) 
    	throws EstimatorFormatException {
        super(type, name);
        if(logDEBUG)
            Core.logger.log(this, "Serializing from: "+set, Logger.DEBUG);
        this.doSmoothing = doSmoothing;
        if(set == null) throw new EstimatorFormatException("null FieldSet");
		String impl = set.getString("Implementation");
		if (impl == null)
			throw new EstimatorFormatException("no Implementation in RunningAverage");
		if (!impl.equals("SlidingBucketsKeyspaceEstimator"))
			throw new EstimatorFormatException(
				"unknown implementation " + impl);
		String v = set.getString("Version");
		if (v == null || !v.equals("2"))
			throw new EstimatorFormatException("Invalid version " + v);
		// Now read it in
		String l = set.getString("Accuracy");
		int acc;
		try {
		    acc = Integer.parseInt(l,16);
		} catch (NumberFormatException e) {
		    throw new EstimatorFormatException("Invalid Accuracy: "+l);
		}
		if(acc != accuracy)
		    throw new EstimatorFormatException("Different accuracy: "+acc+" should be "+accuracy);
		this.accuracy = accuracy;
		ra = new RunningAverage[accuracy];
		leftEdge = new BigInteger[accuracy];
		centers = new BigInteger[accuracy];
		recent = new RecentReports();
		movementFactor = mf;
		FieldSet points = set.getSet("Points");
		if(points == null)
		    throw new EstimatorFormatException("No points!");
		if(logDEBUG)
		    Core.logger.log(this, "Points: "+points, Logger.DEBUG);
		for(int i=0;i<accuracy;i++) {
		    FieldSet point = points.getSet(Integer.toHexString(i));
		    if(point == null) throw new EstimatorFormatException("No point "+Integer.toHexString(i)+", full fieldset="+set);
		    ra[i] = raf.create(point.getSet("Value"));
            if(!type.withinRange(ra[i].currentValue()))
                throw new EstimatorFormatException("Value out of range: "+ra[i].currentValue()+" from "+ra[i]+" should be "+type.getMin()+"< x <"+type.getMax());

		    String key = point.getString("DividingKey");
		    if(key == null) throw new EstimatorFormatException("No key "+i);
		    try {
		        leftEdge[i] = new BigInteger(key, 16);
		    } catch (NumberFormatException e) {
		        throw new EstimatorFormatException("Invalid key: "+key);
		    }
		    if(leftEdge[i].signum() == -1)
		        throw new EstimatorFormatException("Negative key: "+leftEdge[i]+" for "+i);
		    if(leftEdge[i].compareTo(Key.KEYSPACE_SIZE) == 1)
		        throw new EstimatorFormatException("Too big key: "+leftEdge[i]+" for "+i);
		}
		/** 
		 * Minimum sector size: 
		 * 1/10th of the size of one sector if all are equal size.
		 * This means about 40 hits in that sector at 5% movementFactor.
		 * The point here is to prevent fake passed in estimators from taking an
		 * excessively long time to correct.
		 */
		BigInteger minStep = Key.KEYSPACE_SIZE.divide(BigInteger.valueOf(accuracy * 10));
		// Run loop twice because center[] all null until updateCenter called.
		for(int i=0;i<accuracy;i++) {
		    updateCenter(i);
		}
		for(int i=0;i<accuracy;i++) {
		    BigInteger thisCenter = centers[i];
		    int prev = (i + accuracy - 1) % accuracy;
		    BigInteger prevCenter = centers[prev];
		    if(thisCenter.compareTo(prevCenter) == -1) {
		        // thisCenter > prevCenter
		        thisCenter = thisCenter.add(Key.KEYSPACE_SIZE);
		    }
		    BigInteger diff = thisCenter.subtract(prevCenter);
		    if(diff.compareTo(minStep) == -1)
		        throw new EstimatorFormatException("Overspecialized probably fake estimator: refusing to accept FieldSet: diff "+i+"="+diff);
		}
		correctSlide();
        String s = checkConsistency();
        if(s != null)
            throw new EstimatorFormatException("INCONSISTENT: "+s);
    }

    public SlidingBucketsKeyspaceEstimator(SlidingBucketsKeyspaceEstimator e) {
        super(e);
        this.accuracy = e.accuracy;
        this.centers = (BigInteger[]) e.centers.clone();
        this.doSmoothing = e.doSmoothing;
        this.leftEdge = (BigInteger[]) e.leftEdge.clone();
        this.movementFactor = e.movementFactor;
        this.ra = new RunningAverage[e.ra.length];
        for(int i=0;i<ra.length;i++) {
            ra[i] = (RunningAverage) e.ra[i].clone();
        }
        this.recent = (RecentReports) e.recent.clone();
    }

    /**
     * Read a (reasonably short) BigInteger from a DataInputStream
     * @param dis the stream to read from
     * @return a BigInteger
     */
    private BigInteger readBigInteger(DataInputStream dis) throws IOException {
        short i = dis.readShort();
        if(i < 0) throw new IOException("Invalid BigInteger length: "+i);
        byte[] buf = new byte[i];
        dis.readFully(buf);
        return new BigInteger(1,buf);
    }

    public HTMLReportTool getHTMLReportingTool() {
        return new SlidingBucketsHTMLReportingTool(this);
    }

    public synchronized double lowestRaw() {
        double lowest = type.getMax();
        for(int i=0;i<accuracy;i++) {
            double d = getBucketValue(i);
            if(d < lowest) lowest = d;
        }
        return lowest;
    }

    public double lowest() {
        return type.ofRaw(lowestRaw());
    }
    
    public synchronized double highestRaw() {
        double highest = 0;
        for(int i=0;i<accuracy;i++) {
            double d = getBucketValue(i);
            if(d > highest) highest = d;
        }
        return highest;
    }

    public double highest() {
        return type.ofRaw(highestRaw());
    }
    
	public String lowestString() {
		return type.rawToString(lowestRaw());
	}

	public String highestString() {
		return type.rawToString(highestRaw());
	}
	
    public GDSList createGDSL(int samples,
            boolean drawHistoryIfPossible, Color lineCol) {
        GDSList gdsl = new GDSList();
        if(logDEBUG)
            dump("Creating GDSL", Logger.DEBUG, false);
        GraphDataSet gds = this.createGDS(samples, 0);
        gdsl.add(gds, lineCol);
        return gdsl;
    }

    public FieldSet toFieldSet() {
        FieldSet fs = new FieldSet();
        fs.put("Implementation", "SlidingBucketsKeyspaceEstimator");
        fs.put("Version", "2");
        fs.put("Accuracy", Integer.toHexString(accuracy));
        FieldSet points = new FieldSet();
        fs.put("Points", points);
        synchronized(this) {
            for(int i=0;i<accuracy;i++) {
                FieldSet pt = new FieldSet();
                points.put(Integer.toHexString(i), pt);
                pt.put("Value", ra[i].toFieldSet());
                pt.put("DividingKey", HexUtil.biToHex(leftEdge[i]));
            }
        }
        return fs;
    }

    public void writeDataTo(DataOutputStream out) throws IOException {
        out.writeInt(SERIAL_MAGIC);
        out.writeInt(2);
        out.writeInt(accuracy);
        synchronized(this) {
            for(int i=0;i<accuracy;i++) {
                writeBigInteger(centers[i], out);
                writeBigInteger(leftEdge[i], out);
                ra[i].writeDataTo(out);
            }
        }
    }

    /**
     * Write a (reasonably short) BigInteger to a stream.
     * @param integer the BigInteger to write
     * @param out the stream to write it to
     */
    private void writeBigInteger(BigInteger integer, DataOutputStream out) throws IOException {
        if(integer.signum() == -1) {
            dump("Negative BigInteger", Logger.ERROR, true);
            throw new IllegalStateException("Negative BigInteger!");
        }
        byte[] buf = integer.toByteArray();
        if(buf.length > Short.MAX_VALUE)
            throw new IllegalStateException("Too long: "+buf.length);
        out.writeShort((short)buf.length);
        out.write(buf);
    }

    public int getDataLength() {
        int l = 4 + 4;
        for(int i=0;i<accuracy;i++) {
            synchronized(this) {
                l += 2 + centers[i].toByteArray().length;
                l += 2 + leftEdge[i].toByteArray().length;
                l += ra[i].getDataLength();
            }
        }
        return l;
    }

    public RecentReports recentReports() {
        return recent;
    }

    public double guessRaw(Key k) {
        return guess(k.toBigInteger(),0);
    }

    public double guessRaw(Key k, int age) {
        return guess(k.toBigInteger(), 0);
    }
    
    public double guessTime(Key k) {
        checkType(TIME, false);
        return guess(k.toBigInteger(),0);
    }

    public double guessProbability(Key k) {
        checkType(PROBABILITY, false);
        double d = guess(k.toBigInteger(),0);
        if(d < 0.0) {
            Core.logger.log(this, "Guessed probability: "+d+" on "+this,
                    Logger.ERROR);
            return 0.0;
        }
        if(d > 1.0) {
            Core.logger.log(this, "Guessed probability: "+d+" on "+this,
                    Logger.ERROR);
            return 1.0;
        }
        return d;
    }

    public double guessTransferRate(Key k) {
        checkType(TRANSFER_RATE, false);
        return guess(k.toBigInteger(),0);
    }

    public void reportTime(Key k, long millis) {
        checkType(TIME, true);
        report(k.toBigInteger(), millis);
    }

    /**
     * Check that the type of this estimator is the same as the report
     * or guess. Grumble if not.
     * @param t the expected type.
     * @param b is this a report? otherwise is a guess request.
     */
    private void checkType(Unit t, boolean b) {
        if(t != type) {
            Core.logger.log(this, "Type wrong on "+(b ? "reporting" : "guessing") +
                    ": "+type(t)+" should be "+type(), new Exception("debug"), Logger.ERROR);
        }
    }

    public void reportProbability(Key k, double p) {
        checkType(PROBABILITY, true);
        report(k.toBigInteger(), p);
    }

    public void reportTransferRate(Key k, double rate) {
        checkType(TRANSFER_RATE, true);
        report(k.toBigInteger(), rate);
    }

    protected Object getGDSSync() {
        return this;
    }

    /**
     * Guess a value for a given key.
     * Algorithm:
     * Find the two nearest sectors, by their centers.
     * Get their values.
     * Interpolate between them.
     * @param k the key to guess a value for
     * @param age ignored
     */
    double guess(BigInteger k, int age) {
//        if(logDEBUG)
//            Core.logger.log(this, "Guessing: "+HexUtil.biToHex(k)+" on "+this,
//                    Logger.DEBUG);
        BigInteger firstKeyAfter = null;
        BigInteger firstKeyBefore = null;
        BigInteger origK = k;
        double beforeValue = -1;
        double afterValue = -1;
        synchronized(this) {
            int idx = java.util.Arrays.binarySearch(centers, k);
            if(idx >= 0) {
                // Exact match! (rather unlikely)
                return getBucketValue(idx);
            } else {
                idx = (-idx)-1;
                // idx is now the insertion point.
                // so if it is 0, the key is smaller than the first entry
                // and if it is accuracy, it is larger than the last entry
                // so the first key after is idx
                // and the first key before is idx-1
                int after = idx;
                if(after >= accuracy) after -= accuracy; // cheaper than %, right?
                int before = idx-1;
                if(before < 0) before += accuracy;
                firstKeyAfter = centers[after];
                afterValue = getBucketValue(after);
                firstKeyBefore = centers[before];
                beforeValue = getBucketValue(before);
//                if(logDEBUG) {
//                    Core.logger.log(this, "After: "+after+": "+ra[after]+" = "+afterValue, Logger.DEBUG);
//                    Core.logger.log(this, "Before: "+before+": "+ra[before]+" = "+beforeValue, Logger.DEBUG);
//                }
            }
        }
        if(firstKeyBefore.compareTo(firstKeyAfter) == 1) {
            if(k.compareTo(firstKeyAfter) == -1)
                k = k.add(Key.KEYSPACE_SIZE);
            firstKeyAfter = firstKeyAfter.add(Key.KEYSPACE_SIZE);
        }
        // Found sector
        // Now just interpolate
        double interpolatedValue = interpolate(k, firstKeyBefore, beforeValue, firstKeyAfter, afterValue);

        if(/*logDEBUG || */interpolatedValue < 0.0) {
            String s = interpolatedValue+
            	" in guess("+HexUtil.biToHex(origK)+" -> "+HexUtil.biToHex(k)+
            	") on "+this+": keyBefore="+HexUtil.biToHex(firstKeyBefore)+
            	", keyAfter="+HexUtil.biToHex(firstKeyAfter)+", key="+k+
            	", beforeValue="+beforeValue+", afterValue="+afterValue;
//            if(interpolatedValue < 0.0) {
                Core.logger.log(this, "Interpolated crazy value: "+s, Logger.ERROR);
//            }
//            if(logDEBUG)
//                Core.logger.log(this, "Interpolated: "+s, Logger.DEBUG);
        }
        return interpolatedValue;
    }

    /**
     * Calculates, given the other input values, the interpolated value of 'key'.
     */
    protected double interpolate(BigInteger key, BigInteger firstKeyBefore, double beforeValue, BigInteger firstKeyAfter, double afterValue) {
        BigInteger bigdiff = firstKeyAfter.subtract(firstKeyBefore);
        BigInteger smalldiff = firstKeyAfter.subtract(key);

        BigDecimal bigdiffDecimal = new BigDecimal(bigdiff);
        BigDecimal smalldiffDecimal = new BigDecimal(smalldiff);
        BigDecimal proportion = smalldiffDecimal.divide(bigdiffDecimal, 20 /*FIXME: arbitrary!*/, BigDecimal.ROUND_DOWN);
        // FIXME: Converting to double loses accuracy.
        // This may be irrelevant but if so, then we shouldn't be storing
        // them as BigIntegers!!
        // See FastSlidingBucketsKeyspaceEstimator
        // IIRC it was just a PITA to do it all using BigDecimals...
        double p = proportion.doubleValue();
        double interpolatedValue =
            afterValue + p * (beforeValue - afterValue);
		return interpolatedValue;
	}

	/**
     * Get the current value of a bucket. Bootstrapping phase
     * smoothing occurs here.
     * @param index the index of the bucket to retrieve.
     * @return the current value of the given bucket.
     */
    private double getBucketValue(int index) {
        if(!doSmoothing) {
            return ra[index].currentValue();
        } else {
        double centerValue = ra[index].currentValue();
        // FIXME: make a parameter
        // Tried 25, think 10 may work better..
        long coreReports = ra[index].countReports();
        if(coreReports > TARGET_REPORTS) return centerValue;
        int totalReports = (int)coreReports;
        double weightedTotal = totalReports * centerValue;
        int before = index-1;
        int after = index+1;
        int prevBefore = Integer.MIN_VALUE;
        int prevAfter = Integer.MIN_VALUE;
//        if(logDEBUG)
//            Core.logger.log(this, "getBucketValue("+index+"): centerValue="+
//                    centerValue+", coreReports="+coreReports+", weightedTotal="+
//                    weightedTotal+", totalReports="+totalReports, Logger.DEBUG);
        while(true) {
            if(before == -1) before += accuracy;
            if(after == accuracy) after -= accuracy;
            boolean beforeDone = false;
            boolean afterDone = false;
            if(before == index && after == index) break;
            if(before == prevAfter && after == prevBefore) break;
            int neededReports = TARGET_REPORTS - totalReports;
//            if(logDEBUG)
//                Core.logger.log(this, "after="+after+", before="+before+
//                        ", neededReports="+neededReports, Logger.DEBUG);
            if(neededReports <= 0) break;
            double innerTotal = 0.0;
            int innerReports = 0;
            if(before != index && before != prevAfter) {
                int reports = (int)ra[before].countReports();
                innerReports += reports;
                innerTotal += (ra[before].currentValue() * reports);
            } else {
                beforeDone = true; 
            }
            if(after != index && after != prevBefore 
                    && after != before /* if they converge we want one value only */) {
                int reports = (int)ra[after].countReports();
                innerReports += reports;
                innerTotal += (ra[after].currentValue() * reports);
            } else {
                afterDone = true;
            }
            if(beforeDone && afterDone) break;
            if(neededReports >= innerReports) {
                totalReports += innerReports;
                weightedTotal += innerTotal;
            } else {
                totalReports += neededReports;
                weightedTotal += (innerTotal * neededReports) / innerReports;
            }
//            if(logDEBUG)
//                Core.logger.log(this, "innerReports: "+innerReports+
//                        ", innerTotal: "+innerTotal+", weightedTotal: "+
//                        weightedTotal+", totalReports: "+totalReports+
//                        ", beforeDone="+beforeDone+", afterDone="+afterDone, 
//                        Logger.DEBUG);
            if(before == after) break;
            prevBefore = before;
            prevAfter = after;
            before--;
            after++;
        }
        if(totalReports == 0) return centerValue; // return init value
//        if(logDEBUG)
//            Core.logger.log(this, "Returning "+weightedTotal/totalReports,
//                    Logger.DEBUG);
        return weightedTotal / totalReports;
    }
    }

    void report(BigInteger k, double d) {
        recent.report(k, d);
        synchronized(this) {
            errorCheckConsistency();
            if(logDEBUG)
                dump("Before report("+HexUtil.biToHex(k)+","+d+")", Logger.DEBUG, true);
            // Search CENTERS, not boundaries, because CENTERS ARE KEPT IN
            // ORDER !
            int sidx = java.util.Arrays.binarySearch(centers, k);
            
            if(sidx >= 0) {
                // Exact match on center sidx
                // Which is between boundaries sidx and sidx+1
                moveTowardsAndUpdateCentersNotDeadOn(k, sidx, d);
                return;
            } else {
                // Rather more complicated!
                int idx = -sidx-1;
                if(idx == accuracy) {
                    // We are after center # accuracy-1, at the right hand end
                    if(leftEdge[0].compareTo(leftEdge[1]) == -1) {
                        // leftEdge[0] < leftEdge[1]
                        // We are at the other end, so we are in [accuracy-1]
                        moveTowardsAndUpdateCentersNotDeadOn(k, accuracy-1, d);
                        return;
                    }
                } else if(idx == 0) {
                    // We are behind center #0
                    if(leftEdge[0].compareTo(centers[0]) == 1) {
                        // leftEdge[0] is on the other end so we are 
                        // definitely in sector 0
                        moveTowardsAndUpdateCentersNotDeadOn(k, 0, d);
                        return;
                    }
                }
                idx = idx % accuracy;
                int idxMinusOne = (idx + accuracy - 1) % accuracy;
                int cmp = leftEdge[idx].compareTo(k);
                if(cmp > 0) {
                    // leftEdge[idx] > k => we are in sector before.
                    // Not wrapped around because of above special cases.
                    moveTowardsAndUpdateCentersNotDeadOn(k, idxMinusOne, d);
                } else if(cmp < 0) {
                    // We are in sector idx
                    moveTowardsAndUpdateCentersNotDeadOn(k, idx, d);
                } else {
                    // leftEdge[idx] == k - we are dead on
                    moveTowardsAndUpdateCentersDeadOn(k, idx, d);
                }
            }
//            int idx = sidx;
//            int before;
//            int after;
//            int affectedSector1;
//            int affectedSector2;
//            int sectorWhoseRAIsAffected;
//            if(idx >= 0) {
//                // Exact match on the border
//                // Border stays where it is
//                // Move the borders towards the key
//                before = (idx + accuracy - 1) % accuracy;
//                after = (idx + 1) % accuracy;
//                affectedSector1 = (before + accuracy - 1) % accuracy;
//                affectedSector2 = (after + 1) % accuracy;
//                sectorWhoseRAIsAffected = idx;
//            } else {
//                idx = -idx-1;
//                idx = idx % accuracy;
//                before = (idx + accuracy - 1) % accuracy;
//                after = idx % accuracy;
//                affectedSector1 = (accuracy + before - 1) % accuracy;
//                affectedSector2 = -1;
//                /** Insert point is idx.
//                 * idx-1 is first key before.
//                 * idx is first key after.
//                 * Move the border closer to k of idx-1 towards k,
//                 * and the border closer to k of idx towards k.
//                 * The sector the key landed in is "before", because
//                 * as explained above, the boundary is the START of the
//                 * sector X, the center is then after it, in the middle of
//                 * sector X, and the RA for X covers that sector:
//                 * See the comments before the declarations.
//                 */
//                 sectorWhoseRAIsAffected = before;
//            }
//            moveDividingKey(before, k);
//            if(logDEBUG)
//                dump("After moved before", Logger.DEBUG, false);
//            moveDividingKey(after, k);
//            if(logDEBUG)
//                dump("After moved after", Logger.DEBUG, false);
//            updateCenters(before, after, affectedSector1, affectedSector2);
//            if(logDEBUG)
//                dump("After fixed centers", Logger.DEBUG, false);
//            ra[idx].report(d);
//            correctSlide();
//            
//            
//            errorCheckConsistency();
//            if(logDEBUG)
//                dump("After report("+HexUtil.biToHex(k)+","+d+"): sidx="+sidx+
//                        ", idx="+idx+", before="+before+", after="+after+
//                        ", affected="+affectedSector1+" and "+affectedSector2, 
//                        Logger.DEBUG, false);
        }
    }
    
    /**
     * @param k The reported key
     * @param sector The sector the report falls into. 
     * @param d The value of the actual report.
     */
    private void moveTowardsAndUpdateCentersNotDeadOn(BigInteger k, int sector, double d) {
        // We are between leftEdge[sector] and leftEdge[sector+1]
        int nextSector = sector + 1;
        int nextPlusSector = sector + 2;
        int prevSector = sector - 1;
        if(prevSector < 0) prevSector += accuracy;
        if(nextSector >= accuracy) nextSector -= accuracy;
        if(nextPlusSector >= accuracy) nextPlusSector -= accuracy;
        moveTowardsKDirectional(k, sector, true);
        moveTowardsKDirectional(k, (sector + 1) % accuracy, false);
        ra[sector].report(d);
        updateCenter(sector);
        updateCenter(nextSector);
        updateCenter(prevSector);
        updateCenter(nextPlusSector);
        correctSlide();
        errorCheckConsistency();
        if(logDEBUG)
            dump("Reported "+HexUtil.biToHex(k)+":"+d+
                    " via moveTowardsAndUpdateCentersNotDeadOn(k,"+sector+
                    ",d): nextSector="+nextSector+", prevSector="+prevSector+
                    ", nextPlusSector="+nextPlusSector, Logger.DEBUG, false);
    }

    /**
     * @param sector The sector whose left edge we are to move.
     * @param forward If true, move in the positive direction only. If
     * false, move in the negative direction only.
     */
    private void moveTowardsKDirectional(BigInteger k, int sector, boolean forward) {
        BigInteger edge = leftEdge[sector];
        BigInteger diff = edge.subtract(k);
        /**
         * If we are going forward, then we expect k > edge => diff<0
         * If we are going backward, then we expect edge > k => diff>0
         */
        if(forward) diff = diff.negate();
        if(diff.signum() < 0) {
            /**
             * Going forward, k < edge:
             * diff > 0
             * Then diff < 0 (invert).
             * Now diff < 0 so we are here.
             * Adding a keyspace will make us go forward.
             */
            /** 
             * Going backward, k > edge:
             * diff < 0
             * Then diff < 0 (don't invert).
             * We are pointed in the right direction but by the wrong amount.
             * If we add a keyspace we get the right amount.
             */
            diff = diff.add(Key.KEYSPACE_SIZE);
        } else {
            /**
             * Going forward, k > edge:
             * diff < 0
             * Invert: diff > 0, so we are here.
             * Right size, right direction.
             */
            /**
             * Going backwards, k < edge:
             * diff > 0
             * diff > 0 because no invert.
             * We are pointed in the wrong direction but we are the right size.
             */
        }
        if(!forward) diff = diff.negate();
        
        // Now diff is the right direction and the right size
        // Now scale it.
        
        BigDecimal bd = new BigDecimal(diff);
        bd = bd.multiply(movementFactor);
        BigInteger result = edge.add(bd.toBigInteger());
        
        if(result.signum() == -1) result = result.add(Key.KEYSPACE_SIZE);
        if(result.compareTo(Key.KEYSPACE_SIZE) == 1)
            result = result.subtract(Key.KEYSPACE_SIZE);
        leftEdge[sector] = result;
    }

    /**
     * Similar to above, but we hit a border dead-on.
     * @param k The key being reported
     * @param sector The sector whose left boundary the key is equal to.
     * @param d The value being reported.
     */
    private void moveTowardsAndUpdateCentersDeadOn(BigInteger k, int sector, double d) {
        // leftEdge[sector] should be unchanged
        int nextSector = sector + 1;
        int prevSector = sector - 1;
        int pprevSector = sector - 2;
        if(prevSector < 0) prevSector += accuracy;
        if(pprevSector < 0) pprevSector += accuracy;
        if(nextSector >= accuracy) nextSector -= accuracy;
        moveTowardsKDirectional(k, prevSector, true);
        moveTowardsKDirectional(k, nextSector, false);
        ra[sector].report(d);
        updateCenter(sector);
        updateCenter(prevSector);
        updateCenter(nextSector);
        updateCenter(pprevSector);
        correctSlide();
        errorCheckConsistency();
        if(logDEBUG)
            dump("Reported "+HexUtil.biToHex(k)+":"+d+
                    " via moveTowardsAndUpdateCentersNotDeadOn(k,"+sector+
                    ",d): nextSector="+nextSector+", prevSector="+prevSector+
                    ", nextPlusSector="+pprevSector, Logger.DEBUG, false);
    }

    /**
     * Correct any overall slide that occurred after a report.
     */
    private void correctSlide() {
        /** It may have wrapped one way or the other.
         * Either a:
         *  Wrapped backwards: [0] is 0xf..., just above [15]
         * Or b:
         *  Wrapped forwards: [15] is 0x1..., just below [0]
         */
        int count = 0;
        while(count < accuracy) {
            if(centers[0].compareTo(centers[1]) == 1) {
                count++;
                // centers[0] > centers[1]
                // [0] -> [15], [1] -> [0], [2] -> [1], ...
                BigInteger oldCenter = centers[0];
                BigInteger oldBorder = leftEdge[0];
                RunningAverage oldRA = ra[0];
                System.arraycopy(centers, 1, centers, 0, accuracy-1);
                System.arraycopy(leftEdge, 1, leftEdge, 0, accuracy-1);
                System.arraycopy(ra, 1, ra, 0, accuracy-1);
                centers[accuracy-1] = oldCenter;
                leftEdge[accuracy-1] = oldBorder;
                ra[accuracy-1] = oldRA;
                if(logDEBUG)
                    dump("After left shift "+count, Logger.DEBUG, false);
            } else if(centers[accuracy-1].compareTo(centers[accuracy-2]) == -1) {
                count++;
                // centers[15] < centers[14]
                // [15] -> [0], [0] -> [1], [1] -> [2], ...
                BigInteger oldCenter = centers[accuracy-1];
                BigInteger oldBorder = leftEdge[accuracy-1];
                RunningAverage oldRA = ra[accuracy-1];
                System.arraycopy(centers, 0, centers, 1, accuracy-1);
                System.arraycopy(leftEdge, 0, leftEdge, 1, accuracy-1);
                System.arraycopy(ra, 0, ra, 1, accuracy-1);
                centers[0] = oldCenter;
                leftEdge[0] = oldBorder;
                ra[0] = oldRA;
                if(logDEBUG)
                    dump("After right shift "+count, Logger.DEBUG, false);
            } else return;
        }
        Core.logger.log(this, "Too many shifts!: "+count+" for "+this, Logger.ERROR);
    }

    private void errorCheckConsistency() {
        String s = checkConsistency();
        if(s != null)
            dump(s, Logger.ERROR, true);
    }

    /**
     * Check the consistency of the sectors.
     * Consistency definition:
     * centers is strictly ascending.
     * leftEdge is strictly ascending within wraparound.
     * leftEdge[x] <= center[x] <= leftEdge[x+1]
     * (with wraparound)
     */
    private synchronized String checkConsistency() {
        BigInteger prevCenter = null;
        StringBuffer ret = null;
        for(int i=0;i<accuracy;i++) {
            BigInteger center = centers[i];
            if(center.signum() == -1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("NEGATIVE CENTER: "+HexUtil.biToHex(center));
            }
            if(center.compareTo(Key.KEYSPACE_SIZE) == 1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TOO HIGH CENTER: "+HexUtil.biToHex(center));
            }
            if(prevCenter != null && i != (accuracy-1) &&
                    prevCenter.compareTo(center) == 1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("INCONSISTENT: ["+i+"]: "+HexUtil.biToHex(center)+
                        " < prev "+HexUtil.biToHex(prevCenter)+"\n");
            }
            if(prevCenter != null && prevCenter.equals(center)) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TWO SECTORS EQUAL CENTERS: "+HexUtil.biToHex(center)+" at "+i+"\n");
            }
            BigInteger boundary = leftEdge[i];
            if(boundary.signum() == -1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("NEGATIVE BOUNDARY: "+HexUtil.biToHex(boundary));
            }
            if(boundary.compareTo(Key.KEYSPACE_SIZE) == 1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TOO HIGH BOUNDARY: "+HexUtil.biToHex(boundary));
            }
            if(i != (accuracy-1) && 
                    boundary.compareTo(center) == 1) {
                // boundary > center.
                // Could be due to wraparound, IF it's the first element.
                if(i == 0) continue;
                if(ret == null) ret = new StringBuffer();
                ret.append("INCONSISTENT: boundary["+i+"] > center["+i+"]\n");
            }
            // Don't need to check next boundary because of centers check.
        }
        if(centers[0].compareTo(centers[accuracy-1]) == 1) {
           if(ret == null) ret = new StringBuffer();
           ret.append("INCONSISTENT: [0] > [end]: [0] = "+HexUtil.biToHex(centers[0])+
                   ", [end] = "+HexUtil.biToHex(centers[accuracy-1])+"\n");
        }
        if(centers[0].equals(centers[accuracy-1])) {
            if(ret == null) ret = new StringBuffer();
            ret.append("INCONSISTENT: [0] == [end] = "+HexUtil.biToHex(centers[0])+"\n");
        }
        return ret == null ? null : new String(ret);
    }

    /**
     * Log the current status of the sectors, with a mesage.
     * @param message the message to log with the dump.
     * @param prio the log priority to log at
     */
    private void dump(String message, int prio, boolean stackTrace) {
        StringBuffer sb = new StringBuffer();
        sb.append(message);
        sb.append(" for ");
        sb.append(super.toString());
        sb.append(" dump:\n");
        for(int i=0;i<accuracy;i++) {
            sb.append(i);
            sb.append(": start ");
            sb.append(HexUtil.biToHex(leftEdge[i]));
            sb.append(" center ");
            sb.append(HexUtil.biToHex(centers[i]));
            sb.append(" value ");
            sb.append(ra[i].toString());
            sb.append('\n');
        }
        if(stackTrace)
            Core.logger.log(this, sb.toString(), new Exception("debug"), prio);
        else
            Core.logger.log(this, sb.toString(), prio);
    }

    private synchronized void updateCenter(int c1) {
        BigInteger lowerEdge = leftEdge[c1];
        BigInteger upperEdge = leftEdge[(c1 + 1) % accuracy];
        if(upperEdge.compareTo(lowerEdge) == 1) {
            // lowerEdge < upperEdge, all is normal, no wraparound
            centers[c1] = lowerEdge.add(upperEdge).shiftRight(1);
        } else {
            // upperEdge < lowerEdge, need to wrap around
            upperEdge = upperEdge.add(Key.KEYSPACE_SIZE);
            BigInteger movedTo = lowerEdge.add(upperEdge).shiftRight(1);
            if(movedTo.compareTo(Key.KEYSPACE_SIZE) == 1)
                movedTo = movedTo.subtract(Key.KEYSPACE_SIZE);
            centers[c1] = movedTo;
        }
    }

    public int maxAge() {
        return 0;
    }

    protected double guessRaw(BigInteger b, int age) {
        return guess(b, age);
    }

    public int countReports() {
        int totalReports = 0;
        for(int i=0;i<accuracy;i++) {
            totalReports += Math.max(0,ra[i].countReports());
        }
        return totalReports;
    }
    
    public boolean noReports() {
        for(int i=0;i<accuracy;i++) {
            if(ra[i].countReports() > 0) return false;
        }
        return true;
    }

    /**
     * @param bd
     */
    public void getBucketDistribution(BucketDistribution bd) {
        long max = 0;
        long min = Integer.MAX_VALUE;
        bd.setAccuracy(accuracy);
        for(int i=0;i<accuracy;i++) {
            long x = ra[i].countReports();
            bd.buckets[i] = x;
            bd.vals[i] = getBucketValue(i);
            bd.ras[i] = ra[i].toString();
            max = Math.max(x, max);
            min = Math.min(x, min);
        }
        bd.maxBucketReports = max;
        bd.minBucketReports = min;
    }

    public Object clone() {
        return new SlidingBucketsKeyspaceEstimator(this);
    }
}
