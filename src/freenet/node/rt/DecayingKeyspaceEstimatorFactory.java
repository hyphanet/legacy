package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;

import freenet.FieldSet;
import freenet.Key;
import freenet.support.Unit;

/** KeyspaceEstimatorFactory using both DecayingKeyspaceEstimator's
 * and SelfAdjustingDecayingKeyspaceEstimator's.
 */
public class DecayingKeyspaceEstimatorFactory
	implements KeyspaceEstimatorFactory {
	int accuracy;
	public DecayingKeyspaceEstimatorFactory(int accuracy) {
		this.accuracy = accuracy;
	}

	public KeyspaceEstimator createZeroSmooth(String name) {
		return new DecayingKeyspaceEstimator(accuracy, KeyspaceEstimator.TIME, name);
	}

	public KeyspaceEstimator createTransfer(Key k, double slowest, double fastest, String name) {
			return new DecayingKeyspaceEstimator(
				k,
				KeyspaceEstimator.TRANSFER_RATE.toRaw(slowest),
				KeyspaceEstimator.TRANSFER_RATE.toRaw(fastest),
				accuracy, KeyspaceEstimator.TRANSFER_RATE, name);
		}

	public KeyspaceEstimator createInitTransfer(double initRate, String name) {
		return new DecayingKeyspaceEstimator(
		        KeyspaceEstimator.TRANSFER_RATE.toRaw(initRate),
		        accuracy, KeyspaceEstimator.TRANSFER_RATE, name);
	}

    public KeyspaceEstimator createTransferRate(DataInputStream dis, String name) throws IOException {
		return new DecayingKeyspaceEstimator(dis, KeyspaceEstimator.TRANSFER_RATE, name);
    }
    
    public KeyspaceEstimator createTransferRate(FieldSet set, String name) throws EstimatorFormatException {
        return create(set, false, KeyspaceEstimator.TRANSFER_RATE, name);
    }

	public KeyspaceEstimator createProbability(Key k,double worst, double best, String name) {
		return new SelfAdjustingDecayingKeyspaceEstimator(k, worst, best, accuracy, name);
	}

	/*
	 * @see freenet.node.rt.KeyspaceEstimatorFactory#createProbability(double)
	 */
	public KeyspaceEstimator createProbability(double d, String name) {
		return new SelfAdjustingDecayingKeyspaceEstimator(
		        KeyspaceEstimator.PROBABILITY.toRaw(d),	accuracy, name);
	}

	public KeyspaceEstimator createProbability(DataInputStream dis, String name)
		throws IOException {
			return new SelfAdjustingDecayingKeyspaceEstimator(dis, name, KeyspaceEstimator.PROBABILITY);
	}

	public KeyspaceEstimator createProbability(FieldSet set, String name) 
		throws EstimatorFormatException {
			return create(set, true, SelfAdjustingDecayingKeyspaceEstimator.PROBABILITY, name);
	}
		

	
	public KeyspaceEstimator createTime(Key k, long worst, long best, String name) {
		return new DecayingKeyspaceEstimator(k,
			KeyspaceEstimator.TIME.toRaw(worst),
			KeyspaceEstimator.TIME.toRaw(best),
			accuracy, KeyspaceEstimator.TIME, name);
	}

	/*
	 * @see freenet.node.rt.KeyspaceEstimatorFactory#createTime(long)
	 */
	public KeyspaceEstimator createTime(long t, String name) {
		return new DecayingKeyspaceEstimator(KeyspaceEstimator.TIME.toRaw(t), accuracy,
		        KeyspaceEstimator.TIME, name);
	}

	public KeyspaceEstimator createTime(DataInputStream dis, String name)
		throws IOException {
		return new DecayingKeyspaceEstimator(dis, KeyspaceEstimator.TIME, name);
	}
    
    public KeyspaceEstimator createTime(FieldSet set, String name) throws EstimatorFormatException {
        return create(set, false, KeyspaceEstimator.TIME, name);
    }

    /*
	 * @see freenet.node.rt.KeyspaceEstimatorFactory#create(freenet.FieldSet)
	 */
	private KeyspaceEstimator create(
		FieldSet set,
		boolean useSelfAdjusting,
		Unit type, String name)
		throws EstimatorFormatException {
		if (set == null)
			throw new EstimatorFormatException("null DecayingKeyspaceEstimator fieldset");
		String impl = set.getString("Implementation");
		if (impl == null)
			throw new EstimatorFormatException("no Implementation in RunningAverage");
		if (!impl.equals("DecayingKeyspaceEstimator"))
			throw new EstimatorFormatException(
				"unknown implementation " + impl);
		String v = set.getString("Version");
		if (v == null || !v.equals("1"))
			throw new EstimatorFormatException("Invalid version " + v);
		RoutingPointStore rps =
			DecayingKeyspaceEstimator.keepHistory
				? new HistoryKeepingRoutingPointStore(set.getSet("Store"), type, accuracy)
				: new RoutingPointStore(set.getSet("Store"), type, accuracy);
		return useSelfAdjusting ? 
				new SelfAdjustingDecayingKeyspaceEstimator(rps, name) :
				new DecayingKeyspaceEstimator(rps, type, name);
	}

//	public KeyspaceEstimator createTime(
//		Key k,
//		double worst,
//		double best, String name) {
//		return new DecayingKeyspaceEstimator(
//			k,
//			DecayingKeyspaceEstimator.convertTime(worst),
//			DecayingKeyspaceEstimator.convertTime(best),
//			accuracy, KeyspaceEstimator.TIME, name);
//	}
//
}