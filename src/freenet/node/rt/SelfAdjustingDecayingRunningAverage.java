package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.Logger;

/**
 * @author Martin Stone Davis
 *
 * Specialized DecayingRunningAverage, useful for binary variables.
 * Avoids problem where use of a ConstantDecayingRunningAverage for binary variables would lead to inaccurate estimates.  
 *  
 * Also counts total number of hits over 0.9, under 0.1, and between the two.
 */
public class SelfAdjustingDecayingRunningAverage
	extends DecayingRunningAverage implements ExtraDetailRunningAverage {

    public Object clone() {
        return new SelfAdjustingDecayingRunningAverage(this);
    }
    
	int highHits = 0;
	int lowHits = 0;
	int midHits = 0;

	public String extraToString() {
		StringBuffer sb = new StringBuffer();
		if(highHits > 0) {
			sb.append(">0.9: ");
			sb.append(highHits);
			sb.append(" ");
		}
		if(lowHits > 0) {
			sb.append("<0.1: ");
			sb.append(lowHits);
			sb.append(" ");
		}
		if(midHits > 0) {
			sb.append("[0.1,0.9]: ");
			sb.append(midHits);
			sb.append(" ");
		}
		return sb.toString();
	}

	/**
	 * @param startVal
	 * @param decayFactor
	 */
	public SelfAdjustingDecayingRunningAverage(
		double startVal,
		double decayFactor) {
		super(startVal, decayFactor);
	}

	/**
	 * @param dis
	 * @param decayFactor
	 * @throws IOException
	 */
	public SelfAdjustingDecayingRunningAverage(
		DataInputStream dis,
		double decayFactor)
		throws IOException {
		super(dis, decayFactor);
		if (!isValueValid(currentValue)) {
			Core.logger.log(
				this,
				"reset " + currentValue,
				new Exception("debug"),
				Logger.NORMAL);
			currentValue = Core.hopTime(1, 0);
		}
	}

    public SelfAdjustingDecayingRunningAverage(SelfAdjustingDecayingRunningAverage a) {
        super(a);
        this.highHits = a.highHits;
        this.lowHits = a.lowHits;
        this.midHits = a.midHits;
    }

	boolean isValueValid(double d) {
		return !Double.isInfinite(d) && !Double.isNaN(d) && d >= 0 && d <= 1;
	}

	// 0.001 to 0.999
	// Because if it's actually 1.0 or 0.0... bad things happen.
	// Or even if it's too close to them.

	boolean isStartValuePlausible(double d) {
		return d > 0.001 && d < 0.999;
	}

	double getPlausibleStartValue(double d) {
		if (d > 0.999) {
			return 0.99;
		}
		if (d < 0.001) {
			return 0.01;
		}

		return 0.5;
	}

	public synchronized void report(double d) {
		if (!isValueValid(d)) {
			double plausible = getPlausibleStartValue(d);
			Core.logger.log(
				this,
				"Invalid report: " + d + " Using " + plausible + "instead.",
				new Exception("debug"),
				Logger.ERROR);
			d = plausible;
		}

		if(d < 0.1) lowHits++;
		else if(d > 0.9) highHits++;
		else midHits++;
		double adjustedDecayFactor =
			Math.max(
				2 * Double.MIN_VALUE / decayFactor,
				decayFactor * Math.min(currentValue, 1 - currentValue));

		currentValue =
			currentValue * (1 - adjustedDecayFactor) + d * adjustedDecayFactor;
	}

	public synchronized double valueIfReported(double d) {
	    if (!isValueValid(d)) {
	        double plausible = getPlausibleStartValue(d);
	        Core.logger.log(
	                this,
	                "Invalid report: " + d + " Using " + plausible + "instead.",
	                new Exception("debug"),
	                Logger.ERROR);
	        d = plausible;
	    }

	    double adjustedDecayFactor =
	        Math.max(
	                2 * Double.MIN_VALUE / decayFactor,
	                decayFactor * Math.min(currentValue, 1 - currentValue));

	    return
	        currentValue * (1 - adjustedDecayFactor) + d * adjustedDecayFactor;
	}
	
	String getImplementation() {
		return "SelfAdjustingDecayingRunningAverage";
	}

	String getVersion() {
		return "1";
	}

	public static RunningAverageFactory factory() {
		return new SelfAdjustingDecayingRunningAverageFactory();
	}

	public static class SelfAdjustingDecayingRunningAverageFactory
		extends DecayingRunningAverageFactory {
		double defaultFactor = 0.2;

		public RunningAverage create(double start) {
			return new SelfAdjustingDecayingRunningAverage(
				start,
				defaultFactor);
		}

		public RunningAverage create(DataInputStream dis) throws IOException {
			return new SelfAdjustingDecayingRunningAverage(dis, defaultFactor);
		}

		String getImplementation() {
			return "SelfAdjustingDecayingRunningAverage";
		}

		String getVersion() {
			return "1";
		}

		/* (non-Javadoc)
		 * @see freenet.node.rt.RunningAverageFactory#create(freenet.FieldSet)
		 */
		public RunningAverage create(FieldSet set)
			throws EstimatorFormatException {
			return new SelfAdjustingDecayingRunningAverage(
				fromFieldSet(set),
				defaultFactor);
		}
	}
}
