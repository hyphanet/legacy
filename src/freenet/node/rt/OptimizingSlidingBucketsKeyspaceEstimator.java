/*
 * Created on Jul 11, 2004
 */
package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

import net.i2p.util.NativeBigInteger;

import freenet.FieldSet;
import freenet.support.Unit;

/**
 * @author Iakin
 * A specialization of SlidingBucketsKeyspaceEstimator that tries to make use of NativeBigInteger's
 * native-optimizations 
 */
public class OptimizingSlidingBucketsKeyspaceEstimator extends SlidingBucketsKeyspaceEstimator {

	public OptimizingSlidingBucketsKeyspaceEstimator(RunningAverageFactory factory, int accuracy, BigDecimal movementFactor, double initValue, Unit type, String name, boolean doSmoothing) {
		super(factory, accuracy, movementFactor, initValue, type, name, doSmoothing);
	}

	public OptimizingSlidingBucketsKeyspaceEstimator(RunningAverageFactory raf, int accuracy, BigDecimal mf, DataInputStream dis, Unit type, String name, boolean doSmoothing) throws IOException {
		super(raf, accuracy, mf, dis, type, name, doSmoothing);
	}

	public OptimizingSlidingBucketsKeyspaceEstimator(RunningAverageFactory raf, BigDecimal mf, int accuracy, FieldSet set, Unit type, String name, boolean doSmoothing) throws EstimatorFormatException {
		super(raf, mf, accuracy, set, type, name, doSmoothing);
	}

	/* (non-Javadoc)
	 * @see freenet.node.rt.SlidingBucketsKeyspaceEstimator#interpolate(java.math.BigInteger, java.math.BigInteger, java.math.BigInteger, double, double)
	 */
	protected double interpolate(BigInteger key, BigInteger firstKeyBefore, double beforeValue,BigInteger firstKeyAfter, double afterValue) {
		if(NativeBigInteger.isNative()){ //Can we use the fast/native 'doubleValue()' method provided by NativeBigInteger?
	        BigInteger bigdiff = new NativeBigInteger(firstKeyAfter.subtract(firstKeyBefore));
	        BigInteger smalldiff = new NativeBigInteger(firstKeyAfter.subtract(key));
	        // FIXME: Converting to double loses accuracy.
	        // This may be irrelevant but if so, then we shouldn't be storing
	        // them as BigIntegers!!
	        double p = smalldiff.doubleValue()/bigdiff.doubleValue();

	        double interpolatedValue =
	            afterValue + p * (beforeValue - afterValue);
			return interpolatedValue;
		}else{
			return super.interpolate(key, firstKeyBefore, beforeValue, firstKeyAfter, afterValue);
		}

		
		
	}
}
