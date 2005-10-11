package freenet.node.simulator.newsim;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;

/**
 * RouteEstimator using tSuccess only, with a fixed penalty for failure.
 */
public class TSuccessFixedPenaltyRouteEstimator implements RouteEstimator {

    final KeyspaceEstimator tSuccess;
    final int bias;
    
    public TSuccessFixedPenaltyRouteEstimator(KeyspaceEstimatorFactory kef, int startVal, int bias) {
        tSuccess = kef.createTime(null, startVal, startVal, null);
        this.bias = bias;
    }

    public double estimate(Key k) {
        return tSuccess.guessTime(k);
    }

    public void succeeded(Key k, long time) {
        tSuccess.reportTime(k, time);
    }

    public void failed(Key k, long time) {
        tSuccess.reportTime(k, bias);
    }

}
