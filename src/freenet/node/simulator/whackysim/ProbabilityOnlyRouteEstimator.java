package freenet.node.simulator.whackysim;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;

/**
 * RouteEstimator using pSuccess only.
 */
public class ProbabilityOnlyRouteEstimator implements RouteEstimator {

    final KeyspaceEstimator pFailure; // the lower the better!
    
    public ProbabilityOnlyRouteEstimator(KeyspaceEstimatorFactory kef) {
        pFailure = kef.createProbability(1.0, null);
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

}
