package freenet.node.rt;
import freenet.FieldSet;
import freenet.Key;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.Serializable;

public interface KeyspaceEstimatorFactory extends Serializable {
    /** Create a smooth time estimator with
     * all values initially zero.
     */
	public KeyspaceEstimator createZeroSmooth(String name);
	
	public KeyspaceEstimator createTransfer(Key k, double slowest, double fastest, String name);
	public KeyspaceEstimator createInitTransfer(double initRate, String name);
    public KeyspaceEstimator createTransferRate(DataInputStream dis, String name) throws IOException;
	public KeyspaceEstimator createTransferRate(FieldSet set, String name) throws EstimatorFormatException;
	/**
	 * Create a probability estimator with a sharp initial specialization
	 * at a given key.
	 * @param k the initial specialization
	 * @param worst the worst value on the graph. The flatter areas
	 * curve down to this value.
	 * @param best the best value on the graph. The specialization
	 * spikes up to this value.
	 * @return
	 */
	public KeyspaceEstimator createProbability(Key k,double worst,double best, String name);
	public KeyspaceEstimator createProbability(double d, String name);
	public KeyspaceEstimator createProbability(DataInputStream dis, String name) throws IOException;
	public KeyspaceEstimator createProbability(FieldSet set, String name) throws EstimatorFormatException;
	/**
	 * Create a KeyspaceEstimator, for times, with a dip around the key.
	 * Not specified what the distribution should be.
	 * @param k the key
	 * @param highest the high value. This will occur at the opposite end
	 * of the keyspace from the key itself.
	 * @param lowest the low value. This will occur at the key itself.
	 */
	public KeyspaceEstimator createTime(Key k, long lowest, long highest, String name);
	public KeyspaceEstimator createTime(long maxDNFTime, String name);
	public KeyspaceEstimator createTime(DataInputStream dis, String name) throws IOException;
	public KeyspaceEstimator createTime(FieldSet set, String name) throws EstimatorFormatException;
}
