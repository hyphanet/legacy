package freenet.diagnostics;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Enumeration;

/**
 * Implementation of the counting process.
 *
 * @author oskar
 */

class CountingProcess extends RandomVar {

    static {
        try {
            RandomVar.registerType("CountingProcess", RandomVar.class);
        } catch (NoSuchMethodException e) {
        }
    }

	public static abstract class CountingEventCallback implements RandomVar.EventCallback {

		public void reportArrived(VarEvent e){
			if(e instanceof CountingEvent)
				reportArrived((CountingEvent)e);
			else
				throw new IllegalArgumentException("e not a CountingEvent");
		}

		public abstract void reportArrived(CountingEvent e);
	}

    public static int VERSION = 2;
    
    private long totalEvents;

    private long lastEventTime;

    public CountingProcess(StandardDiagnostics sd, String name, int period, String comment) {
        super(sd, name, period, comment);
        lastEventTime = System.currentTimeMillis(); // FIXME
        totalEvents = 0; // reset when the var is created.
    }

    public String getType() {
        return "CountingProcess";
    }
    
    public void add(long time, long occurrences) {

        totalEvents += occurrences;
        super.add(new CountingEvent(time, 1, occurrences, totalEvents, time - lastEventTime));
        lastEventTime = time;
    }

    public VarEvent aggregate(EventDequeue.Tail t, long time, long after) {
        long nsum = 0, eventsum = 0, totalTime = 0;
        double totalEventsAverage = 0;
		synchronized (t) {
			for (Enumeration e = t.elements(); e.hasMoreElements();) {
            CountingEvent ce = (CountingEvent) e.nextElement();
            if (ce.time() > after) {

                    if (ce.time() > time) break;
                nsum += ce.n;
                eventsum += ce.events;
                totalTime += ce.timeForEvents;
                if (!Double.isNaN(ce.totalEvents)) {
                    totalEventsAverage += ce.timeForEvents * ce.totalEvents;
                }
            }
        }
            // t.purgeOldEvents(time);
		}
        totalEventsAverage = totalEventsAverage / totalTime; 
        // note, may be NaN

		return new CountingEvent(time, eventsum, nsum, totalEventsAverage, totalTime);
    }

    public VarEvent readEvent(DataInputStream in) throws IOException {
        long version = in.readLong();
        long time = version;
        if (version > StandardDiagnostics.y2k) version = 1;
        if (version < 2) {
            long n = in.readLong();
            return new CountingEvent(time, n, n, 0, 0);
        } else {
            time = in.readLong(); 
            long events = in.readLong();
            long n = in.readLong();
            double totalEvents = in.readDouble();
            long timeForEvents = in.readLong();
            return new CountingEvent(time, events, n, totalEvents, timeForEvents);
        }
    }

    public String[] headers() {
        return new String[] { "Events", "Value Change", "Mean Total Value", "Mean Time Between Events"};
    }
}
