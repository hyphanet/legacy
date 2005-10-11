package freenet.diagnostics;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Hashtable;
import java.util.Vector;

import freenet.diagnostics.EventDequeue.Tail;
import freenet.diagnostics.EventDequeue.Head;

/**
 * Superclass of different types of variables.
 *
 * @author oskar
 */

abstract class RandomVar {

	protected interface EventCallback {

		public void reportArrived(VarEvent e);
	}

    private static Hashtable types;

    public static void registerType(String name, Class type) throws NoSuchMethodException {

        Constructor c = type.getConstructor(new Class[] { java.lang.String.class, Integer.TYPE});
        types.put(name, c);
    }
    
    protected final String name, comment;
    
    /** The smallest type of period this var supports (from MINUTE, HOUR, ..., above) */
    protected final int shortestSupportedPeriod;

    protected final EventDequeue[] periodAggs;
    
    /**
     * This list might *not* be modified. The only change that is allowed is
     * reference replacement (listeners = newlistenerlist) and during that time
     * one should sync on the listenersLock object below
     */
    protected EventCallback[] listeners;

    protected final Object listenersLock = new Object();
    
    protected RandomVar(StandardDiagnostics sd, String name, int period, String comment) {
        this.name = name;
        this.comment = comment;
        this.shortestSupportedPeriod = period;
        Vector v = new Vector();
        v.addElement(sd.newList(name));
        for (int i = 0 ; ; i++) {
            EventDequeue el = sd.getList(name, period + i);
            if (el == null)
                break;
            else 
                v.addElement(el);
        }
        
        //This should never happen.. still, a cheap sanity check won't hurt
        if(v.size()>StandardDiagnostics.periods.length+1) 
                throw new IllegalArgumentException("Got more periods than the defined ones (" + v.size() + ">"
                        + (StandardDiagnostics.periods.length + 1) + ")");
        
        //Reserve lists for all possible periods up to, and including, DECADE
        // beforehand. This relieves us of the, somewhat cumbersome, task of
        // creating new period lists when demand arises later on
		for (int i = v.size() ;i<StandardDiagnostics.periods.length+1 ; i++) {
			v.addElement(sd.newList(name,i));
		}
		periodAggs = new EventDequeue[v.size()]; 
        v.copyInto(periodAggs);
    }

    /**
     * Returns the type of the Var of this object.
     */
    public abstract String getType();

    /**
     * Returns the name given to this var.
     */
    public String getName() {
        return name;
    }

    public String getComment() {
        return comment;
    }

    public int aggregationPeriod() {
        return shortestSupportedPeriod;
    }

    public int aggregations() {
        return periodAggs.length - 1;
    }
    
    /**
     * Returns the events over a given period, or the latest occurrences if
     * eventPeriod < 0.
     */
    EventDequeue getEvents(int eventPeriod) {
        int offset;
        if (eventPeriod < 0)
            offset = 0;
        else {
            offset = eventPeriod - shortestSupportedPeriod + 1;
            if (offset == 0) // zero offset means occurrences below, but here
                    // it would mean that formatPeriod was one less
                    // than period
                offset = -1;
        }
		return (offset < 0 || offset >= periodAggs.length ? null : periodAggs[offset]);
    }
    
    protected void add(VarEvent ve) {
        addTo(0, ve);
        
		EventCallback[] l = listeners;
		if(l != null){ //Will be null as long as we dont have any listeners
			for(int i2 = 0;i2<l.length;i2++){
				l[i2].reportArrived(ve);
			}
		}
    }
  
    /**
     * Adds a varevent to the specified periods list
     */
    protected void addTo(int period, VarEvent ve) {
        
    	//List of all events that has occurred during the specified period
    	EventDequeue lPeriodEvents = periodAggs[period];

			lPeriodEvents.open(this);

			Head w = lPeriodEvents.getHead();
			w.add(ve);
			//w.insertSorted(ve);
			
			lPeriodEvents.close();
        
    }

    /**
     * Signals the end of a period of time.
     * 
     * @param type
     *            The type of period that ends, from MINUTE, HOUR,... above.
     *            Note that when many roll over they are called one at the time
     *            in order, so at the end of a DECADE, this is called for
     *            MINUTE, then HOUR, then DAY etc.
     * @param plength
     *            The exact length of the period that passed, this can't be
     *            determined from type because months and years shift (who
     *            thought of that!!)
     * @param time
     *            The millisecond time of the end of this period.
     */
    public void endOf(int type, long plength, long time) {
        int offset = type - shortestSupportedPeriod;
        if (offset < 0) {
            // do nothing
            return;
        }  else {
			if (offset + 2 > periodAggs.length)
				throw new IllegalStateException("Not enough periods available defined. Cannot aggregate stats further.");
				
            // eat older events..
            long start = time - plength;
			EventDequeue endingPeriodEvents = periodAggs[offset];
				endingPeriodEvents.open(this);
			Tail t = endingPeriodEvents.getTail();
            synchronized (t) {
				t.purgeOldEvents(start); //Clean up
                addTo(offset + 1, aggregate(t, time, start));
			}
			endingPeriodEvents.close();

            // cleanup old events from the next bigger period
            int nextPeriodOffset = offset + 1;
            if (nextPeriodOffset <= periodAggs.length - 1) {
                EventDequeue nextPeriodEvents = periodAggs[nextPeriodOffset];
                nextPeriodEvents.open(this);
                nextPeriodEvents.getTail().purgeOldEvents(time - StandardDiagnostics.getPeriod(nextPeriodOffset));
                nextPeriodEvents.close();
            }
        }
    }

    public double getValue(int vperiod, int type, long time) {
		EventDequeue events;
		int offset = vperiod - shortestSupportedPeriod;
        long after;
		double r;
        if (offset < Diagnostics.MINUTE) {
            events = periodAggs[Diagnostics.MINUTE];
            after = time - Diagnostics.getPeriod(Diagnostics.MINUTE);
        } else if (offset >= periodAggs.length) {
            events = periodAggs[periodAggs.length - 1]; // the biggest we have
            after = time - Diagnostics.getPeriod(vperiod); // no harm.
        } else {
            events = periodAggs[offset];
            after = time - Diagnostics.getPeriod(vperiod);
        }

		synchronized (events) {
        events.open(this);
            r = aggregate(events.getTail(), time, after).getValue(type);
        events.close();
		}
        return r;
    }

    public abstract String[] headers();
    
    public abstract VarEvent aggregate(EventDequeue.Tail tail, long time, long after);

    public abstract VarEvent readEvent(DataInputStream in) throws IOException;

    public String toString() {
        return getType() + ':' + name;
    }

    //Registers a callback that will be notified for any report that
    //comes in to this RandomVar
    public void registerEventListener(EventCallback e) {
        synchronized (listenersLock) {
   			EventCallback[] newlisteners = new EventCallback[(listeners == null)?1:(listeners.length+1)];
            if (listeners != null) // Then we need to move all old listeners to
                                   // our new list
   				System.arraycopy(listeners,0,newlisteners,0,listeners.length);
			listeners = newlisteners;
			listeners[listeners.length-1] = e;
    	}
    }

}
