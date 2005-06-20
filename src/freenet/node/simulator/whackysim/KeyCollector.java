package freenet.node.simulator.whackysim;

import java.io.Serializable;
import java.util.Random;

import freenet.Key;

/**
 * Class to collect the last N keys
 */
public class KeyCollector implements Serializable {

    KeyWithCounter[] keys;
    int totalKeys;
    int index;
    int mark;
    final Random r;
    boolean hasKeys = false;
    
    public KeyCollector(int numkeys, Random r) {
        keys = new KeyWithCounter[numkeys];
        totalKeys = 0;
        index = 0;
        mark = 0;
        this.r = r;
    }

    public void add(Key k) {
        if(k == null) throw new NullPointerException();
        keys[index] = new KeyWithCounter(k);
        totalKeys++;
        index++;
        if(index == keys.length) index = 0;
        if(index >= mark) mark = index;
        hasKeys = true;
    }

    public KeyWithCounter getRandomKey() {
        int range = Math.min(totalKeys, keys.length);
        range = Math.min(range, mark);
        int x = r.nextInt(range);
        KeyWithCounter k = keys[x];
        if(k == null) throw new NullPointerException();
        return k;
    }

    /**
     * @return The current buffer size.
     */
    public int keepingKeys() {
        return keys.length;
    }

    /**
     * Change the buffer size.
     * @param keepKeys
     */
    public void setKeep(int keepKeys) {
        if(keepKeys < keys.length) {
            // Easy
            KeyWithCounter[] newKeys = new KeyWithCounter[keepKeys];
            System.arraycopy(keys, 0, newKeys, 0, keepKeys);
            keys = newKeys;
            if(keepKeys == 0) hasKeys = false;
            mark = Math.min(mark, keepKeys);
        } else if(keepKeys > keys.length) {
            // Harder...
            KeyWithCounter[] newKeys = new KeyWithCounter[keepKeys];
            System.arraycopy(keys, 0, newKeys, 0, keys.length);
            keys = newKeys;
        }
    }

    public boolean hasKeys() {
        return hasKeys;
    }

    public int countValidKeys() {
        int range = Math.min(totalKeys, keys.length);
        range = Math.min(range, mark);
        System.out.println("total: "+totalKeys+", len: "+keys.length+", mark: "+mark);
        return range;
    }

    /**
     * @param i
     * @return
     */
    public KeyWithCounter get(int i) {
        return keys[i];
    }
}
