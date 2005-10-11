/*
 * Created on Jul 26, 2004
 *
 */
package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;

import freenet.Core;
import freenet.FieldSet;
import freenet.Key;
import freenet.support.Logger;
import freenet.support.HexUtil;
import freenet.support.Unit;

/**
 * @author Iakin
 * @author thelema  -- rewrote to use doubles internally 
 */
public class FloatBucketStore {

    /*
     * buckets[i].leftEdge is the boundaries.
     * buckets[i].center is the centers.
     * So buckets[i].ra serves the sector formed by boundaries 
     * buckets[i].leftEdge and buckets[i+1].leftEdge with center
     * buckets[i].center.
     * KEPT IN STRICTLY INCREASING ORDER (by the buckets' center keys).
     */
	private final Sector[] buckets;
	private final double movementFactor;
    final int TARGET_REPORTS = 10;
    final double KEYSPACE_SIZE = Key.KEYSPACE_SIZE.doubleValue();
    final boolean doSmoothing;
    
	public Iterator iterator(){
		return new BucketIterator();
	}
	public FloatBucketStore(RunningAverageFactory raf, BigDecimal mf, int accuracy, FieldSet set, Unit type, boolean doSmoothing) throws EstimatorFormatException {
		String l = set.getString("Accuracy");
		int acc;
		this.doSmoothing = doSmoothing;
		try {
		    acc = Integer.parseInt(l,16);
		} catch (NumberFormatException e) {
		    throw new EstimatorFormatException("Invalid Accuracy: "+l);
		}
		if(acc != accuracy)
		    throw new EstimatorFormatException("Different accuracy: "+acc+" should be "+accuracy);
		movementFactor = mf.doubleValue();
		buckets = new Sector[accuracy];
		FieldSet points = set.getSet("Points");
		if(points == null)
		    throw new EstimatorFormatException("No points!");
		for(int i=0;i<accuracy;i++) {
		    FieldSet point = points.getSet(Integer.toHexString(i));
		    if(point == null) throw new EstimatorFormatException("No point "+i);
		    RunningAverage _ra = raf.create(point.getSet("Value"));
		    // FIXME: use type to check range
		    String key = point.getString("DividingKey");
		    if(key == null) throw new EstimatorFormatException("No key "+i);
		    double _leftEdge;
		    try {
		    	_leftEdge = (new BigInteger(key, 16)).doubleValue();
		    } catch (NumberFormatException e) {
		        throw new EstimatorFormatException("Invalid key: "+key);
		    }
		    if(_leftEdge < 0)
		        throw new EstimatorFormatException("Negative key: "+_leftEdge+" for "+i);
		    if(_leftEdge > KEYSPACE_SIZE)
		        throw new EstimatorFormatException("Too big key: "+_leftEdge+" for "+i);
		    buckets[i] = new Sector(_ra,_leftEdge, -1);
		    buckets[i].updateIndex(i);
		}
		for(int i=0;i<accuracy;i++) {
		    buckets[i].updateCenter();
		}
		/** 
		 * Minimum sector size: 
		 * 1/10th of the size of one sector if all are equal size.
		 * This means about 40 hits in that sector at 5% movementFactor.
		 * The point here is to prevent fake passed in estimators from taking an
		 * excessively long time to correct.
		 */
		double minStep = KEYSPACE_SIZE / (accuracy * 10);
		for(int i=0;i<accuracy;i++) {
		    double thisCenter = buckets[i].center;
		    double prevCenter = buckets[i].prev().center;
		    if(thisCenter < prevCenter) {
		        // thisCenter < prevCenter
		        thisCenter += KEYSPACE_SIZE;
		    }
		    double diff = thisCenter - prevCenter;
		    if(diff < minStep)
		        throw new EstimatorFormatException("Overspecialized probably fake estimator: refusing to accept FieldSet: diff "+i+"="+diff);
		}
		correctSlide();
        String s = checkConsistency();
        if(s != null)
            throw new EstimatorFormatException("INCONSISTENT: "+s);
	}

	public FloatBucketStore(RunningAverageFactory raf, int accuracy, BigDecimal mf, DataInputStream dis, Unit type, boolean doSmoothing) throws IOException {
		buckets = new Sector[accuracy];
		this.movementFactor  =mf.doubleValue();
		this.doSmoothing = doSmoothing;
		for(int i=0;i<accuracy;i++) {
            double _center = HexUtil.readBigInteger(dis).doubleValue();
            double _leftEdge = HexUtil.readBigInteger(dis).doubleValue();
            buckets[i] = new Sector(raf.create(dis),_leftEdge,_center);
            buckets[i].updateIndex(i);
            if(i != 0 && buckets[i].leftEdge > buckets[i].center)
                throw new IOException("Border greater than center on "+i);
            if(!type.withinRange(buckets[i].ra.currentValue()))
                throw new IOException("Value out of range: "+buckets[i].ra.currentValue()+" from "+buckets[i].ra+" should be "+type.getMin()+"< x <"+type.getMax());
        }
        correctSlide();
        String s = checkConsistency();
        if(s != null)
            throw new IOException("Serialized inconsistent (and uncorrectable) estimator: "+s);
	}

	public FloatBucketStore(RunningAverageFactory factory, int accuracy, BigDecimal mf, double initValue, Unit type, boolean doSmoothing) {
		buckets = new Sector[accuracy];
		this.doSmoothing = doSmoothing;
		this.movementFactor = mf.doubleValue();
        // Initial dividing keys: gap should be keyspace / accuracy
		double a = KEYSPACE_SIZE;
		double b = KEYSPACE_SIZE / accuracy;
		double c = b / 2;
        for(int i=(accuracy-1);i>=0;i--) {
            a -= b;
            buckets[i] = new Sector(factory.create(initValue), a, a+c);
            buckets[i].updateIndex(i);
        }
	}

	public class Sector implements Comparable{
    	final RunningAverage ra;
    	double leftEdge;
    	double center;
    	private int myIndex = -1;
    	Sector(RunningAverage ra, double left, double center){
    		this.ra = ra;
    		this.leftEdge = left;
    		this.center = center;
    	}
		public int compareTo(Object o) { //Define the natural ordering of Items as the natural ordering of their centers
			double val2;
			if(o instanceof BigInteger) //NOTE! This is done so that this 'Sector' can act as a BigInteger wrt. comparision
                val2 = ((BigInteger)o).doubleValue();
			else
				val2 = ((Sector)o).center;
			if (center > val2) return 1;
			else if (center < val2) return -1;
			else return 0;
			
		}
		
		//Make sure to call this method whenever rearranging the order of the buckets
		private void updateIndex(int newIndex){
			myIndex = newIndex;
		}
		
		//Returns the sector after this one
		public Sector next() {
			int nextIndex = myIndex +1;
			if(nextIndex == buckets.length)
				nextIndex = 0;
			return buckets[nextIndex];
		}

		public double rightEdge() {
		    return next().leftEdge;
		}
		
		//Returns the sector previous to this one
		public Sector prev(){
			int prevIndex = myIndex-1;
			if(prevIndex<0)
				prevIndex = buckets.length-1;
			return buckets[prevIndex];
		}
		
		/**
		 * Moves the center-point to the exact middle of the sector
		 */
	    public void updateCenter() {
	        double lowerEdge = leftEdge;
	        double upperEdge = rightEdge();
	        if(upperEdge > lowerEdge) {
	            // lowerEdge < upperEdge, all is normal, no wraparound
	            center = (lowerEdge+upperEdge)/2;
	        } else {
	            // upperEdge < lowerEdge, need to wrap around
	            upperEdge = upperEdge + KEYSPACE_SIZE;
	            double movedTo = (lowerEdge+upperEdge)/2;
	            if(movedTo > KEYSPACE_SIZE)
	                movedTo -= KEYSPACE_SIZE;
	            center = movedTo;
	        }
	    }
	    /**
	     * Moves the sector's left edge towards the indicated key
	     * @param forward If true, move in the positive direction only. If
	     * false, move in the negative direction only.
	     */
	    public void moveTowardsKDirectional(double k,boolean forward) {
	        double edge = leftEdge;
	        double diff = edge-k;
	        /**
	         * If we are going forward, then we expect k > edge => diff<0
	         * If we are going backward, then we expect edge > k => diff>0
	         */
	        if(forward) diff = -diff;
	        if(diff < 0) {
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
	            diff += KEYSPACE_SIZE;
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
	        if(!forward) diff = -diff;
	        
	        // Now diff is the right direction and the right size
	        // Now scale it.
	        
	        diff *= movementFactor;
	        double result = edge + diff;
	        
	        if(result < 0) result += KEYSPACE_SIZE; 
	        if(result > KEYSPACE_SIZE) result -= KEYSPACE_SIZE;
	        leftEdge = result;
	    }
	    /**
	     * Get the current value of a bucket. Bootstrapping phase
	     * smoothing occurs here.
	     * @return the current value of the given bucket.
	     */
	    public double getBucketValue() {
	        double centerValue = ra.currentValue();
	        // FIXME: make a parameter
	        // Tried 25, think 10 may work better..
	        long coreReports = ra.countReports();
	        if(coreReports > TARGET_REPORTS) return centerValue;
	        int totalReports = (int)coreReports;
	        double weightedTotal = totalReports * centerValue;
	        int before = myIndex-1;
	        int after = myIndex+1;
	        int prevBefore = -1;
	        int prevAfter = -1;
//	        if(logDEBUG)
//	            Core.logger.log(this, "getBucketValue("+index+"): centerValue="+
//	                    centerValue+", coreReports="+coreReports+", weightedTotal="+
//	                    weightedTotal+", totalReports="+totalReports, Logger.DEBUG);
	        while(true) {
	            if(before == -1) before += buckets.length;
	            if(after == buckets.length) after -= buckets.length;
	            boolean beforeDone = false;
	            boolean afterDone = false;
	            if(before == myIndex && after == myIndex) break;
	            if(before == prevAfter && after == prevBefore) break;
	            int neededReports = TARGET_REPORTS - totalReports;
//	            if(logDEBUG)
//	                Core.logger.log(this, "after="+after+", before="+before+
//	                        ", neededReports="+neededReports, Logger.DEBUG);
	            if(neededReports <= 0) break;
	            double innerTotal = 0.0;
	            int innerReports = 0;
	            if(before != myIndex && before != prevAfter) {
	            	Sector sBefore = buckets[before];
	                int reports = (int)sBefore.ra.countReports();
	                innerReports += reports;
	                innerTotal += sBefore.ra.currentValue() * reports;
	            } else {
	                beforeDone = true; 
	            }
	            if(after != myIndex && after != prevBefore 
	                    && after != before /* if they converge we want one value only */) {
	            	Sector sAfter = buckets[after];
	                int reports = (int)sAfter.ra.countReports();
	                innerReports += reports;
	                innerTotal += sAfter.ra.currentValue() * reports;
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
//	            if(logDEBUG)
//	                Core.logger.log(this, "innerReports: "+innerReports+
//	                        ", innerTotal: "+innerTotal+", weightedTotal: "+
//	                        weightedTotal+", totalReports: "+totalReports+
//	                        ", beforeDone="+beforeDone+", afterDone="+afterDone, 
//	                        Logger.DEBUG);
	            if(before == after) break;
	            prevBefore = before;
	            prevAfter = after;
	            before--;
	            after++;
	        }
	        if(totalReports == 0) return centerValue; // return init value
//	        if(logDEBUG)
//	            Core.logger.log(this, "Returning "+weightedTotal/totalReports,
//	                    Logger.DEBUG);
	        return weightedTotal / totalReports;
	    }

	    public StringBuffer isConsistent (int location) {
	        StringBuffer ret = null;
        	if(myIndex != location) {
                if(ret == null) ret = new StringBuffer();
                ret.append("BUCKETINDEX OUT OF SYNC: "+buckets[location]);
            }
            if(center < 0) {
                if(ret == null) ret = new StringBuffer();
                ret.append("NEGATIVE CENTER: "+center);
            }
            if(center > KEYSPACE_SIZE) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TOO HIGH CENTER: "+center);
            }
            if(location != buckets.length-1 && next().center < center) { 
                if(ret == null) ret = new StringBuffer();
                ret.append("INCONSISTENT: ["+location+"]: "+center+
                        " < next "+next().center+"\n");
            }
            if(next().center == center) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TWO SECTORS EQUAL CENTERS: "+center+" at "+location+"\n");
            }
            if(leftEdge < 0) {
                if(ret == null) ret = new StringBuffer();
                ret.append("NEGATIVE BOUNDARY: "+leftEdge);
            }
            if(leftEdge > KEYSPACE_SIZE) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TOO HIGH BOUNDARY: "+leftEdge);
            }
            if(location != 0 && leftEdge > center) {
                // boundary > center.
                // Could be due to wraparound, IF it's the first element.
                if(ret == null) ret = new StringBuffer();
                ret.append("INCONSISTENT: leftEdge["+location+"] > center["+location+"]\n");
            }
            // Don't need to check next boundary because of centers check.
            return ret;
	    }
	    
    }
    /**
	 * @return true iff the leftEdge[0] > center[0].., meaning that part of the
	 * first sector is somwhere high up in the keyspace. 
	 */
    private boolean isWrapped(){
    	return buckets[0].leftEdge > buckets[0].center;
    }
	/**
	 * @param k The key whose bucket to find
	 * @return Information about the bucket where the key belongs
	 */
	public FindResult findIndex(BigInteger k) {
		//Search CENTERS, not boundaries, because CENTERS ARE KEPT IN
        // ORDER !
		int sidx = java.util.Arrays.binarySearch(buckets, k);
        if(sidx >= 0) {
            // Exact match on center sidx
            // Which is between boundaries sidx and sidx+1
            return new FindResult(buckets[sidx],FindResult.DEAD_ON_CENTER);
        } else {
            // Rather more complicated!
            int idx = -sidx-1;
            
            /**First check for two special cases;
             * Where the key happens to be to the right of the last bucket's center
             * Where the key happens to be before the first bucket's center
             */
            Sector firstSector = buckets[0];
            Sector lastSector = buckets[buckets.length-1];
            if(idx == buckets.length) {
                // We are after center for the last sector, at the right hand end
                if(!isWrapped()) {
                    // firstSector.leftEdge < firstSector.center < lastSector.center
                    // We are at the high end, so we can now be sure that we are in the last bucket
                	return new FindResult(lastSector,FindResult.RIGHT_OF_CENTER);
                }
            }
            if(idx == 0) {
                // We are on the left-hand side of the center of the first sector
                if(isWrapped()) {
                    // leftEdge[0] > center[0] (to the right of the keyspace) ->
                	// we are definitely in the first sector
                    return new FindResult(firstSector,FindResult.LEFT_OF_CENTER);
                }
            }

            //The common case:
            //We have hit somewhere between center[idx-1] and center[idx].
            //Figure out which of the buckets that really owns the key (leftEdge[idx] decides that)
            Sector sector = buckets[idx % buckets.length];
            double k1 = k.doubleValue();
            if(sector.leftEdge > k1) {
                // leftEdge[idx] > k => we are in sector before.
                // Not wrapped around because of above special cases.
            	return new FindResult(sector.prev(),FindResult.RIGHT_OF_CENTER);
            } else if(sector.leftEdge < k1) {
                // We are in sector idx
            	return new FindResult(sector,FindResult.LEFT_OF_CENTER);
            } else {
                // leftEdge[idx] == k - we are dead on
            	return new FindResult(sector,FindResult.DEAD_ON_CENTER); //This should never happen since we have already checked for that above
            }
        }
	}
	public class FindResult{
		Sector sector;
		int hitType;
		static final int DEAD_ON_CENTER=0;
		static final int LEFT_OF_CENTER=1;
		static final int RIGHT_OF_CENTER=2;
		FindResult(Sector s, int hitType){
			this.sector = s;
			this.hitType = hitType;
		}
	}
	
	/**
	 * [0] -> [15], [1] -> [0], [2] -> [1], ...
	 */
	public void rotateLeft() {
		Sector oldItem = buckets[0];
        System.arraycopy(buckets, 1, buckets, 0, buckets.length-1);
        buckets[buckets.length-1] = oldItem;
        for(int i = 0; i<buckets.length;i++)
        	buckets[i].updateIndex(i);
	}
	
	/**
	 *  [15] -> [0], [0] -> [1], [1] -> [2], ...
	 */
	public void rotateRight() {
		Sector oldItem = buckets[buckets.length-1];
        System.arraycopy(buckets, 0, buckets, 1, buckets.length-1);
        buckets[0] = oldItem;
        for(int i = 0; i<buckets.length;i++)
        	buckets[i].updateIndex(i);
	}
    /**
     * Check the consistency of the sectors.
     * Consistency definition:
     * centers is strictly ascending.
     * leftEdge is strictly ascending within wraparound.
     * leftEdge[x] <= center[x] <= leftEdge[x+1]
     * (with wraparound)
     */
	public String checkConsistency() {
		double prevCenter;
        StringBuffer ret = null;
        for(int i=0;i<buckets.length;i++) {
            StringBuffer ret1 = buckets[i].isConsistent(i);
            if (ret1 != null) {
                	if (ret == null) ret = ret1;
                	else ret.append(ret1);
            }
        }
        return ret == null ? null : new String(ret);
	}
	/**
	 * @param fs
	 */
	public void toFieldSet(FieldSet fs) {
		fs.put("Accuracy", Integer.toHexString(buckets.length));
        FieldSet points = new FieldSet();
        fs.put("Points", points);
        for(int i=0;i<buckets.length;i++) {
                FieldSet pt = new FieldSet();
                points.put(Integer.toHexString(i), pt);
                pt.put("Value", buckets[i].ra.toFieldSet());
                pt.put("DividingKey", HexUtil.biToHex((new BigDecimal(buckets[i].leftEdge)).toBigInteger()));
        }
		
	}
	/**
	 * @param out
	 * @throws IOException
	 */
	public void writeDataTo(DataOutputStream out) throws IOException {
        out.writeInt(buckets.length);
        for(int i=0;i<buckets.length;i++) {
            	HexUtil.writeBigInteger((new BigDecimal(buckets[i].center)).toBigInteger(), out);
                HexUtil.writeBigInteger((new BigDecimal(buckets[i].leftEdge)).toBigInteger(), out);
                buckets[i].ra.writeDataTo(out);
            }
	}
	/**
	 * @return
	 */
	public int getDataLength() {
    	Iterator it = iterator();
    	int l = 4; //bucketcount int
    	while(it.hasNext()) {
    		Sector s = (Sector)it.next();
            l += 8 + s.ra.getDataLength();
        }
    	return l;
	}
	/**
     * Correct any overall slide that occurred after a report.
     */
    public void correctSlide() {
        /** It may have wrapped one way or the other.
         * Either a:
         *  Wrapped backwards: [0] is 0xf..., just above [15]
         * Or b:
         *  Wrapped forwards: [15] is 0x1..., just below [0]
         */
        int count = 0;
        while(count < buckets.length) {
        	Sector sFirst = buckets[0];
        	Sector sLast = sFirst.prev();
        	count++;
            if(sFirst.center > sFirst.next().center) {
                // centers[0] > centers[1]
                rotateLeft();
                //if(logDEBUG)
                //    dump("After left shift "+count, Logger.DEBUG, false);
            } else if(sLast.center < sLast.prev().center) {
                // centers[15] < centers[14]
                rotateRight();
                //if(logDEBUG)
                //    dump("After right shift "+count, Logger.DEBUG, false);
            } else return;
        }
        Core.logger.log(this, "Too many shifts!: "+count+" for "+this, Logger.ERROR);
    }
    private class BucketIterator implements Iterator{
    	int index;
		public void remove(){
			throw new RuntimeException("Remove not allowed");
		}

		public boolean hasNext() {
			return index < buckets.length;
		}

		public Object next() {
			index++;
			return buckets[index-1];
		}	
    }
    public int getAccuracy() {
        return buckets.length;
    }
}
