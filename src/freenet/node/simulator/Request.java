package freenet.node.simulator;

import freenet.Key;

/**
 * A simulated request.
 */
public class Request {

    final Key key;
    int hopsToLive;
    
    public String toString() {
        return super.toString()+": key="+key+", htl="+hopsToLive;
    }
    
    /**
     * 
     */
    public Request(Key k, int htl) {
        key = k;
        hopsToLive = htl;
    }

    /**
     * Update the HTL for another hop
     */
    public void updateHTL() {
        hopsToLive--;
    }

}
