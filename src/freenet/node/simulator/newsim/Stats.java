package freenet.node.simulator.newsim;

/**
 * Simple request statistics class
 */
public class Stats {

    int totalRequests;
    int totalInserts;
    int successfulRequests;
    int successfulInserts;
    /** Number of requests that succeeded on the first fetch for that key */
    int totalFirstTimeSuccessfulRequests;
    /** Number of first-time key requests - should be ~= totalInserts */
    int totalFirstTimeRequests;
    
    public Stats() {
        clear();
    }

    void clear() {
        totalRequests = 0;
        totalInserts = 0;
        successfulRequests = 0;
        successfulInserts = 0;
        totalFirstTimeSuccessfulRequests = 0;
        totalFirstTimeRequests = 0;
    }

}
