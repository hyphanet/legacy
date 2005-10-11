package freenet.support;

public class KeySizeHistogram {

    public synchronized void add(long len) {
	int binNumber = 0;
	int i = 2048;
		for (binNumber = 0; i < len;) {
		i <<= 1;
		binNumber++;
		}
		if (binNumber < 0)
			binNumber = 0;
		if (binNumber > 15)
			binNumber = 15;
	bins[binNumber]++;
    }
    
    public synchronized void remove(long len) {
	int binNumber = 0;
	int i = 2048;
		for (binNumber = 0; i < len;) {
		i <<= 1;
		binNumber++;
		}
		if (binNumber < 0)
			binNumber = 0;
		if (binNumber > 15)
			binNumber = 15;
	bins[binNumber]--;
    }
    
    // Distribution of keys with most significant nibbles 0-f.
    public synchronized int[] getBins() {
        int[] ret = new int[bins.length];
        System.arraycopy(bins, 0, ret, 0, bins.length);
        return ret;
    }
    
    public Object clone() {
		// Synchronization will likely be necessary if any other variables are
		// added, much like KeyHistogram.clone().
	KeySizeHistogram h = new KeySizeHistogram();
		System.arraycopy(bins, 0, h.bins, 0, bins.length);
	return h;
    }
    
    protected final int bins[] = new int[21];
}
