package freenet.node.simulator.whackysim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.Arrays;

/**
 * @author toad
 * 
 * Maintains a list of the last N double's.
 * Sorts and searches this list to obtain the rank of a given
 * double.
 */
public class LRUDoubleRank implements Serializable {

    final double[] lru;
    int curMaxRank;
    final int maxRank;
    int ptr;
    final double[] temp;
    
    public static void main(String[] args) throws IOException {
        LRUDoubleRank rank = new LRUDoubleRank(10);
        System.err.println("Please enter value");
        InputStreamReader isr = new InputStreamReader(System.in);
        BufferedReader r = new BufferedReader(isr);
        String s = null;
        while((s = r.readLine()) != null) {
            double d = Double.parseDouble(s);
            int x = rank.rank(d);
            System.out.println("Rank: "+x);
        }
    }

    LRUDoubleRank(int maxRank) {
        lru = new double[maxRank];
        this.maxRank = maxRank;
        for(int i=0;i<lru.length;i++)
            lru[i] = Double.MAX_VALUE;
        curMaxRank = 0;
        ptr = 0;
        temp = new double[maxRank];
        for(int i=0;i<temp.length;i++)
            temp[i] = Double.MAX_VALUE;
    }
    
    /**
     * Return the rank of an item within the list.
     * @param rank
     * @return
     */
    public int rank(double rank) {
        // First add to list
        lru[ptr] = rank;
        ptr++;
        if(ptr >= maxRank) ptr = 0;
        if(curMaxRank < maxRank) curMaxRank++;
        // Now sort it
        for(int i=0;i<curMaxRank;i++)
            temp[i] = lru[i];
        Arrays.sort(temp);
        // Ascending numerical order - lowest first
        for(int i=0;i<curMaxRank;i++) {
            if(temp[i] == rank) return i;
        }
        throw new IllegalStateException("WTF?! Couldn't find rank just added");
    }

    public int currentMaxRank() {
        return curMaxRank;
    }
}
