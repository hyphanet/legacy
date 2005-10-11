package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.Logger;

/**
 * @author Martin Stone Davis
 *
 */
public class ConstantDecayingRunningAverage extends DecayingRunningAverage {

    public Object clone() {
        return new ConstantDecayingRunningAverage(this);
    }
    
    double minValue;
    double maxValue;
    
	/**
	 * @param startVal
	 * @param decayFactor
	 */
	public ConstantDecayingRunningAverage(
		double startVal,
		double decayFactor,
		double min,
		double max) {
		super(startVal, decayFactor);
		minValue = min;
		maxValue = max;
		if(!(isValueValid(currentValue) && isStartValuePlausible(currentValue)))
		    throw new IllegalArgumentException("Start value not plausible");
	}

	/**
	 * @param dis
	 * @param decayFactor
	 * @throws IOException
	 */
	public ConstantDecayingRunningAverage(
		DataInputStream dis,
		double decayFactor,
		double min,
		double max)
		throws IOException {
		super(dis, decayFactor);
		minValue = min;
		maxValue = max;
		if(!(isValueValid(currentValue) && isStartValuePlausible(currentValue)))
		    throw new IllegalArgumentException("Start value not plausible");
	}

	/**
     * @param average
     */
    public ConstantDecayingRunningAverage(ConstantDecayingRunningAverage average) {
        super(average);
        this.minValue = average.minValue;
        this.maxValue = average.maxValue;
    }

    boolean isValueValid(double d) {
		return !Double.isInfinite(d) && !Double.isNaN(d);
	}

	boolean isStartValuePlausible(double d) {
		return d >= minValue && d <= maxValue;
	}

	double getPlausibleStartValue(double d) {
		return Core.hopTime(1, 0);
	}

	public synchronized void report(double d) {
		if (!isValueValid(d) || !isStartValuePlausible(d)) {
			double validAndPlausible = getPlausibleStartValue(d);
			Core.logger.log(
				this,
				"Invalid/Implausible report: "
					+ d
					+ " Using "
					+ validAndPlausible
					+ "instead.",
				new Exception("debug"),
				Logger.ERROR);
			d = validAndPlausible;
		}
		reports++;
		currentValue = currentValue * (1 - decayFactor) + d * decayFactor;
	}

	public double valueIfReported(double d) {
	    if (!isValueValid(d) || !isStartValuePlausible(d)) {
	        double validAndPlausible = getPlausibleStartValue(d);
	        Core.logger.log(
	                this,
	                "Invalid/Implausible report: "
	                + d
	                + " Using "
	                + validAndPlausible
	                + "instead.",
	                new Exception("debug"),
	                Logger.ERROR);
	        d = validAndPlausible;
	    }

	    return currentValue * (1 - decayFactor) + d * decayFactor;
	}
	
	String getImplementation() {
		return "ConstantDecayingRunningAverage";
	}

	String getVersion() {
		return "2";
	}

	public static RunningAverageFactory factory(double decayFactor,
	        double min, double max) {
		return new ConstantDecayingRunningAverageFactory(decayFactor, min, max);
	}

	static class ConstantDecayingRunningAverageFactory
		extends DecayingRunningAverageFactory {
		final double defaultFactor;
		final double max;
		final double min;

		ConstantDecayingRunningAverageFactory(double df, double min,
		        double max) {
		    defaultFactor = df;
		    this.min = min;
		    this.max = max;
		}
		
		public RunningAverage create(double start) {
			return new ConstantDecayingRunningAverage(start, defaultFactor, min, max);
		}

		public RunningAverage create(DataInputStream dis) throws IOException {
			return new ConstantDecayingRunningAverage(dis, defaultFactor, min, max);
		}

		String getImplementation() {
			return "ConstantDecayingRunningAverage";
		}

		String getVersion() {
			return "1";
		}

		public RunningAverage create(FieldSet set)
			throws EstimatorFormatException {
			return new ConstantDecayingRunningAverage(
				fromFieldSet(set),
				defaultFactor,
				min,
				max);
		}
	}
}
