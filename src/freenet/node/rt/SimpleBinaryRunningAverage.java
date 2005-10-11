package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.BitSet;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * Simple running average for binary (0.0/1.0) values.
 * Keeps the last 1000 values and generates a probability of
 * the next value being 1.0.
 * @author amphibian
 * Created on May 14, 2004
 */
public class SimpleBinaryRunningAverage
		implements
			RunningAverage,
			ExtraDetailRunningAverage {

    public Object clone() {
        return new SimpleBinaryRunningAverage(this);
    }
    
	static final int MAGIC = 0x4281;
	
	final int maximumSize;
	int totalZeros;
	int totalOnes;
	int index;
	long totalReported;
	final double defaultValue;
	final BitSet ba;
	boolean logDEBUG;

	private final int baSize() {
		return Math.min((int)totalReported, maximumSize);
	}
	
	public SimpleBinaryRunningAverage(int maxSize, double start) {
		maximumSize = maxSize;
		ba = new BitSet(maxSize);
		totalZeros = totalOnes = index = 0;
		totalReported = 0;
		if(start < 0.0 || start > 1.0) {
		    Core.logger.log(this, "Illegal default value: "+start+" on "+this,
		            new Exception("debug"), Logger.ERROR);
		    start = Math.max(1.0, Math.min(0.0, start));
		}
		defaultValue = start;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	}
	
	public synchronized double currentValue() {
	    if(totalZeros < 0 || totalOnes < 0) {
	        Core.logger.log(this, "Argh in currentValue(): "+this,
	                new Exception("debug"), Logger.ERROR);
	        calculateTotalOnesZeros();
	    }
		if(totalZeros == 0 && totalOnes == 0)
			return defaultValue;
		return ((double)totalOnes) / (double)(totalZeros + totalOnes);
	}
	
	public void report(double d) {
		report(convert(d));
	}
	
	public void report(long d) {
		report((double)d);
	}
	
	public synchronized void report(boolean value) {
		if(logDEBUG)
			Core.logger.log(this, "Reporting: "+value+" on "+this,
					Logger.DEBUG);
		totalReported++;
		if(totalReported > maximumSize) {
			// Remove the value, that is to be overwritten, from the calculations 
			boolean valueOverwriting = ba.get(index);
			if(valueOverwriting)
				totalOnes--;
			else
				totalZeros--;
		}
		ba.set(index, value);
		index++;
		if(index >= maximumSize) index = 0;
		if(value)
			totalOnes++;
		else
			totalZeros++;
		if(logDEBUG)
			Core.logger.log(this, "Reported: "+value+" on "+this,
					Logger.DEBUG);
	    if(totalZeros < 0 || totalOnes < 0) {
	        Core.logger.log(this, "Argh in report("+value+"): "+this,
	                new Exception("debug"), Logger.ERROR);
	        calculateTotalOnesZeros();
	    }
	}
	
	public FieldSet toFieldSet() {
		FieldSet fs = new FieldSet();
		fs.put("Implementation", "SimpleBinaryRunningAverage");
		fs.put("Version", "1");
		fs.put("Length", Integer.toHexString(baSize()));
		fs.put("Data", HexUtil.bitsToHexString(ba, baSize()));
		fs.put("Index", Integer.toHexString(index));
		return fs;
	}

	public SimpleBinaryRunningAverage(int maxSize, int fsSize, FieldSet set) 
		throws EstimatorFormatException {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if(set == null) throw new EstimatorFormatException("set null");
		String s = set.getString("Implementation");
		if(s == null || !s.equals("SimpleBinaryRunningAverage"))
			throw new EstimatorFormatException("Wrong impl: "+s, false);
		s = set.getString("Version");
		if(s == null || !s.equals("1"))
			throw new EstimatorFormatException("Wrong version: "+s);
		s = set.getString("Length");
		if(s == null) throw new EstimatorFormatException("No length");
		try {
			totalReported = Integer.parseInt(s,16);
		} catch (NumberFormatException e) {
			throw new EstimatorFormatException("Parsing length: "+s+": "+e.toString());
		}
		if(totalReported < 0) throw new EstimatorFormatException("Negative length");
		if(totalReported > maxSize) throw new EstimatorFormatException("Too high length "+totalReported);
		if(totalReported > fsSize) totalReported = fsSize;
		ba = new BitSet(maxSize);
		s = set.getString("Data");
		if (s == null) throw new EstimatorFormatException("No data");
		HexUtil.hexToBits(s, ba, (int)totalReported);
		s = set.getString("Index");
		if(s == null) throw new EstimatorFormatException("No index");
		try {
			index = Integer.parseInt(s,16);
		} catch (NumberFormatException e) {
			throw new EstimatorFormatException("Parsing index: "+s+": "+e.toString());
		}
		if(index > fsSize) index = fsSize;
		if(index < 0)
			throw new EstimatorFormatException("Invalid index: "+index);
		this.maximumSize = maxSize;
		calculateTotalOnesZeros();
		defaultValue = 0.5;
	}

	// Compute what the resulting average -would be- if (value) were
	// reported, WITHOUT ACTUALLY UPDATING THE STATE OF THIS OBJECT.
	public double valueIfReported(boolean value) {
		int to, tz;
		synchronized(this) {
			to = totalOnes;
			tz = totalZeros;
			if( (totalReported+1) > maximumSize ) {
				// account for the bit that would be dropped
				boolean valueOverwriting = ba.get(index);
				if(valueOverwriting)
					to--;
				else
					tz--;
			}
		}
		if(value)
			to++;
		else
			tz++;
		return ((double)to) / (double)(tz + to);
	}
	
	public String extraToString() {
		return Integer.toString(totalZeros) + " 0s, "+
			totalOnes + " 1s, "+(totalZeros+totalOnes)+
			" total";
	}
	
	public String toString() {
		return super.toString() + " ("+extraToString()+")"+
			", init="+defaultValue+", index="+index+", totalReported="+
			totalReported;
	}
	
	public synchronized void writeDataTo(DataOutputStream out) throws IOException {
		out.writeInt(MAGIC); // magic for this class
		out.writeInt(1);
		out.writeInt(baSize());
		out.writeInt(index);
		out.writeLong(totalReported);
		out.write(HexUtil.bitsToBytes(ba, baSize()));
	}
	
	public SimpleBinaryRunningAverage(int maxSize, DataInputStream dis) throws IOException {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		int magic = dis.readInt();
		if(magic != MAGIC) throw new IOException("Invalid magic "+magic+" should be "+MAGIC+" - format change?");
		int ver = dis.readInt();
		if(ver != 1)
		    throw new IOException("Invalid version: "+ver);
		int size = dis.readInt();
		if(size < 0) throw new IOException("Invalid size "+size);
		if(size > maxSize) throw new IOException("Too big "+size);
		index = dis.readInt();
		if(index > size) throw new IOException("Invalid index");
		totalReported = dis.readLong();
		if(totalReported < 0) throw new IOException("Negative totalReported");
		if(totalReported < size) throw new IOException("Invalid totalReported: "+totalReported+", size: "+size);
		ba = new BitSet(maxSize);
		maximumSize = maxSize;
		byte[] b = new byte[HexUtil.countBytesForBits(size)];
		dis.read(b);
		HexUtil.bytesToBits(b, ba, size);
		calculateTotalOnesZeros();
		defaultValue = 0.5; // not used
		if(logDEBUG)
			Core.logger.log(this, "Created: "+this+" from "+dis,
					Logger.DEBUG);
	}

    public SimpleBinaryRunningAverage(SimpleBinaryRunningAverage a) {
        this.ba = (BitSet) a.ba.clone();
        this.defaultValue = a.defaultValue;
        this.index = a.index;
        this.maximumSize = a.maximumSize;
        this.totalOnes = a.totalOnes;
        this.totalReported = a.totalReported;
        this.totalZeros = a.totalZeros;
    }

    private void calculateTotalOnesZeros() {
		StringBuffer sb = new StringBuffer();
		int tones = 0;
		int tzeros = 0;
		for(int i=0;i<baSize();i++) {
			if(ba.get(i)) {
				tones++;
				sb.append('1');
			} else {
				tzeros++;
				sb.append('0');
			}
		}
		totalOnes = tones;
		totalZeros = tzeros;
		if(Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, "Returning: "+totalZeros+" zeros, "+totalOnes+
					" ones, binary: "+sb.toString(), Logger.DEBUG);
	}

	protected String checkOnesZeros() {
	    StringBuffer sb = new StringBuffer();
		int tones = 0;
		int tzeros = 0;
		for(int i=0;i<baSize();i++) {
			if(ba.get(i)) {
				tones++;
				sb.append('1');
			} else {
				tzeros++;
				sb.append('0');
			}
		}
		return "bits: " + sb.toString() + "counted " + tones + " ones and " + tzeros + " zeros"; 
	}
	
	public int getDataLength() {
		return 4 + 4 + 4 + 4 + 8 + HexUtil.countBytesForBits(baSize());
	}

	public boolean convert(double d) {
		if(d > 1.0 || d < 0.0) throw new IllegalArgumentException("invalid : "+d);
		if(d > 0.9) return true;
		if(d < 0.1) return false;
		throw new IllegalArgumentException("not one or other extreme!");
	}
	
	public double valueIfReported(double d) {
		return valueIfReported(convert(d));
	}

	/**
	 * @return a RunningAverageFactory for this class.
	 * @param maxSize the maximum number of reports to be kept
	 * by the created RunningAverage.
	 * @param fsSize the maximum number of reports to be accepted
	 * from serialization.
	 */
	public static RunningAverageFactory factory(int maxSize, int fsSize) {
		return new SimpleBinaryRunningAverageFactory(maxSize, fsSize);
	}

	public static class SimpleBinaryRunningAverageFactory
			implements
				RunningAverageFactory {

		int maxSize;
		int fsSize;
		
		public SimpleBinaryRunningAverageFactory(int maxSize, int fsSize) {
			this.maxSize = maxSize;
			this.fsSize = fsSize;
		}
		
		public RunningAverage create(double start) {
			return new SimpleBinaryRunningAverage(maxSize, start);
		}

		public RunningAverage create(DataInputStream dis) throws IOException {
			return new SimpleBinaryRunningAverage(maxSize, dis);
		}
		
		public RunningAverage create(FieldSet set)
				throws EstimatorFormatException {
			return new SimpleBinaryRunningAverage(maxSize, fsSize, set);
		}
	}

    public long countReports() {
        return totalReported;
    }
}
