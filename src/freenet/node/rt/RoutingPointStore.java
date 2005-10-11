package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;

import net.i2p.util.NativeBigInteger;

import EDU.oswego.cs.dl.util.concurrent.ReadWriteLock;
import EDU.oswego.cs.dl.util.concurrent.WriterPreferenceReadWriteLock;

import freenet.Core;
import freenet.FieldSet;
import freenet.Key;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.Unit;
import freenet.support.sort.QuickSorter;
import freenet.support.sort.Sortable;

/**
 * @author Iakin Created on Nov 2, 2003
 */
class RoutingPointStore {
	
	private boolean logDEBUG = true;
	
	//Use a lock that allows for multiple concurrent read-accesses
	//to the store. Decreases lock contention and improves general
	//routing throughput. Note, this lock does _not_ support reentrance
	//TODO: Replace with java.util.concurrent.RWLock when Java 1.5 is more widespread
	private final ReadWriteLock rw = new WriterPreferenceReadWriteLock();
	
	//A store for routingpoint, synch using the rw-lock above before
	//accessing/modifying its contents
	protected RoutingPoint points[];
	 
	private final RoutingPointStore.RoutingPointKeySorter ms =
		new RoutingPointKeySorter();
	private static final int MAX_ACCURACY = 32;

	private static int SERIAL_MAGIC = 0x434c8ba6;
	
	private double lowestRaw = -1; //Caches the the current lowestRaw() value
	private boolean knowsLowestRaw = false; //Is the value above dirty or not
	private Object lowestRawSync = new Object(); //Sync object for the two items above

	public Object clone() {
	    return new RoutingPointStore(this);
	}
	
	/** Represents a point in a routing graph.  Is the fundamental storage item of the RoutingStore */
	static class RoutingPoint {
		
		//Underlying values write accessible only to this class
		//If anyone wants to modify RoutingPoints they need to use
		//a RoutingPointEditor instance
		private BigInteger key;
		private double estimate;
		private double age;
		
		RoutingPoint() {
			key = null;
			estimate = 0;
			age = 0;
		}
		RoutingPoint(BigInteger key, double estimate, double age) {
			this.key = key;
			this.estimate = estimate;
			this.age = age;
		}

		/**
		 * Create a RoutingPoint from a FieldSet, serialized for network Check
		 * against the prev point to enforce ordering etc
		 * 
		 * @param set
		 * @param prev
		 */
		public RoutingPoint(
			FieldSet set,
			RoutingPoint prev, Unit type) throws EstimatorFormatException {
			if (set == null)
				throw new EstimatorFormatException("no point");
			String keyAsString = set.getString("Key");
			if (keyAsString == null)
				throw new EstimatorFormatException("no key");
			try {
				key = new NativeBigInteger(keyAsString, 16);
			} catch (NumberFormatException e) {
				throw new EstimatorFormatException("NFE: " + e);
			}
			if (key.signum() == -1)
				throw new EstimatorFormatException("negative key");
			if (key.compareTo(Key.KEYSPACE_SIZE) == 1)
				throw new EstimatorFormatException("key exceeds keyspace");
			if (prev != null) {
				int c = key.compareTo(prev.key);
				if (c != -1)
					throw new EstimatorFormatException("key lower than previous key");
				if (c == 0)
					throw new EstimatorFormatException("key equal to previous key");
			}
			String estimateAsString = set.getString("Value");
			// FIXME: Needs to be changed to "Estimate" but I'm not sure if
			// that will screw things up.
			if (estimateAsString == null)
				throw new EstimatorFormatException("no value");
			try {
			    estimate = type.toRaw(Double.parseDouble(estimateAsString));
			} catch (NumberFormatException e) {
			    EstimatorFormatException ex = new EstimatorFormatException("Couldn't parse value: "+e);
			    ex.initCause(e);
			    throw ex;
			}
			age = 0.0;
		}

		//Expose underlying values for reading only to others
		public BigInteger getKey() {
			return key;
		}

		public double getAge() {
			return age;
		}

		public double getEstimate() {
			return estimate;
		}

		protected Object clone() {
			return new RoutingPoint(key, estimate, age);
		}
		/**
		 * @return a FieldSet containing the basic data for this point. Age is
		 *         NOT recorded because it is not used by FNP and is none of
		 *         its business.
		 */
		public FieldSet toFieldSet() {
			FieldSet fs = new FieldSet();
			fs.put("Key", HexUtil.bytesToHex(key.toByteArray()));
			fs.put("Value", Double.toString(estimate));
			// FIXME: Also, should be changed to "Estimate"
			return fs;
		}
	}
	
	class RoutingPointEditor {
		
		private RoutingPoint p;
		private BigInteger key;
		private double estimate;
		private double age;
		private boolean keyDirty, estimateDirty, ageDirty;
		private int index;

		RoutingPointEditor(int index) {
			this.index = index;
			this.p = points[index];
			keyDirty = estimateDirty = ageDirty = false;
		}
		void commit() {
			if(estimateDirty)
			{
				synchronized(lowestRawSync){
					if(p.estimate == lowestRaw){
						if(estimate > p.estimate){
							knowsLowestRaw = false;
						}else {
							lowestRaw = estimate;
						}
					}
				}
			}
			try{
				aquireWriteLock();
				notifyPrePointModified(index);
				//Tell the store that we are about to modifiy a point
				p.key = key;
				p.estimate = estimate;
				p.age = age;
			}finally{
				releaseWriteLock();
			}
			keyDirty = estimateDirty = ageDirty = false;

		}
		public BigInteger getKey() {
			if (keyDirty)
				return key;
			else
				return p.key;
		}
		public void setKey(BigInteger key) {
			this.key = key;
			keyDirty = true;
		}

		public double getAge() {
			if (ageDirty)
				return age;
			else
				return p.age;
		}

		public void setAge(double age) {
			this.age = age;
			ageDirty = true;
		}

		public double getEstimate() {
			if (estimateDirty)
				return estimate;
			else
				return p.estimate;
		}

		public void setEstimate(double estimate) {
			this.estimate = estimate;
			estimateDirty = true;
		}
	}

	//A pair of two neighbouring RoutingPoints, including information on
	// wheter or not
	//there is a keyspace wrap between them (right-key actually a larger value
	// than left-key)
	static class NeighbourRoutingPointPair {
		RoutingPointStore.RoutingPoint left, right;
		//The key before and the key after the supplied key
		boolean includesKeyspaceWrap;
		//Wheter or not a keyspace wrap is actually between the two
		// RoutingPoints
		int nPos;
		//TODO:Get rid of this one. The position of the key that before and
		// after is just that.
	}

	/** Sorts the store in order of the RoutingPoint keys. */
	private class RoutingPointKeySorter implements Sortable {
		public final int compare(int index1, int index2) {
			return points[index1].key.compareTo(points[index2].key);
		}

		public final void swap(int index1, int index2) {
			RoutingPoint p = points[index1];
			points[index1] = points[index2];
			points[index2] = p;
		}

		public final int size() {
			return length();
		}
	}

	//Create fresh routing store with 'accuracy' number of default-valued
	// RoutingPoints
	//All estimate values will be set to the supplied 'initEstimate'
	public RoutingPointStore(int accuracy, double initEstimate) {
		points = new RoutingPoint[accuracy];
		if (initEstimate < 0)
			throw new IllegalArgumentException("negative initTime");
		for (int x = 0; x < points.length; x++) {
			points[x] = new RoutingPoint();
		}
		distributeKeysEvenly();
		setAllEstimates(initEstimate);
		synchronized(lowestRawSync){
			lowestRaw = initEstimate;
			knowsLowestRaw = true;
		}
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	}

	//Create a store by reading a serialization
	public RoutingPointStore(
		DataInputStream i,
		double maxAllowedEstimate,
		double minAllowedEstimate)
		throws IOException {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		int magic = i.readInt();
		if(magic != SERIAL_MAGIC)
		    throw new IOException("bad magic");
		int ver = i.readInt();
		if(ver != 1)
		    throw new IOException("bad version: "+ver);
		int accuracy = i.readInt();
		if (accuracy < 0)
			throw new IOException("failed read");
		if (accuracy == 0
			|| accuracy > MAX_ACCURACY) // seems reasonable bounds
			throw new IOException("Invalid accuracy word " + accuracy);
		points = new RoutingPoint[accuracy];

		BigInteger prev = BigInteger.ZERO;

		for (int x = 0; x < points.length; x++) {
			double time = i.readDouble();
			//estimate[x] = i.readInt();
			if (time < 0)
				throw new IOException("negative value");
			if (time < minAllowedEstimate)
				throw new IOException(
					"Read estimate "
						+ time
						+ " smaller than minimum allowed  "
						+ minAllowedEstimate);
			if (time > maxAllowedEstimate)
				throw new IOException(
					"Read estimate "
						+ time
						+ " larger than maximum allowed  "
						+ maxAllowedEstimate);
			byte[] b = new byte[Key.KEYBYTES];
			i.readFully(b);
			BigInteger key = new NativeBigInteger(1, b);
			if (key.signum() == -1)
				throw new IOException("negative key");
			if (key.compareTo(Key.KEYSPACE_SIZE) == 1)
				throw new IOException("exceeded keyspace");
			int c = key.compareTo(prev);
			if (c == -1)
				throw new IOException("smaller than prev");
			if (c == 0)
				throw new IOException("identical to prev");
			prev = key;
			double age = i.readDouble();
			points[x] = new RoutingPoint(key, time, age);
		}
	}
	//Initializes the routing store to contain a 'mountain', centered around
	// the supplied 'k',
	//starting at 'low' and ending at 'high'
	public RoutingPointStore(Key k, double high, double low, int accuracy) {
		this(accuracy, 1);
		//We will overwrite the initEstimate:s in a second so they doesn't
		// really matter.
		//Make sure that we detect any assignment problems but setting
		// initEstimate to -1
		if (high < 0 || low < 0)
			throw new IllegalArgumentException(
				"negative high " + high + " or low " + low);
		// Center on k
		// b = keyspace / accuracy
		// c = k % c
		// Then set keys as in monadic constructor, except adding k
		BigInteger a = Key.KEYSPACE_SIZE.subtract(BigInteger.ONE);
		BigInteger b = a.divide(BigInteger.valueOf(accuracy));
		BigInteger ik = k.toBigInteger();
		BigInteger[] dr = ik.divideAndRemainder(b);
		BigInteger c = dr[1]; // remainder. positive!
		if (c.signum() < 0)
			throw new IllegalStateException("argh");
		int kOffset = dr[0].intValue();
		for (int i = 0; i < accuracy; i++) {
			points[i].key = c;
			c = c.add(b);
		}
		// Now the estimates
		double val = high;
		double valStep = (high - low) / accuracy;
		for (int i = 0; i < accuracy; i++) {
			int offset = (kOffset + i) % accuracy;
			points[offset].estimate = val;
			if (i < accuracy / 2)
				val -= valStep;
			else
				val += valStep;
		}
		//TODO: What about the ages?
	}

	public RoutingPointStore(
		FieldSet set, Unit type, int accuracy)
		throws EstimatorFormatException {
		String v = set.getString("Version");
		// Not really important, but must reset estimators
		if (v == "1") throw new EstimatorFormatException("Obsolete version 1, resetting", false);
		if (v == null || !v.equals("2"))
			throw new EstimatorFormatException("Invalid version " + v);
		String pts = set.getString("Points");
		if (pts == null)
			throw new EstimatorFormatException("no points");
		int numPoints = Fields.hexToInt(pts);
		if (numPoints != accuracy)
			throw new EstimatorFormatException("Wrong accuracy " + numPoints);
		points = new RoutingPoint[accuracy];
		for (int i = 0; i < points.length; i++) {
			RoutingPoint prev = null;
			points[i] =
				new RoutingPoint(
					set.getSet(Integer.toHexString(i)),
					prev, type);
			prev = points[i];
		}
	}

	/**
     * @param store
     */
    public RoutingPointStore(RoutingPointStore store) {
        this.knowsLowestRaw = store.knowsLowestRaw;
        this.lowestRaw = store.lowestRaw;
        this.lowestRawSync = new Object();
        this.points = (RoutingPoint[]) store.points.clone();
    }

	int search(BigInteger n) {
		try{
			aquireReadLock();	
		return search(n, points);
		}finally{
			releaseReadLock();
		}
	}
	//Should be called by anyone who are about to modify any of our
	// RoutingPoints
	//Should *not* be called *after* the modification
	protected void notifyPrePointModified(int index) {
		//Default behaviour.. dont care
	}

	//Should be called by anyone who does a modification to the contents of
	// 'points'
	//without actually modifying any of the points
	protected void notifyStructureModified() {
		//Default behaviour.. dont care
	}

	public void ensureEstimatesMax(int maxAllowedEstimate) {
		try{
			aquireWriteLock();
		for (int i = 0; i < points.length; i++) {
			if (points[i].estimate > maxAllowedEstimate) {
				notifyPrePointModified(i);
				Core.logger.log(
					this,
					"Larger than allowed estimate "
						+ points[i].estimate
						+ " detected, will use "
						+ maxAllowedEstimate
						+ " instead",
					Logger.ERROR);
				points[i].estimate = maxAllowedEstimate;
			}
		}
			knowsLowestRaw = false;
		}finally{
			releaseWriteLock();
		}
	}
	public void ensureEstimatesMin(int minAllowedEstimate) {
		try{
			aquireWriteLock();
		for (int i = 0; i < points.length; i++) {
			if (points[i].estimate < minAllowedEstimate) {
				notifyPrePointModified(i);
				Core.logger.log(
					this,
					"Smaller than allowed estimate "
						+ points[i].estimate
						+ " detected, will use "
						+ minAllowedEstimate
						+ " instead",
					Logger.ERROR);
				points[i].estimate = minAllowedEstimate;
			}
		}
			knowsLowestRaw = false;
		}finally{
			releaseWriteLock();
		}
	}

	//Returns the offset of the RoutingPoint with the biggest key that's still
	// less than n.
	//Caller is responsible for making sure that there is proper
	// synchronization
	//to prevent simultaneous access to 'points'
	private static int search(BigInteger n, RoutingPoint[] points) {
		int mid, low = 0, high = points.length - 1;

		for (;;) {
			mid = (high + low) >> 1;
			int i = points[mid].key.compareTo(n);
			if (i <= 0 && points[mid + 1].key.compareTo(n) > 0)
				return mid;
			if (i > 0)
				high = mid;
			else
				low = mid + 1;
			if (low == high)
				return low;
		}
	}

	//Sets all estimates to the supplied value
	private void setAllEstimates(double newEstimate) {
		try{
			aquireWriteLock();
		for (int i = 0; i < points.length; i++) {
			notifyPrePointModified(i);
			points[i].estimate = newEstimate;
			}
			knowsLowestRaw = false;
		}finally{
			releaseWriteLock();
		}
	}

	//Returns a RoutingPointEditor for the Point at the position 'index' in
	// the store
	//The RoutingPointEditor can be used to modify the routingPoint
	RoutingPointEditor checkout(int index) {
		return new RoutingPointEditor(index);
	}

	//Finds the position of the first key in the store, before the position
	// 'startAt', that is smaller than n
	//Returns -1 if no such key is present in the store
	protected int findFirstSmaller_reverse(
		BigInteger n,
		int startAt) {
		try{
			aquireReadLock();
		while (points[startAt].key.compareTo(n) == 1) {
			startAt--;
			if (startAt < 0)
				return -1;
		}
		return startAt;
		}finally{
			releaseReadLock();
		}
	}
	//Finds the position of the first key in the store, after the position
	// 'startAt', that is larger than n
	//Returns -1 if no such key is present in the store
	protected int findFirstLarger(BigInteger n, int startAt) {
		try{
			aquireReadLock();
		while (points[startAt].key.compareTo(n) == -1) {
			startAt++;
			if (startAt >= points.length)
				return -1;
		}
		return startAt;
		}finally{
			releaseReadLock();
		}
	}

	int getDataLength() {
		//TODO: What about the estimates and ages. Shouldn't they also be
		// included in the size?
		return 4 + points.length * (4 + Key.KEYBYTES);
	}

	//Returns the number of items in the store
	int length() {
		return points.length;
	}

	public void dumpLog() {
		try{
			aquireReadLock();
		dumpLog(points);
		}finally{
			releaseReadLock();
		}
	}

	//Dumps the content of the 'points' array
	//Caller is responsible for making sure that there is proper
	// synchronization
	//to prevent simultaneous access to 'points'
	public static void dumpLog(RoutingPoint[] points) {
		if (Core.logger.shouldLog(Logger.MINOR, RoutingPointStore.class)) {
			StringBuffer sb = new StringBuffer();
			for (int x = 0; x < points.length; x++) {
				BigInteger k = points[x].key;
				if (k == null) {
					Core.logger.log(
						RoutingPointStore.class,
						"points[" + x + "].key=null!",
						new NullPointerException(),
						Logger.ERROR);
					sb.append("null");
				} else {
					String s;
					//					try {
					//						s = k.toString(16);
					//					} catch (NullPointerException e) {
					// GRRR: REDFLAG
					// Java 1.4.2-b28 has MAJOR bugs with this!
					byte[] b = k.toByteArray();
					s = HexUtil.bytesToHex(b);
					//					}
					Core.logger.log(
						RoutingPointStore.class,
						"points[" + x + "].key=" + s,
						Logger.MINOR);
					sb.append(s);
				}
				sb.append(": ");
				double t = points[x].estimate;
				sb.append(Double.toString(t));
				if (t < 0)
					Core.logger.log(
						RoutingPointStore.class,
						"estimate["
							+ x
							+ "]="
							+ t
							+ " - NEGATIVE ("
							+ RoutingPointStore.class
							+ ')',
						Logger.ERROR);
				sb.append("\n");
			}
			Core.logger.log(
				RoutingPointStore.class,
				"Dump of " + RoutingPointStore.class +": \n" + sb.toString(),
				Logger.MINOR);
		} else {
			//Do only the sanity check if we wont log the contents of the
			// estimator
			for (int x = 0; x < points.length; x++) {
				if (points[x].key == null) {
					Core.logger.log(
						RoutingPointStore.class,
						"points[" + x + "].key=null!",
						new Exception("debug"),
						Logger.ERROR);
				}
				double t = points[x].estimate;
				if (t < 0)
					Core.logger.log(
						RoutingPointStore.class,
						"estimate["
							+ x
							+ "]="
							+ t
							+ " - NEGATIVE ("
							+ RoutingPointStore.class
							+ ")",
						Logger.ERROR);
			}
		}
	}
	void dumpHtml(PrintWriter pw, Unit type) {
		try{
			aquireReadLock();
		for (int x = 0; x < points.length; x++) {
			pw.println(
				"<tr><td>"
					+ x
					+ "</td><td>"
						+ HexUtil.biToHex(points[x].key)
					+ "</td><td>"
					+ points[x].estimate
					+ "</td><td>"
						+ type.rawToString(points[x].estimate) 
						+ "</td><td>"
					+ points[x].age
					+ "</td></tr>");
			if (points[x].estimate < 0)
					Core.logger.log(this,
					"estimate["
							+ x	+ "]="+ points[x].estimate
							+ " - NEGATIVE ("+ this
							+ ")",Logger.ERROR);
			}
		}finally{
			releaseReadLock();
		}
	}
	//Returns the value (estimate) of the highest-valued RoutingPoint
	public double highestRaw() {
		try{
			aquireReadLock();
			double highest = 0;
		for (int x = 0; x < points.length; x++) {
			highest = Math.max(highest, points[x].estimate);
		}
		return highest;
		}finally{
			releaseReadLock();
		}
	}
	//Returns the value (estimate) of the lowest-valued RoutingPoint
	public double lowestRaw() {
		synchronized(lowestRawSync){
			if(!knowsLowestRaw){
				lowestRaw = findLowestRaw();
				knowsLowestRaw = true;
			}
			return lowestRaw;
		}
	}
	private double findLowestRaw(){
		try{
			aquireReadLock();
			double lowest = Integer.MAX_VALUE;
		for (int x = 0; x < points.length; x++) {
			lowest = Math.min(lowest, points[x].estimate);
		}
		return lowest;
		}finally{
			releaseReadLock();
		}
	}
	public void print() {
		try{
			aquireReadLock();
		for (int i = 0; i < points.length; i++)
			System.out.println(
				i + ":\t" + points[i].key + "\t" + points[i].estimate);
		}finally{
			releaseReadLock();
		}
	}
	//Sorts the RoutingStore in order of its RoutingPoint keys
	protected void sortByKey() {
		try{
			aquireWriteLock();
		if (logDEBUG)
			Core.logger.log(this, "Reordering keys", Logger.DEBUG);
			dumpLog(points);
		// We cannot simply find the lowest value and then shift,
		// Becuase we are not using anything like strict gravity
		// We move the first key 1/2 the distance closer, the second 1/4 its
		// distance
		// closer, the third 1/8 its distance closer etc
		// They get reordered!
		QuickSorter.quickSort(ms);
			dumpLog(points);
		notifyStructureModified();
		}finally{
			releaseWriteLock();
		}
	}

	//Serializes the store to the supplied DataOutputStream
	public void writeTo(DataOutputStream o) throws IOException {
		try{
		    o.writeInt(SERIAL_MAGIC);
		    o.writeInt(1);
			aquireReadLock();
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"Serializing store to disk, accuracy " + points.length,
				Logger.DEBUG);
		o.writeInt(points.length);
		for (int i = 0; i < points.length; i++) {
				o.writeDouble(points[i].estimate);
			byte[] b = points[i].key.toByteArray();
			if (b.length > Key.KEYBYTES + 1)
				throw new IllegalStateException(
					"Key too long in serializing: " + b.length);
			if (b.length < Key.KEYBYTES) {
				int skip = Key.KEYBYTES - b.length;
				for (int x = 0; x < skip; x++)
					o.writeByte(0);
				o.write(b);
			} else
				o.write(b, b.length - Key.KEYBYTES, Key.KEYBYTES);
			// in case of zero padding
			o.writeDouble(points[i].age);
		}
		}finally{
			releaseReadLock();
		}
	}

	//Returns the RoutingPoint which has the highest key
	//This method exists only as a helper to clarify code which
	//accesses the last element in 'points'
	private static RoutingPoint lastPoint(RoutingPoint[] points) {
		return points[points.length - 1];
	}
	//Returns the RoutingPoint which has the lowest key
	//This method exists only as a helper to clarify code which
	//accesses the first element in 'points'
	private static RoutingPoint firstPoint(RoutingPoint[] points) {
		return points[0];
	}

	//distributes the keys evenly throughout all of the keyspace
	void distributeKeysEvenly() {
		try{
			aquireWriteLock();
		BigInteger a = Key.KEYSPACE_SIZE.subtract(BigInteger.ONE);
		BigInteger b = a.divide(BigInteger.valueOf(points.length));
		for (int i = points.length; --i >= 0; a = a.subtract(b)) {
			notifyPrePointModified(i);
			points[i].key = a;
		}
		}finally{
			releaseWriteLock();
		}
	}

	//A bunch of trivial helper methods for locking the 
	// access to the 'points'
	private final void releaseWriteLock() {
		rw.writeLock().release();
	}

	private final void aquireWriteLock() {
		try{
			rw.writeLock().acquire();
		}catch(InterruptedException e){
			throw new RuntimeException(e); //Should never happen.. make sure it is noticed
		}
	}
	
	private final void releaseReadLock() {
		rw.readLock().release();
	}

	private final void aquireReadLock() {
		try{
			rw.readLock().acquire();
		}catch(InterruptedException e){
			throw new RuntimeException(e); //Should never happen.. make sure it is noticed
		}
	}

	NeighbourRoutingPointPair findKeyBeforeAndAfter(BigInteger n) {
		try{
			aquireReadLock();
		return findKeyBeforeAndAfter(n, points);
		}finally{
			releaseReadLock();
		}
	}
	//Locates the RoutingPoints who are keywise located right before and right
	// after the supplied BigInteger
	//Handles keyspace wrap situations. The returned class contains
	// information wheter or not a wrap occurred
	//Caller is responsible for making sure that there is proper
	// synchronization
	//to prevent simultaneous access to 'points'
	static NeighbourRoutingPointPair findKeyBeforeAndAfter(
		BigInteger n,
		RoutingPoint[] points) {
		NeighbourRoutingPointPair retval = new NeighbourRoutingPointPair();
		int pos = search(n, points);

		retval.nPos = pos;
		//Is it off the keyspace, either to the left or to the right...
		if ((pos == 0 && n.compareTo(firstPoint(points).key) < 0)
			|| (pos == points.length - 1)) {
			// Off the end/beginning
			// Doesn't matter which
			retval.includesKeyspaceWrap = true;
			pos = points.length - 1;
			//Yes, beforeKey should be negative
			retval.left =
				new RoutingPoint(
					lastPoint(points).key.subtract(Key.KEYSPACE_SIZE),
					points[pos].estimate,
					points[pos].age);
			retval.right = firstPoint(points);
		} else { //else in the middle
			retval.includesKeyspaceWrap = false;
			retval.left = points[pos];
			retval.right = points[pos + 1];
		}
		return retval;
	}

	/**
	 *  
	 */
	public void clearAging() {
		try{
			aquireWriteLock();
		for (int i = 0; i < points.length; i++) {
			points[i].age = 0.0;
		}
		}finally{
			releaseWriteLock();
		}
	}

    public boolean noReports() {
        try {
            aquireReadLock();
            for(int i=0;i<points.length;i++) {
            if(points[i].age > 0.0) return false;
            }
        return true;
        } finally {
            releaseReadLock();
        }
    }
	
	/**
	 * Write to a FieldSet for transport
	 */
	public FieldSet toFieldSet() {
		FieldSet fs = new FieldSet();
		fs.put("Version", "1");
		fs.put("Points", Integer.toHexString(points.length));
		try{
			aquireReadLock();
			for (int i = 0; i < points.length; i++) {
				RoutingPoint p = points[i];
				fs.put(Integer.toHexString(i), p.toFieldSet());
			}
		}finally{
			releaseReadLock();
		}
		return fs;
	}
}
