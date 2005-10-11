package freenet.node.rt;

import freenet.Core;

class Estimate implements Comparable {
    // Not immutable so can be reused
	double value;
	double normalizedValue;
	NodeEstimator ne;
	long searchSuccessTime;
	double transferRate;
	final int tieBreaker;

	public String toString() {
		return super.toString()
			+ ": "
			+ ne
			+ ": value="
			+ value
			+ ", searchSuccessTime="
			+ searchSuccessTime
			+ ", transferRate="
			+ transferRate;
	}

	/**
	 * Blank constructor for filling in later
	 */
	Estimate() {
		tieBreaker = Core.getRandSource().nextInt();
	    clear();
	}

	public void clear() {
	    this.ne = null;
	    this.value = -1;
	    this.searchSuccessTime = -1;
	    this.transferRate = -1;
	}
	
	Estimate(
		NodeEstimator ne,
		double value,
		double normalizedValue,
		long searchSuccessTime,
		double transferRate) {
		this.ne = ne;
		this.value = value;
		this.normalizedValue = normalizedValue;
		this.searchSuccessTime = searchSuccessTime;
		this.transferRate = transferRate;
		tieBreaker = Core.getRandSource().nextInt();
	}

	public int compareTo(Object o) {
		Estimate e = (Estimate) o;
		if (value > e.value)
			return 1;
		if (value < e.value)
			return -1;
		else {
		    if (tieBreaker > e.tieBreaker)
		        return 1;
		    else if (tieBreaker < e.tieBreaker)
		        return -1;
			return 0;
		}
	}

	public boolean equals(Object o) {
		if (!(o instanceof Estimate))
			return false;
		Estimate e = (Estimate) o;
		return (value == e.value && tieBreaker == e.tieBreaker);
	}
}
