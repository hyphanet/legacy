package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;

import freenet.Key;
import freenet.support.Unit;
import freenet.support.Unit.noConvertClip;
import freenet.support.Unit.probPrint;
/**
 * @author Martin Stone Davis
 *  
 */
public class SelfAdjustingDecayingKeyspaceEstimator
	extends DecayingKeyspaceEstimator {

	static final double MIN_PROBABILITY = 0.01;
	static final double MAX_PROBABILITY = 0.99;

	public static final Unit PROBABILITY = new noConvertClip(0.0, 1.0, MIN_PROBABILITY, MAX_PROBABILITY, new probPrint());

	SelfAdjustingDecayingKeyspaceEstimator(
			RoutingPointStore rps, String name) {
		super(rps, KeyspaceEstimator.PROBABILITY, name);
	}
	
	/**
	 * @param i
	 * @param maxAllowedTime
	 * @param minAllowedTime
	 * @throws IOException
	 */
	public SelfAdjustingDecayingKeyspaceEstimator(DataInputStream i, String name, Unit type)
		throws IOException {
		super(i, type, name);
	}

	/**
	 * @param initTime
	 * @param accuracy
	 */
	public SelfAdjustingDecayingKeyspaceEstimator(double initTime, int accuracy, String name) {
		super(initTime, accuracy, KeyspaceEstimator.PROBABILITY, name);
	}

	/**
	 * @param k
	 * @param high
	 * @param low
	 * @param accuracy
	 */
	public SelfAdjustingDecayingKeyspaceEstimator(
		Key k,
		double high,
		double low,
		int accuracy, String name) {
		super(k, high, low, accuracy, KeyspaceEstimator.PROBABILITY, name);
	}

	/**
	 * @param accuracy
	 */
	public SelfAdjustingDecayingKeyspaceEstimator(int accuracy, String name) {
		super(accuracy, KeyspaceEstimator.PROBABILITY, name);
	}

	double ADJUST_DECAY_FACTOR_KLUDGE(double p) {
		// KLUDGE: (a kludge within a kludge) If currentValue is implausible,
		// we should log it and change it.
		// KLUDGE: However, this kludge will prevent 0.0 or 1.0 from being
		// hopelessly immobile.
		// FIXME: However, it will also cause SelfAdjusting... to not work as
		// well as it could for estimates far below 0.01 or far above 0.99.
		return Math.max(MIN_PROBABILITY, Math.min(p, 1 - p));
	}
}
