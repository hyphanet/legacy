package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.Logger;

public abstract class DecayingRunningAverage implements RunningAverage {
	double currentValue;
	double decayFactor;
	long reports;
	static final int SERIAL_MAGIC = 0x3b8ea169;
	
	public abstract Object clone();

	/*
	 * Assumes value fed in is at least Valid.
	 */
	private final double convertToPlausibleStartValue(double start) {
		if (!isStartValuePlausible(start)) {
			double plausible = getPlausibleStartValue(start);
			Core.logger.log(
				this,
				"Implausible startValue "
					+ start
					+ ". Resetting to "
					+ plausible,
				new Exception("debug"),
				Logger.NORMAL);
			return plausible;
		} else {
			return start;
		}
	}

	public DecayingRunningAverage(double startValue, double decayFactor) {
		if (!isValueValid(startValue)) {
			throw new IllegalArgumentException(
				"Invalid startValue: " + startValue);
		}
		this.currentValue = convertToPlausibleStartValue(startValue);
		this.decayFactor = decayFactor;
		reports = 0;
	}

	public DecayingRunningAverage(DataInputStream dis, double decayFactor)
		throws IOException {
	    int magic = dis.readInt();
	    if(magic != SERIAL_MAGIC)
	        throw new IOException("Invalid magic - format change?");
	    int ver = dis.readInt();
	    if(ver != 1)
	        throw new IOException("Invalid version "+ver+" - should be 1");
		double read = dis.readDouble();
		if (!isValueValid(read)) {
			throw new IOException("Invalid value serializing in: " + read);
		}
		this.currentValue = convertToPlausibleStartValue(read);
		this.reports = dis.readLong();
		if(reports < 0) throw new IOException("Invalid value for reports: "+reports);
		this.decayFactor = decayFactor;
	}

    public DecayingRunningAverage(DecayingRunningAverage a) {
        this.currentValue = a.currentValue;
        this.decayFactor = a.decayFactor;
        this.reports = a.reports;
    }

    public final double currentValue() {
		return currentValue;
	}

	abstract boolean isValueValid(double d);
	abstract boolean isStartValuePlausible(double d);
	abstract double getPlausibleStartValue(double d);

	public abstract void report(double d);

	public synchronized void report(long d) {
		report((double) d);
	}

	public synchronized void report(boolean b) {
		report(b ? 1.0 : 0.0);
	}

	// Only write the value, not the decay factor
	public void writeDataTo(DataOutputStream out) throws IOException {
	    out.writeInt(SERIAL_MAGIC);
	    out.writeInt(1);
		double cv;
		synchronized (this) {
			cv = currentValue;
		}
		out.writeDouble(cv);
		out.writeLong(reports);
	}

	public int getDataLength() {
		return DOUBLE_SIZE + 4 + 4 + 8;
	}

	abstract String getImplementation();
	abstract String getVersion();

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.rt.RunningAverage#toFieldSet()
	 */
	public FieldSet toFieldSet() {
		FieldSet fs = new FieldSet();
		fs.put("Implementation", getImplementation());
		fs.put("Version", getVersion());
		fs.put("CurrentValue", Double.toString(currentValue));
		return fs;
	}

	public static abstract class DecayingRunningAverageFactory
		implements RunningAverageFactory {
		/*
		 * (non-Javadoc)
		 * 
		 * @see freenet.node.rt.RunningAverageFactory#create(freenet.FieldSet)
		 */

		abstract String getImplementation();
		abstract String getVersion();

		protected double fromFieldSet(FieldSet set)
			throws EstimatorFormatException {
			if (set == null)
				throw new EstimatorFormatException("null set passed to DecayingRunningAverage");
			String impl = set.getString("Implementation");
			if (impl == null)
				throw new EstimatorFormatException("no Implementation in DecayingRunningAverage");

			if (!impl.equals(getImplementation()))
				throw new EstimatorFormatException(
					"unknown implementation " + impl);
			String v = set.getString("Version");
			if (v == null || !v.equals(getVersion()))
				throw new EstimatorFormatException("Invalid version " + v);
			String val = set.getString("CurrentValue");
			if (val == null)
				throw new EstimatorFormatException("no CurrentValue");
			return Double.parseDouble(val);
		}
	}

    public long countReports() {
        return reports;
    }
}
