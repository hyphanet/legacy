package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigDecimal;

import freenet.Core;
import freenet.FieldSet;
import freenet.Key;
import freenet.support.Logger;


/**
 * Factory for SlidingBucketsKeyspaceEstimator
 * @author amphibian
 */
public class SlidingBucketsKeyspaceEstimatorFactory implements
        KeyspaceEstimatorFactory {

    final BigDecimal movementFactor;
    final double movementFactorDouble;
    final int accuracy;
    final RunningAverageFactory rafProbability; // for probabilities
    final RunningAverageFactory rafTime; // for everything else
    final RunningAverageFactory rafTransferRate; // for transfer rates
    final boolean doSmoothing;
    /** If true, use DoubleFastSBKE instead of SBKE; it uses doubles instead of
     * BigInteger's to represent keys. */
    final boolean doFullPrecision;
    
    /**
     * Create a SlidingBucketsKeyspaceEstimatorFactory
     */
    public SlidingBucketsKeyspaceEstimatorFactory(RunningAverageFactory rafSmooth,
            RunningAverageFactory rafProbability, RunningAverageFactory rafTransferRate,
            int accuracy, double movementFactor, boolean doSmoothing, boolean doDoubles) {
        this.rafTime = rafSmooth;
        this.rafProbability = rafProbability;
        this.rafTransferRate = rafTransferRate;
        this.accuracy = accuracy;
        movementFactorDouble = movementFactor;
        this.movementFactor = new BigDecimal(Double.toString(movementFactor));
        this.doSmoothing = doSmoothing;
        this.doFullPrecision = !doDoubles;
    }

    public KeyspaceEstimator createZeroSmooth(String name) {
        return doFullPrecision ?
                (KeyspaceEstimator)new SlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactor, 0.0, 
                        KeyspaceEstimator.TIME, name, doSmoothing) :
                (KeyspaceEstimator)new FastSlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactorDouble, 0.0, 
                        KeyspaceEstimator.TIME, name, doSmoothing);
    }

    public KeyspaceEstimator createTransfer(Key k, double slowest,
            double fastest, String name) {
        return createTransfer((slowest+fastest)/2, name);
    }

    public KeyspaceEstimator createTransfer(double t, String name) {
        return doFullPrecision ? 
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, movementFactor, t,
                        KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, movementFactorDouble, t,
                        KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing);
    }

    /**
     * Create an SBKE for smooth values initialized to the given value.
     */
    public KeyspaceEstimator createInitTransfer(double initRate, String name) {
        return doFullPrecision ? 
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, 
                        movementFactor, initRate, KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, 
                        movementFactorDouble, initRate, KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing);
        
    }

    public KeyspaceEstimator createTransferRate(DataInputStream dis, String name) throws IOException {
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, movementFactor, dis, 
                        KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, movementFactorDouble, dis, 
                        KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing);
    }

    /**
     * Create a probability estimator starting at a given value.
     */
    public KeyspaceEstimator createProbability(double p, String name) {
        if(p < 0.0 || p > 1.0) {
            Core.logger.log(this, "Invalid probability: "+p,
                    new Exception("error"), Logger.ERROR);
            p = Math.min(Math.max(p, 1.0), 0.0);
        }
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafProbability, accuracy, movementFactor, p,
                        KeyspaceEstimator.PROBABILITY, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafProbability, accuracy, movementFactorDouble, p,
                        KeyspaceEstimator.PROBABILITY, name, doSmoothing);
    }

    public KeyspaceEstimator createTime(long t, String name) {
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactor, t,
                        KeyspaceEstimator.TIME, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactorDouble, t,
                        KeyspaceEstimator.TIME, name, doSmoothing);
    }

    public KeyspaceEstimator createTime(double t, String name) {
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactor, t,
                        KeyspaceEstimator.TIME, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactorDouble, t,
                        KeyspaceEstimator.TIME, name, doSmoothing);
    }
    
    public KeyspaceEstimator createTime(DataInputStream dis, String name) throws IOException {
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactor, dis, 
                        KeyspaceEstimator.TIME, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactorDouble, dis, 
                        KeyspaceEstimator.TIME, name, doSmoothing);
    }

    /**
     * Create a probability estimator centered on a specific key
     */
    public KeyspaceEstimator createProbability(Key k, double lowest,
            double highest, String name) {
        return createProbability((lowest+highest)/2, name);
    }

    public KeyspaceEstimator createProbability(DataInputStream dis, String name) throws IOException {
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafProbability, accuracy, 
                        movementFactor, dis, KeyspaceEstimator.PROBABILITY, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafProbability, accuracy, 
                        movementFactorDouble, dis, KeyspaceEstimator.PROBABILITY, name, doSmoothing);
    }

    public KeyspaceEstimator createTime(Key k, long lowest, long highest, String name) {
        return createTime((lowest+highest)/2, name);
    }

    public KeyspaceEstimator createTime(Key k, double lowest, double highest, String name) {
        return createTime((lowest+highest)/2, name);
    }

    public KeyspaceEstimator createTime(FieldSet set, String name) throws EstimatorFormatException {
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafTime, movementFactor, 
                        accuracy, set, KeyspaceEstimator.TIME, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafTime, movementFactorDouble, 
                        accuracy, set, KeyspaceEstimator.TIME, name, doSmoothing);
    }

    public KeyspaceEstimator createTransferRate(FieldSet set, String name) throws EstimatorFormatException {
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafTime, movementFactor, 
                        accuracy, set, KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafTime, movementFactorDouble, 
                        accuracy, set, KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing);
    }

    public KeyspaceEstimator createProbability(FieldSet set, String name)
            throws EstimatorFormatException {
        return doFullPrecision ?
                (KeyspaceEstimator) new SlidingBucketsKeyspaceEstimator(rafProbability, 
                        movementFactor, accuracy, set, KeyspaceEstimator.PROBABILITY, name, doSmoothing) :
                (KeyspaceEstimator) new FastSlidingBucketsKeyspaceEstimator(rafProbability, 
                        movementFactorDouble, accuracy, set, KeyspaceEstimator.PROBABILITY, name, doSmoothing);
    }

}
