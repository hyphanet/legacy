package freenet.node.rt;

import freenet.Core;
import freenet.support.Logger;

/**
 * Created on Apr 20, 2004
 * @author Iakin
 *
 * Extends the TemporalEventCounter class to add some 
 * events/time extrapolating functionality. Selects a random
 * interval between 6 minutes 40 and 10 minutes at creation to 
 * use for extrapolated events per hour. This is a good idea 
 * to avoid oscillations on the network.
 */
public class ExtrapolatingTemporaEventCounter extends TemporalEventCounter {
	
	/** Random sampling period, chosen at initialization,
	 * between 6 minutes 40 and 10 minutes.
	 */
	int maxUptime;
	
	public ExtrapolatingTemporaEventCounter(long bucketLength, int maxBuckets,
			int samplingInterval) {
		super(bucketLength, maxBuckets);
		maxUptime = samplingInterval;
	}

	/**
     * @return the average events per hour based on the events
     * received over the last 10 minutes.
     */
    public double getExtrapolatedEventsPerHour() {
        int uptime = getUptime();
        if(uptime > maxUptime) uptime = maxUptime;
        int requests = countEvents(uptime);
        if(uptime == 0) return Double.POSITIVE_INFINITY;
        if(Core.logger.shouldLog(Logger.DEBUG, this))
        	Core.logger.log(this, toString()+".getExtrapolatedRequestsPerHour():"
        			+requests+" requests in "+uptime+"ms, max="+maxUptime,
					Logger.DEBUG);
        return (double) requests * ((3600*1000) / uptime);
    }
}
