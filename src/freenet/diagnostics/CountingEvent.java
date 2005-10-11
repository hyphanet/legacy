package freenet.diagnostics;
import java.io.DataOutputStream;
import java.io.IOException;
/**
 * Implementation of a counting event.
 *
 * @author oskar
 */


class CountingEvent extends VarEvent {
    
    public final long n;
    public final long events;
    public final double totalEvents;
    // Time Between Events
    public final long timeForEvents;
    
    public CountingEvent(long time, long events, long n, double totalEvents,
                         long timeForEvents) {
        super(time);
        this.events = events;
        this.n = n;
        this.totalEvents = totalEvents;
        this.timeForEvents = timeForEvents;
    }

    public String toString() {
        return "Counted " + n + " events.";
    }

    public double timeBetweenEvents() {
        return (double) timeForEvents / events;
    }

    public String[] fields() {
        return new String[] { 
            Long.toString(events), Long.toString(n),
            Double.toString(totalEvents), Double.toString(timeBetweenEvents())
        };
    }

    public double getValue(int type) {
        switch (type) {
        case Diagnostics.NUMBER_OF_EVENTS:
            return events;
        case Diagnostics.MEAN_TIME_BETWEEN_EVENTS:
            return timeBetweenEvents();
        case Diagnostics.MEAN_RUNTIME_COUNT:
            return totalEvents;
        case Diagnostics.COUNT_CHANGE:
            return n;
        default:
            throw new IllegalArgumentException("Unsupported value type.");
        }
    }

    public void write(DataOutputStream out) throws IOException {
        super.write(CountingProcess.VERSION, out);
        out.writeLong(events);
        out.writeLong(n);
        out.writeDouble(totalEvents);
        out.writeLong(timeForEvents);
    }

}
