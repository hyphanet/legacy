package freenet.node.rt;

/** A KeyspaceEstimator that allows to use double's directly for guess/etc.
 */
public interface DoubleKeyspaceEstimator extends KeyspaceEstimator {

    public double guess(double k);
    public double guess(Double k);
    
    public void report(double k, double d);
    public void report(Double k, double d);
    
    public void report(double k, boolean d);
    public void report(Double k, boolean d);

    public void reportProbability(Double k, double d);
    public void reportProbability(double k, double d);

    public void reportTime(Double k, double d);
    public void reportTime(double k, double d);
}
