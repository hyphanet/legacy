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
 *
 */
public class BucketStore {

    /*
     * buckets[i].leftEdge is the boundaries.
     * buckets[i].center is the centers.
     * So buckets[i].ra serves the sector formed by boundaries 
     * buckets[i].leftEdge and buckets[i+1].leftEdge with center
     * buckets[i].center.
     * KEPT IN STRICTLY INCREASING ORDER (by the buckets' center keys).
     */
	private final Sector[] buckets;
	private final BigDecimal movementFactor;
    final int TARGET_REPORTS = 10;
	
	public Iterator iterator(){
		return new BucketIterator();
	}
	public BucketStore(RunningAverageFactory raf, BigDecimal mf, int accuracy, FieldSet set, Unit type) throws EstimatorFormatException {
		String l = set.getString("Accuracy");
		int acc;
		try {
		    acc = Integer.parseInt(l,16);
		} catch (NumberFormatException e) {
		    throw new EstimatorFormatException("Invalid Accuracy: "+l);
		}
		if(acc != accuracy)
		    throw new EstimatorFormatException("Different accuracy: "+acc+" should be "+accuracy);
		movementFactor = mf;
		buckets = new Sector[accuracy];
		FieldSet points = set.getSet("Points");
		if(points == null)
		    throw new EstimatorFormatException("No points!");
		for(int i=0;i<accuracy;i++) {
		    FieldSet point = points.getSet(Integer.toHexString(i));
		    if(point == null) throw new EstimatorFormatException("No point "+i);
		    RunningAverage _ra = raf.create(point.getSet("Value")); //FIXME: use type to check range
		    String key = point.getString("DividingKey");
		    if(key == null) throw new EstimatorFormatException("No key "+i);
		    BigInteger _leftEdge;
		    try {
		    	_leftEdge = new BigInteger(key, 16);
		    } catch (NumberFormatException e) {
		        throw new EstimatorFormatException("Invalid key: "+key);
		    }
		    if(_leftEdge.signum() == -1)
		        throw new EstimatorFormatException("Negative key: "+_leftEdge+" for "+i);
		    if(_leftEdge.compareTo(Key.KEYSPACE_SIZE) == 1)
		        throw new EstimatorFormatException("Too big key: "+_leftEdge+" for "+i);
		    buckets[i] = new Sector(_ra,_leftEdge,null);
		    buckets[i].updateIndex(i);
		}
		/** 
		 * Minimum sector size: 
		 * 1/10th of the size of one sector if all are equal size.
		 * This means about 40 hits in that sector at 5% movementFactor.
		 * The point here is to prevent fake passed in estimators from taking an
		 * excessively long time to correct.
		 */
		BigInteger minStep = Key.KEYSPACE_SIZE.divide(BigInteger.valueOf(accuracy * 10));
		for(int i=0;i<accuracy;i++) {
			buckets[i].updateCenter();
		    BigInteger thisEdge = buckets[i].leftEdge;
		    BigInteger prevEdge = buckets[i].prev().leftEdge;
		    if(thisEdge.compareTo(prevEdge) == -1) {
		        // thisEdge < prevEdge means we're in a wraparound situation
		        thisEdge = thisEdge.add(Key.KEYSPACE_SIZE);
		    }
		    BigInteger diff = thisEdge.subtract(prevEdge);
		    if(diff.compareTo(minStep) == -1)
		        throw new EstimatorFormatException("Overspecialized probably fake estimator: refusing to accept FieldSet: diff "+i+"="+diff);
		}
		correctSlide();
        String s = checkConsistency();
        if(s != null)
            throw new EstimatorFormatException("INCONSISTENT: "+s);
	}

	public BucketStore(RunningAverageFactory raf, int accuracy, BigDecimal mf, DataInputStream dis, Unit type) throws IOException {
		buckets = new Sector[accuracy];
		this.movementFactor  =mf;
		for(int i=0;i<accuracy;i++) {
            BigInteger _center = HexUtil.readBigInteger(dis);
            BigInteger _leftEdge = HexUtil.readBigInteger(dis);
            buckets[i] = new Sector(raf.create(dis),_leftEdge,_center);
            buckets[i].updateIndex(i);
            if(i != 0 && buckets[i].leftEdge.compareTo(buckets[i].center) == 1)
                throw new IOException("Border greater than center on "+i);
            if(!type.withinRange(buckets[i].ra.currentValue()))
                throw new IOException("Value out of range: "+buckets[i].ra.currentValue()+" from "+buckets[i].ra+" should be "+type.getMin()+"< x <"+type.getMax());
        }
        correctSlide();
        String s = checkConsistency();
        if(s != null)
            throw new IOException("Serialized inconsistent (and uncorrectable) estimator: "+s);
	}

	public BucketStore(RunningAverageFactory factory, int accuracy, BigDecimal mf, double initValue) {
		buckets = new Sector[accuracy];
		this.movementFactor = mf;
        // Initial dividing keys: gap should be keyspace / accuracy
		BigInteger a = Key.KEYSPACE_SIZE.subtract(BigInteger.ONE);
		BigInteger b = a.divide(BigInteger.valueOf(accuracy));
		BigInteger c = b.shiftRight(1);
        for(int i=(accuracy-1);i>=0;i--) {
            a = a.subtract(b);
            buckets[i] = new Sector(factory.create(initValue), a,a.add(c));
            buckets[i].updateIndex(i);
        }
	}

	public class Sector implements Comparable{
    	final RunningAverage ra;
    	BigInteger leftEdge;
    	BigInteger center;
    	private int myIndex = -1;
    	Sector(RunningAverage ra, BigInteger left, BigInteger center){
    		this.ra = ra;
    		this.leftEdge = left;
    		this.center = center;
    	}
		public int compareTo(Object o) { //Define the natural ordering of Items as the natural ordering of their centers
			if(o instanceof BigInteger) //NOTE! This is done so that this 'Sector' can act as a BigInteger wrt. comparision
                return center.compareTo((BigInteger)o);
			else
				return center.compareTo(((Sector)o).center);
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
	        BigInteger lowerEdge = leftEdge;
	        BigInteger upperEdge = next().leftEdge;
	        if(upperEdge.compareTo(lowerEdge) == 1) {
	            // lowerEdge < upperEdge, all is normal, no wraparound
	            center = lowerEdge.add(upperEdge).shiftRight(1);
	        } else {
	            // upperEdge < lowerEdge, need to wrap around
	            upperEdge = upperEdge.add(Key.KEYSPACE_SIZE);
	            BigInteger movedTo = lowerEdge.add(upperEdge).shiftRight(1);
	            if(movedTo.compareTo(Key.KEYSPACE_SIZE) == 1)
	                movedTo = movedTo.subtract(Key.KEYSPACE_SIZE);
	            center = movedTo;
	        }
	    }
	    /**
	     * Moves the sector's left edge towards the indicated key
	     * @param forward If true, move in the positive direction only. If
	     * false, move in the negative direction only.
	     */
	    public void moveTowardsKDirectional(BigInteger k,boolean forward) {
	        BigInteger edge = leftEdge;
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

    }
    /**
	 * @return true iff the leftEdge[0] > center[0].., meaning that part of the
	 * first sector is somwhere high up in the keyspace. 
	 */
    private boolean isWrapped(){
    	return buckets[0].leftEdge.compareTo(buckets[0].center) == 1;
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
            boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
            if(logDEBUG)
                Core.logger.log(this, "Sector: "+sector+" idx: "+idx+" key: "+HexUtil.biToHex(k), Logger.DEBUG);
            int cmp = sector.leftEdge.compareTo(k);
            if(logDEBUG)
                Core.logger.log(this, "cmp: "+cmp, Logger.DEBUG);
            if(cmp > 0) {
                // leftEdge[idx] > k => we are in sector before.
                // Not wrapped around because of above special cases.
            	return new FindResult(sector.prev(),FindResult.RIGHT_OF_CENTER);
            } else if(cmp < 0) {
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
		public String toString() {
		    StringBuffer sb = new StringBuffer(20);
		    sb.append("Sector: ");
		    sb.append(sector.toString());
		    sb.append(", hitType: ");
		    if(hitType == DEAD_ON_CENTER)
		        sb.append("dead on");
		    else if(hitType == LEFT_OF_CENTER)
		        sb.append("left of center");
		    else if(hitType == RIGHT_OF_CENTER)
		        sb.append("right of center");
		    else sb.append(hitType);
		    return sb.toString();
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
		BigInteger prevCenter = null;
        StringBuffer ret = null;
        for(int i=0;i<buckets.length;i++) {
        	if(buckets[i].myIndex != i) {
                if(ret == null) ret = new StringBuffer();
                ret.append("BUCKETINDEX OUT OF SYNC: "+buckets[i]);
            }
            BigInteger center = buckets[i].center;
            if(center.signum() == -1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("NEGATIVE CENTER: "+HexUtil.biToHex(center));
            }
            if(center.compareTo(Key.KEYSPACE_SIZE) == 1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TOO HIGH CENTER: "+HexUtil.biToHex(center));
            }
            if(prevCenter != null && i != (buckets.length-1) &&
                    prevCenter.compareTo(center) == 1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("INCONSISTENT: ["+i+"]: "+HexUtil.biToHex(center)+
                        " < prev "+HexUtil.biToHex(prevCenter)+"\n");
            }
            if(prevCenter != null && prevCenter.equals(center)) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TWO SECTORS EQUAL CENTERS: "+HexUtil.biToHex(center)+" at "+i+"\n");
            }
            BigInteger boundary = buckets[i].leftEdge;
            if(boundary.signum() == -1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("NEGATIVE BOUNDARY: "+HexUtil.biToHex(boundary));
            }
            if(boundary.compareTo(Key.KEYSPACE_SIZE) == 1) {
                if(ret == null) ret = new StringBuffer();
                ret.append("TOO HIGH BOUNDARY: "+HexUtil.biToHex(boundary));
            }
            if(i != (buckets.length-1) && 
                    boundary.compareTo(center) == 1) {
                // boundary > center.
                // Could be due to wraparound, IF it's the first element.
                if(i == 0) continue;
                if(ret == null) ret = new StringBuffer();
                ret.append("INCONSISTENT: boundary["+i+"] > center["+i+"]\n");
            }
            // Don't need to check next boundary because of centers check.
        }
        if(buckets[0].center.compareTo(buckets[buckets.length-1].center) == 1) {
           if(ret == null) ret = new StringBuffer();
           ret.append("INCONSISTENT: [0] > [end]: [0] = "+HexUtil.biToHex(buckets[0].center)+
                   ", [end] = "+HexUtil.biToHex(buckets[buckets.length-1].center)+"\n");
        }
        if(buckets[0].center.equals(buckets[buckets.length-1].center)) {
            if(ret == null) ret = new StringBuffer();
            ret.append("INCONSISTENT: [0] == [end] = "+HexUtil.biToHex(buckets[0].center)+"\n");
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
                pt.put("DividingKey", HexUtil.biToHex(buckets[i].leftEdge));
        }
		
	}
	/**
	 * @param out
	 * @throws IOException
	 */
	public void writeDataTo(DataOutputStream out) throws IOException {
        out.writeInt(buckets.length);
        for(int i=0;i<buckets.length;i++) {
                HexUtil.writeBigInteger(buckets[i].center, out);
                HexUtil.writeBigInteger(buckets[i].leftEdge, out);
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
        	//FIXME: Does these items change length? If so.. how to ensure that the data length
        	//stays valid until it is used?
    		Sector s = (Sector)it.next();
            l += 2 + s.center.toByteArray().length;
            l += 2 + s.leftEdge.toByteArray().length;
            l += s.ra.getDataLength();
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
            if(sFirst.center.compareTo(sFirst.next().center) == 1) {
                // centers[0] > centers[1]
                rotateLeft();
                //if(logDEBUG)
                //    dump("After left shift "+count, Logger.DEBUG, false);
            } else if(sLast.center.compareTo(sLast.prev().center) == -1) {
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
