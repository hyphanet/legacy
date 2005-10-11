package freenet.support;

public final class KeyHistogram {

	public synchronized void add(byte[] keyVal) {
		// Most significant nibble of the most significant byte.
		final int binNumber = (keyVal[0] & 0xff);
		bins[binNumber >>> (byte) 4]++;
		binsBigger[binNumber]++;
		total++;
	}

	public synchronized void remove(byte[] keyVal) {
		// Most significant nibble of the most significant byte.
		final int binNumber = (keyVal[0] & 0xff);
		bins[binNumber >>> (byte) 4]--;
		binsBigger[binNumber]--;
		total--;
	}

	/** Distribution of keys with most significant nibbles 0-f. */
	public synchronized int[] getBins() {
		int[] ret = new int[bins.length];
		System.arraycopy(bins, 0, ret, 0, bins.length);
		return ret;
	}

	public synchronized int[] getBiggerBins() {
		int[] ret = new int[binsBigger.length];
		System.arraycopy(binsBigger, 0, ret, 0, binsBigger.length);
		return ret;
	}

	public synchronized int getBin(int bin) {
		return bins[bin];
	}

	public synchronized int getBiggerBin(int bin) {
		return binsBigger[bin];
	}

	public int length() {
		return bins.length;
	}

	public int lengthBigger() {
		return binsBigger.length;
	}

	public synchronized long getTotal() {
		return total;
	}

	public synchronized Object clone() {
		KeyHistogram h = new KeyHistogram();
		h.total = total;
        System.arraycopy(bins, 0, h.bins, 0, bins.length );
        System.arraycopy(binsBigger, 0, h.binsBigger, 0, binsBigger.length );
		return h;
	}

	protected long total = 0;
	protected final int bins[] = new int[16];
	protected final int binsBigger[] = new int[256];
}
