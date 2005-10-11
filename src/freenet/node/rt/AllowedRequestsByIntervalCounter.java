package freenet.node.rt;

import freenet.Core;
import freenet.support.Logger;

/**
 * Counts allowed requests over an interval.
 * Start at interval X.
 * ... time t1 elapses...
 * We receive instruction to set interval to X1
 * Total over this period: t1 / X
 * ... time t2 elapses...
 * We receive instruction to set interval to X2
 * Total over that period: t2 / X1
 * ... time t3_1 elapses...
 * We are asked for total allowed since a long time.
 * We calculate: 
 * No chop-off at the start.
 * Complete periods: t1/X + t2/X1
 * Incomplete periods: t3_1/X2
 * Total: t1/X + t2/X1 + t3_1/X2
 * 
 * But the above requires infrequent changes in requestInterval.
 * This is unacceptable as continuous adjustment may be useful.
 * So, what we do:
 * Every N seconds, we start a new block.
 * Within each block, we have a double counter for the number of requests
 * allowed in that period.
 * Blocks are independant of changes in requestInterval.
 * 
 * A slightly simpler form would just keep a grand total and store the total
 * for each period. The problem with that is rounding errors. Actually, a
 * 64-bit double shouldn't have that many rounding problems: 52 bit mantissa 
 * leaves plenty of room.
 * @author amphibian
 */
public class AllowedRequestsByIntervalCounter {
    double totalSoFar;
    long lastRequestIntervalSet;
    double currentRequestInterval;
    double[] blocks;
    final long blockLength;
    long lastBlockEndTime;
    int maxBlocks;
    long blocksCount;
    int ptr;
    boolean logDEBUG;
    
    AllowedRequestsByIntervalCounter(long blockLength, int maxBlocks,
            double initRequestInterval) {
        blocks = new double[maxBlocks];
        for(int i=0;i<maxBlocks;i++) blocks[i] = -1.0;
        ptr = 0;
        currentRequestInterval = initRequestInterval;
        lastBlockEndTime = lastRequestIntervalSet = System.currentTimeMillis();
        totalSoFar = 0.0;
        blocksCount = 0;
        this.maxBlocks = maxBlocks;
        this.blockLength = blockLength;
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
    }

    /**
     * Set the minRequestInterval to a new value
     * @param minRequestInterval the current minRequestInterval
     */
    public synchronized void updateRequestInterval(double minRequestInterval) {
        if(minRequestInterval == currentRequestInterval) return;
        long now = System.currentTimeMillis();
        if(logDEBUG) Core.logger.log(this, "updateRequestInterval("+minRequestInterval+
                "), totalSoFar="+totalSoFar+", Tdelta ="+(now - lastRequestIntervalSet)+
                ", currentRequestInterval="+currentRequestInterval+", ptr="+ptr+" ("+this+")",
                new Exception("debug"), Logger.DEBUG);
        updateBlocks(now);
        // Now update totalSoFar
        totalSoFar += (now - lastRequestIntervalSet) / currentRequestInterval;
        lastRequestIntervalSet = now;
        currentRequestInterval = minRequestInterval;
    }
    
    protected synchronized void updateBlocks(long now) {
    	if(logDEBUG)
    		Core.logger.log(this, "updateBlocks("+now+")", 
    				new Exception("debug"), Logger.DEBUG);
        // Are we on the same block?
        if(blockLength + lastBlockEndTime < now) {
            // Nope
            // Fill blocks up to now
            double total = totalSoFar;
            // First block
            total += 
                (lastBlockEndTime + blockLength - lastRequestIntervalSet) /
                currentRequestInterval;
            lastBlockEndTime += blockLength;
            blocks[ptr] = total;
            ptr++;
            if(ptr == maxBlocks) ptr = 0;
            if(logDEBUG) Core.logger.log(this, "ptr="+ptr+" on "+this+" (A)", Logger.DEBUG);
            blocksCount++;
            while(lastBlockEndTime + blockLength < now) {
                lastBlockEndTime += blockLength;
                total += blockLength / currentRequestInterval;
                blocks[ptr] = total;
                ptr++;
                if(ptr == maxBlocks) ptr = 0;
                if(logDEBUG) Core.logger.log(this, "ptr="+ptr+" on "+this+" (B)", Logger.DEBUG);
                blocksCount++;
            }
        }
    }

    /**
     * @param i the length of time to return data for
     * @return the number of allowed requests over the given time period
     */
    public synchronized double count(long i) {
        long now = System.currentTimeMillis();
        updateBlocks(now);
        int count = (int) (i/blockLength);
        if(count > maxBlocks) count = maxBlocks;
        if(count > blocksCount) count = (int)blocksCount;
        int myPtr = ptr - count;
        if(myPtr < 0) myPtr += maxBlocks;
        double startVal = blocks[myPtr];
        if(startVal < 0) startVal = 0;
        Core.logger.log(this, "count("+i+"): Tdelta="+(now-lastRequestIntervalSet)+
                ", ptr="+ptr+", myPtr="+myPtr+", count="+count+
                ", currentRequestInterval="+currentRequestInterval+", startVal="+
                startVal+", totalSoFar="+totalSoFar+" ("+this+")", Logger.DEBUG);
        return totalSoFar + 
        	((now - lastRequestIntervalSet) / currentRequestInterval)
        	- startVal;
    }
}
