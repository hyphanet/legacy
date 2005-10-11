package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigDecimal;

import freenet.FieldSet;
import freenet.Key;


/**
 * Factory for OptimizingSlidingBucketsKeyspaceEstimator
 * @author Iakin
 */
public class OptimizingSlidingBucketsKeyspaceEstimatorFactory implements
        KeyspaceEstimatorFactory {

    final BigDecimal movementFactor;
    final int accuracy;
    final RunningAverageFactory rafProbability; // for probabilities
    final RunningAverageFactory rafTime; // for everything else
    final RunningAverageFactory rafTransferRate; // for transfer rates
    final boolean doSmoothing;
    
    /**
     * Create a OptimizingSlidingBucketsKeyspaceEstimatorFactory
     */
    public OptimizingSlidingBucketsKeyspaceEstimatorFactory(RunningAverageFactory rafSmooth,
            RunningAverageFactory rafProbability, RunningAverageFactory rafTransferRate,
            int accuracy, double movementFactor, boolean doSmoothing) {
        this.rafTime = rafSmooth;
        this.rafProbability = rafProbability;
        this.rafTransferRate = rafTransferRate;
        this.accuracy = accuracy;
        this.movementFactor = new BigDecimal(Double.toString(movementFactor));
        this.doSmoothing = doSmoothing;
    }

    public KeyspaceEstimator createZeroSmooth(String name) {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactor, 0.0, 
                KeyspaceEstimator.TIME, name, doSmoothing);
    }

    //FIXME: should this just be creating a completely average estimator?
    public KeyspaceEstimator createTransfer(Key k, double slowest, 
            double fastest, String name) {
        return createTransfer((slowest+fastest)/2, name);
    }
    
    //FIXME: same as createInitialTransfer
    private KeyspaceEstimator createTransfer(double t, String name) {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, 
                movementFactor, t, KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing);
    }

    /**
     * Create an SBKE for smooth values initialized to the given value.
     */
    public KeyspaceEstimator createInitTransfer(double initRate, String name) {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, 
                movementFactor, initRate, KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing);
    }

    public KeyspaceEstimator createTransferRate(DataInputStream dis, String name) throws IOException {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafTransferRate, accuracy, movementFactor, dis, 
                KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing);
    }

    public KeyspaceEstimator createTransferRate(FieldSet set, String name) throws EstimatorFormatException {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafTime, movementFactor, 
                accuracy, set, KeyspaceEstimator.TRANSFER_RATE, name, doSmoothing);
    }


    /**
     * Create a probability estimator starting at a given value.
     */
    public KeyspaceEstimator createProbability(double p, String name) {
        return new OptimizingSlidingBucketsKeyspaceEstimator(
                rafProbability, accuracy, movementFactor, p, KeyspaceEstimator.PROBABILITY, name, doSmoothing);
    }

    /**
     * Create a probability estimator centered on a specific key
     */
    //FIXME: argument k is unused
    public KeyspaceEstimator createProbability(Key k, double lowest,
            double highest, String name) {
        return createProbability((lowest+highest)/2, name);
    }

    public KeyspaceEstimator createProbability(DataInputStream dis, String name) throws IOException {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafProbability, accuracy, 
                movementFactor, dis, KeyspaceEstimator.PROBABILITY, name, doSmoothing);
    }

    public KeyspaceEstimator createProbability(FieldSet set, String name)
    throws EstimatorFormatException {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafProbability, 
                movementFactor, accuracy, set, KeyspaceEstimator.PROBABILITY, name, doSmoothing);
    }

    public KeyspaceEstimator createTime(Key k, long lowest, long highest, String name) {
        return createTime((lowest+highest)/2, name);
    }

    public KeyspaceEstimator createTime(long t, String name) {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactor, t,
                KeyspaceEstimator.TIME, name, doSmoothing);
    }

    public KeyspaceEstimator createTime(DataInputStream dis, String name) throws IOException {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafTime, accuracy, movementFactor, dis, 
                KeyspaceEstimator.TIME, name, doSmoothing);
    }

    public KeyspaceEstimator createTime(FieldSet set, String name) throws EstimatorFormatException {
        return new OptimizingSlidingBucketsKeyspaceEstimator(rafTime, movementFactor, 
                accuracy, set, KeyspaceEstimator.TIME, name, doSmoothing);
    }
}
