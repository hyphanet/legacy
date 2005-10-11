package freenet.support.io;
import freenet.Core;
import freenet.support.Logger;

public final class Bandwidth {
    public static final String SENT="SENT";
    public static final String RECEIVED="RECEIVED";
    public static final String BOTH="BOTH";
    
    private String type=null; //set to one of the above values by constructor
    
    private static final int ticksPerSecond = 10;
    private static final int millisPerTick = 1000 / ticksPerSecond;
    private static final int preferredMinFragmentSize=2000;
    private static final long millisPerWeek = 1000L*60L*60L*24L*7L;
    private static final long millisPerSecond = 1000L;
    private static final long millisPerAverageCheckingTick=1000L*10L; //ten seconds
    private static final long millisPerReportTick=1000L*60L*60L;
    
    protected int bandwidthPerTick; /* quantity to increment every tick.
     *This might be adjusted up or down
     * to keep the average on target*/
    private int available=0;          /* how much is available right now */
    private long moreBandwidthTime=0L; /* when more bandwidth is available */
    
    private long checkAverageTime=0L; /* when to check the long term averages */
    private long reportTime=0L;
    private int origBandwidth;      /* original constructor arg */
    private int origAverageBandwidth; /*original constructor arg */
    private long totalUsed=0L; //total bytes since startup
    private long totalEarned=0L; //total allowance since startup based on average limit
    private long timeStarted=0L; //time of startup
    
    /**
     * Sets the upper bandwidth limit, in multiples of 10Bps (Bytes, not bits),
     * for all ThrottledOutputStreams. This class will treat any bandwidth
     * under 100Bps as equals to 0Bps! A setting of 0 or less will turn
     * bandwidth limiting off for all ThrottledOutputStreams, and prevent
     * new ones from being created.
     *
     * @param bandwidth the upper bandwidth limit in multiples of 10Bps.
     */
    
    public Bandwidth(int bandwidth, int averageBandwidth, String type) {
        this.type = type;
        
        origBandwidth = bandwidth;
        origAverageBandwidth = averageBandwidth;
        bandwidthPerTick = bandwidth / ticksPerSecond;
        if(averageBandwidth>0 && bandwidthPerTick<1)
            bandwidthPerTick = 1; //to ensure new connections stay throttled (public access)
        timeStarted=System.currentTimeMillis();
        Core.logger.log(this.getClass(),"new Bandwidth("+bandwidth+","+averageBandwidth+","+type+")",Logger.MINOR);
    }
    
    public int currentBandwidthPerSecondAllowed() {
	return ticksPerSecond * bandwidthPerTick;
    }
    
    public int maximumPacketLength() {
	//return 1492;
	return Math.max(1492, origBandwidth/3);
    }
    
    public int availableBandwidth() {
	return available;
    }
    
    /**
     * account for bandwidth already used for input
     * @param used the number of bytes read from input
     * may wait up to 4 seconds if low on bandwidth.
     */
    
    public void chargeBandwidth(int used) {
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	if(logDEBUG)
	    Core.logger.log(this, "chargeBandwidth("+used+")", 
			    new Exception("debug"), Logger.DEBUG);
	synchronized(this) {
	    if(logDEBUG)
		Core.logger.log(this, "chargeBandwidth("+used+") synchronized",
				Logger.DEBUG);
	    if(used<0){
		Core.logger.log(this.getClass(),"used="+used+" seems unreasonable bandwidth to charge",Logger.ERROR);
		return;
	    }
	    totalUsed += used;
	    
	    waitForBandwidth(used);
	    
	    available -= used;
	}
	if(logDEBUG)
	    Core.logger.log(this, "Leaving chargeBandwidth("+used+")", 
			    Logger.DEBUG);
    }
    
    public class BandwidthToken {
	public int availableNow;
	public long sleepUntil;
    }
    
    /**
     * account for bandwidth used already for input
     * asynchronous version. Do not mix the two! Do not multiplex async
     * bandwidth limiting.
     * @param used the number of bytes read from input
     * @return number of millis that must be slept before can read again
     * will never block
     */
    public BandwidthToken chargeBandwidthAsync(int used) {
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	if(logDEBUG)
	    Core.logger.log(this, "chargeBandwidthAsync("+used+")", 
			    new Exception("debug"), Logger.DEBUG);
	BandwidthToken bt = new BandwidthToken();
	synchronized(this) {
	    if(logDEBUG)
		Core.logger.log(this, "chargeBandwidth("+used+") synchronized",
				Logger.DEBUG);
	    if(used<0){
		Core.logger.log(this.getClass(),"used="+used+" seems unreasonable bandwidth to charge",Logger.ERROR);
		bt.availableNow = 0;
		bt.sleepUntil = -1;
		return bt;
	    }
	    if(available < 0)
		Core.logger.log(this, "available = "+available, Logger.ERROR);
	    if(available > used) {
		bt.availableNow = available;
		available -= used;
		totalUsed += used;
		bt.sleepUntil = -1;
		if(logDEBUG)
		    Core.logger.log(this, "chargeBandwidthAsync("+used+
				    ") fulfilled order, no sleep: "+
				    "available="+available+", totalUsed="+
				    totalUsed, Logger.DEBUG);
		return bt;
	    } else {
		long x = waitForBandwidthAsync(used);
		long now = System.currentTimeMillis();
		if(x > 0)
		    bt.sleepUntil = now + x;
		else bt.sleepUntil = -1;
		if(available > used) {
		    bt.availableNow = used;
		} else {
		    bt.availableNow = available;
		}
		available -= bt.availableNow;
		totalUsed += bt.availableNow;
		if(bt.sleepUntil > 0) {
		    if(logDEBUG)
			Core.logger.log(this, "chargeBandwidthAsync("+used+
					") partially fulfilled: sleep "+
					(bt.sleepUntil-now)+"ms, available now "+
					bt.availableNow+", available="+available+
					", totalUsed="+totalUsed, Logger.DEBUG);
		} else
		    if(logDEBUG)
			Core.logger.log(this, "chargeBandwidthAsync("+used+
					") fulfilled (late): available now "+
					bt.availableNow+", available="+available+
					", totalUsed="+totalUsed, Logger.DEBUG);
		return bt;
	    }
	}
    }
    
    /**
     *wait until some desired bytes of bandwidth are available
     * may return even if less are available but will try for up to 4 seconds
     *@param desired the number of bytes of bandwidth wanted
     *@return the number of bytes granted.
     */
    
    protected int getBandwidth(int desired) {
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	if(logDEBUG)
	    Core.logger.log(this, "getBandwidth("+desired+")", 
			    new Exception("debug"), Logger.DEBUG);
	synchronized(this) {
	    if(logDEBUG)
		Core.logger.log(this, "getBandwidth("+desired+") synchronized",
				Logger.DEBUG);
	    if(desired<0) {
		Core.logger.log(this.getClass(),"desired="+desired+" seems unreasonable bandwidth to ask for",Logger.NORMAL);
		return desired;
	    }
	    waitForBandwidth(desired);
	    int result = Math.min(desired, available);
	    available -= result;
	    totalUsed += result;
	    if(logDEBUG)
		Core.logger.log(this, "Leaving getBandwidth("+desired+")",
				Logger.DEBUG);
	    return result;
	}
    }
    
    synchronized protected void putBandwidth(int returnedUnused) {
        if(returnedUnused<0){
            Core.logger.log(this.getClass(),"returnedUnused="+returnedUnused+" seems unreasonable bandwidth to put back",Logger.ERROR);
        }

        available += returnedUnused;   
    }
    
    synchronized protected void waitForBandwidth(int desired) {
	int millisToSleep = 0;
	while((millisToSleep = waitForBandwidthAsync(desired)) > 0) {
	    try {
		Thread.sleep(millisToSleep);
	    } catch (InterruptedException e) {}
	}
    }
    
    synchronized protected int waitForBandwidthAsync(int desired) {
        if(desired==0)
            return 0;
        if(desired<0){
            Core.logger.log(this.getClass(),"desired="+desired+" seems unreasonable bandwidth to ask for",Logger.ERROR);
            return 0;
        }
        
        long totalWaitMillis=0L;
        for (;;) {
		//quick test to see if we can avoid the system call
            if (available >= desired || available > preferredMinFragmentSize)
                break;
            long now = System.currentTimeMillis();
            int eeek = refillAvailableBandwidth(now);
	    if(eeek != 0) return eeek;
            if (available >= desired || available > preferredMinFragmentSize)
                break;
            long millisToSleep = moreBandwidthTime-now;
            if(millisToSleep > 1000L){
                Core.logger.log(this.getClass(),"millisToSleep="+millisToSleep+" seems unreasonable. Limiting this wait to 1000",Logger.NORMAL);
                millisToSleep = 1000L;
            }
            if(totalWaitMillis > 4000L){
                Core.logger.log(this.getClass(),"totalWaitMillis="+totalWaitMillis+" seems unreasonable. Abandon wait. desired="+desired+" available="+available,Logger.ERROR);
                break;
            }
            if(millisToSleep > 0){
		if(Core.logger.shouldLog(Logger.DEBUG,this) || millisToSleep>500)
		    Core.logger.log(this, "Bandwidth waiting "+millisToSleep+
				    ", total so far "+totalWaitMillis+"; available="+
				    available+", desired="+desired,millisToSleep>500?
				    Logger.MINOR:Logger.DEBUG);
                totalWaitMillis += millisToSleep;
		return (int)millisToSleep;
            }
        }
	return 0;
    }
    
    synchronized private int refillAvailableBandwidth(long now) {
        if (now >= moreBandwidthTime) {
            
            if(now > moreBandwidthTime+10*millisPerTick){
                available += bandwidthPerTick;
                moreBandwidthTime = now + millisPerTick;
            } else {
                available +=bandwidthPerTick;
                moreBandwidthTime = moreBandwidthTime + millisPerTick;
            }
            
             /* take the oppportunity to check long term averages */
            if(origAverageBandwidth!=0){
                long uptimeThisWeek = now-timeStarted;
                if(timeStarted==0L || (uptimeThisWeek)>millisPerWeek){
                    timeStarted = now;
                    totalEarned = 0L;
                    totalUsed = 0L;
                    checkAverageTime = now+millisPerAverageCheckingTick;
                    reportTime = now+millisPerReportTick;
                } else if(now > checkAverageTime){
                    checkAverageTime = now+millisPerAverageCheckingTick;
                    totalEarned = (uptimeThisWeek*origAverageBandwidth)/millisPerSecond;
                    if(totalEarned > totalUsed){
                        //restore original bandwidthLimit
                        bandwidthPerTick = origBandwidth / ticksPerSecond;
                    } else if(totalEarned+400000L < totalUsed){
                        //throttle to long term average and pause activity
                        bandwidthPerTick = origAverageBandwidth/ticksPerSecond;
			return 10000;
                    } else if(totalEarned+200000L < totalUsed){
                        //throttle to long term average
                        bandwidthPerTick = origAverageBandwidth/ticksPerSecond;
                    } else if(totalEarned+100000L < totalUsed){
                        //less serious. see if half the bandwidth will do
                        bandwidthPerTick = origBandwidth/2/ticksPerSecond;
                    }
                    if(bandwidthPerTick <1) //must never go less than 1
                        bandwidthPerTick=1;
                    
                    if(now > reportTime){
                        reportTime = now+millisPerReportTick;
                        Core.logger.log(this.getClass(),"bytes "+type+"="+totalUsed+" bytes earned="+totalEarned,Logger.NORMAL);
                    }
                }
            }
            
            
        }
	return 0;
    }
}
