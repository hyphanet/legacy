package freenet.node.simulator.whackysim;

import java.io.PrintWriter;

import freenet.Key;
import freenet.node.rt.BucketDistribution;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;

/**
 * RouteEstimator using pSuccess only.
 */
public class ProbabilityOnlyRouteEstimator implements RouteEstimator {

    final KeyspaceEstimator pFailure; // the lower the better!
    
    public ProbabilityOnlyRouteEstimator(KeyspaceEstimatorFactory kef) {
        pFailure = kef.createProbability(0.0, null);
    }

    public double estimate(Key k) {
        return pFailure.guessProbability(k);
    }

    public void succeeded(Key k, long time) {
        pFailure.reportProbability(k, 0.0);
    }

    public void failed(Key k, long time) {
        pFailure.reportProbability(k, 1.0);
    }

    public void dump(PrintWriter pw, String dumpFilename) {
        pw.println(getClass().getName());
        BucketDistribution bd = new BucketDistribution();
        pFailure.getBucketDistribution(bd);
        pw.println(bd.toString());
    }

    public long hits() {
        return pFailure.countReports();
    }

}
