package freenet.node.simulator.newsim;

import java.util.Random;

import freenet.Key;

/**
 * Class to collect the last N keys
 */
public class KeyCollector {

    KeyWithCounter[] keys;
    int totalKeys;
    int index;
    final Random r;
    
    public KeyCollector(int numkeys, Random r) {
        keys = new KeyWithCounter[numkeys];
        totalKeys = 0;
        index = 0;
        this.r = r;
    }

    public void add(Key k) {
        if(k == null) throw new NullPointerException();
        keys[index] = new KeyWithCounter(k);
        totalKeys++;
        index++;
        if(index == keys.length) index = 0;
    }

    public KeyWithCounter getRandomKey() {
        int range = Math.min(totalKeys, keys.length);
        int x = r.nextInt(range);
        KeyWithCounter k = keys[x];
        if(k == null) throw new NullPointerException();
        return k;
    }
}
