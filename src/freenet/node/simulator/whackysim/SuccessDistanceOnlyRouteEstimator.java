package freenet.node.simulator.whackysim;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;

/**
 * RouteEstimator using pSuccess only.
 */
public class SuccessDistanceOnlyRouteEstimator implements RouteEstimator {

    final KeyspaceEstimator tSuccess; // the lower the better!
    
    public SuccessDistanceOnlyRouteEstimator(KeyspaceEstimatorFactory kef) {
        tSuccess = kef.createTime(0, null);
    }

    public double estimate(Key k) {
        return tSuccess.guessTime(k);
    }

    public void succeeded(Key k, long time) {
        tSuccess.reportTime(k, time);
    }

    public void failed(Key k, long time) {
        // Do nothing
    }

}
