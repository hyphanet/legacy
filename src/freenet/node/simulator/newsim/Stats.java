package freenet.node.simulator.newsim;

/**
 * Simple request statistics class
 */
public class Stats {

    int totalRequests;
    int totalInserts;
    int successfulRequests;
    int successfulInserts;
    
    public Stats() {
        clear();
    }

    void clear() {
        totalRequests = 0;
        totalInserts = 0;
        successfulRequests = 0;
        successfulInserts = 0;
    }

}
