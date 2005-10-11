package freenet.support;

public class LimitCounter {
    private long timeoutMs = -1;
    private long intervalMs = -1;
    private int maxCount = 0;
    private int count = 0;
    
    private final synchronized void checkTimeout() {
        long nowMs = System.currentTimeMillis();
        if (nowMs > timeoutMs) {
            count = 0;
            timeoutMs = nowMs + intervalMs;
        }
    }

    public LimitCounter(long intervalMs, int maxCountsPerInterval) {
        this.intervalMs = intervalMs;
        this.maxCount = maxCountsPerInterval;
        this.timeoutMs = System.currentTimeMillis() + intervalMs;
    }

    // returns true if rate has been exceeded.
    public synchronized boolean inc() {
        checkTimeout();
        count++;
        return count > maxCount;
    }

    public synchronized boolean exceeded() {
        checkTimeout();
        return count > maxCount;
    }
    
    // Currently dumps the current status to a string
	public synchronized String toString() {
		checkTimeout();
		return count+"/"+maxCount+" ("+Float.toString(((float)count / (float)maxCount*100f))+"%)";
	}

    // for the *current interval*.
    public synchronized float rate() {
        checkTimeout();
        if (count == 0) {
            return (float)0.0;
        }
        return (float)count / (float) intervalMs; 
    }
}


