package freenet.node.rt;

import freenet.Core;
import freenet.support.Logger;

/**
 * A class to count events over time. Divides them into buckets of size
 * determined by the constructor argument, and then efficiently returns
 * the total number of events in the required period, providing it is a
 * largish multiple of the bucket size.
 * @author amphibian
 */
public class TemporalEventCounter {
    
    private final TimeBucket buckets[];
    private final int maxBuckets;
    private final long bucketLength;
    private int bucketIndex;
    private final long initTime;
    
	private static boolean logDEBUG;
	private static int logDEBUGRefreshCounter=0;
    
    public TemporalEventCounter(long bucketLength, int maxBuckets) {
        this.maxBuckets = maxBuckets;
        this.bucketLength = bucketLength;
        buckets = new TimeBucket[maxBuckets];
        initTime = System.currentTimeMillis();
        buckets[0] = new TimeBucket(initTime);
        bucketIndex = 0;
    }
    
    public int getUptime() {
        return (int)(System.currentTimeMillis() - initTime);
    }
    
    public class TimeBucket {
        int events;
        long startTime;
        TimeBucket(long initTime) {
            startTime = initTime;
        }
        public String toString() {
            return super.toString() + ": events="+events+
            	", startTime="+startTime;
        }
    }

    /**
     * Log an event. Events have no properties, they are just counted.
     */
    public synchronized void logEvent() {

		if(logDEBUGRefreshCounter%1000 == 0) //Dont refresh the flag too often, saves us some CPU
			logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		logDEBUGRefreshCounter++;
		
        long now = System.currentTimeMillis();
        TimeBucket curBucket = buckets[bucketIndex];
        if(logDEBUG)
            Core.logger.log(this, "logEvent() on "+this+": curBucket="+
                    curBucket+", bucketIndex="+bucketIndex+", now="+now+
                    ", bucketLength="+bucketLength, Logger.DEBUG);
        if(curBucket.startTime + bucketLength < now) {
            // Advance to next bucket
            long startTime = curBucket.startTime;
            // cheaper than division for all conceivable uses :)
            while(startTime < now) {
                startTime += bucketLength;
            }
            startTime -= bucketLength;
            bucketIndex++;
            if(bucketIndex == buckets.length) bucketIndex = 0;
            curBucket = buckets[bucketIndex] = new TimeBucket(startTime);
            if(logDEBUG)
                Core.logger.log(this, "Advanced to next bucket: "+curBucket+
                        ", startTime="+startTime+", now="+now+
                        ", bucketIndex="+bucketIndex, Logger.DEBUG);
        }
        curBucket.events++;
    }

    /**
     * @param i the time over which to count the events
     * @return
     */
    public synchronized int countEvents(int i) {
        boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        if(logDEBUG)
            Core.logger.log(this, "countEvents("+i+") on "+this+
                    ": bucketIndex="+bucketIndex, Logger.DEBUG);
        int counter = 0;
        long now = System.currentTimeMillis();
        long start = now - i;
        int pos = bucketIndex;
        TimeBucket tb;
        while(true) {
            tb = buckets[pos];
            if(logDEBUG)
                Core.logger.log(this, "countEvents() on "+this+
                        " in while: pos="+pos+", tb="+tb+
                        ", counter="+counter, Logger.DEBUG);
            if(tb == null) break;
            if(tb.startTime < start) break;
            counter += tb.events;
            pos--;
            if(pos < 0) pos = maxBuckets-1;
            if(pos == bucketIndex) break;
            tb = buckets[pos];
        }
        if(logDEBUG)
            Core.logger.log(this, "countEvents() on "+this+
                    " returning "+counter, Logger.DEBUG);
        return counter;
    }
}
