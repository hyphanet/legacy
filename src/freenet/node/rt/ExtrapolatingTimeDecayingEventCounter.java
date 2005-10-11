package freenet.node.rt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.Core;
import freenet.support.Logger;

/**
 * Keeps a count of the number of events that happen
 * per hour, based on a specified half-life, using a
 * TimeDecayingRunningAverage.
 * @author amphibian
 */
public class ExtrapolatingTimeDecayingEventCounter {
	
	TimeDecayingRunningAverage tdra;
	long lastEventTime;
	long startTime;
	long totalCount;
	boolean logDEBUG;
	
	public ExtrapolatingTimeDecayingEventCounter(long halflife, double defaultValue) {
		tdra = new TimeDecayingRunningAverage((60.0*60.0*1000.0) / defaultValue, halflife, 0, Double.MAX_VALUE);
		lastEventTime = -1;
		totalCount = 0;
		startTime = System.currentTimeMillis();
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	}
	
    public ExtrapolatingTimeDecayingEventCounter(double defaultValue, double period, DataInputStream dis) throws IOException {
        int ver = dis.readInt();
        if(ver != 1) throw new IOException("invalid version");
        tdra = new TimeDecayingRunningAverage(defaultValue, period, 0, Double.MAX_VALUE, dis);
        lastEventTime = -1;
        startTime = System.currentTimeMillis();
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        totalCount = dis.readLong();
    }

    public synchronized void logEvent() {
		long now = System.currentTimeMillis();
		totalCount++;
		if(lastEventTime <= 0) {
			lastEventTime = now;
		} else {
			long delta = now - lastEventTime;
			lastEventTime = now;
			tdra.report(delta);
		}
		if(logDEBUG)
			Core.logger.log(this, "logEvent() on "+this+": "+totalCount+
					" events over "+(now-startTime), Logger.DEBUG);
	}
	
	public synchronized double getExtrapolatedEventsPerHour() {
		double averageInterval = tdra.currentValue();
		return (60.0*60.0*1000.0) / averageInterval;
	}

	public String toString() {
	    return super.toString()+" "+tdra.toString();
	}
	
	/**
	 * Write the data to a stream.
	 * We ignore downtime - we restart the timer at the moment of restarting and ignore the gap.
	 * So we don't write lastEventTime.
	 * @param dos
	 */
    public void writeDataTo(DataOutputStream dos) throws IOException {
        dos.writeInt(1);
        tdra.writeDataTo(dos);
        dos.writeLong(totalCount);
    }

    public long countEvents() {
        return totalCount;
    }
}
