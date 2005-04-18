package freenet.node.simulator.whackysim;

import freenet.Key;

/**
 * Key with a counter to indicate how many times it has been requested
 * @author amphibian
 */
public class KeyWithCounter {

    private Key k;
    private int counter;

    public KeyWithCounter(Key k2) {
        counter = 0;
        k = k2;
    }

    public Key getKeyIncCounter() {
        counter++;
        return k;
    }
    
    public int getCount() {
        return counter;
    }

    /**
     * @return
     */
    public Key getKeyDontIncCounter() {
        return k;
    }
}
