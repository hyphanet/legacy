package freenet.node.simulator.whackysim;

import freenet.Key;

/**
 * Roughly equivalent to NodeEstimator in the real code.
 */
public interface RouteEstimator {

    /**
     * Estimate goodness value of sending the request to this node. Lower is 
     * better; normally the request will be sent to the node with the lowest
     * estimate value.
     * @param k The key being requested. Should be used by any implementation of
     * RouteEstimator.
     */
    double estimate(Key k);

    /**
     * Report that the request succeeded.
     * @param k The key being requested.
     * @param time The time, in hops, taken to get the data.
     */
    void succeeded(Key k, long time);

    /**
     * Report that the request failed.
     * @param k The key being requested.
     * @param time The time, in hops, taken to get the DNF.
     */
    void failed(Key k, long time);
}
