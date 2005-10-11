package freenet;

import java.io.PrintWriter;
import java.util.Date;
import java.util.Enumeration;
import java.util.Vector;

import freenet.diagnostics.ExternalContinuous;
import freenet.support.BlockingQueue;
import freenet.support.Heap;
import freenet.support.Logger;
import freenet.support.Schedulable;
import freenet.support.TickerToken;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.QuickSorter;
import freenet.thread.ThreadFactory;

/*
 * This code is part of the Java Adaptive Network Client by Ian Clarke. It is
 * distributed under the GNU General Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * This class sends MessageObjects to a MessageHandler after a specified amout
 * on time.
 * 
 * <p>
 * It's now based on a heap (so it should be pretty fast), but you have to
 * handle keeping track of objects you may wish to cancel yourself (I didn't
 * want another fucking hashtable). Since this makes it Freenet specific, I'm
 * moving it to the root (and Freenet's main instance. of it is an instance,
 * rather than a static, variable in freenet.Core).
 * </p>
 * 
 * <p>
 * BTW, it doesn't tick anymore (sigh...)
 * </p>
 * 
 * @author oskar
 * @author <A HREF="mailto:I.Clarke@strs.co.uk">Ian Clarke </A>
 */
public class Ticker implements Runnable {

    private final Heap events = new Heap();

    private final MessageHandler mh;

    private final ThreadFactory tf;

    private final ExternalContinuous tickerDelay = Core.diagnostics.getExternalContinuousVariable("tickerDelay");

    private final int desiredMaxImmediateExecutionQueueSize = 2000;

    //TODO: Hadcoded

    private ImmediateMessageExecutionThread immediateExecutionThread = new ImmediateMessageExecutionThread(desiredMaxImmediateExecutionQueueSize);

    //TODO: Hadcoded

    /**
     * Construct a Ticker with the given tick time in milliseconds.
     * 
     * @param mh
     *            The MessageHandler to send events to.
     * @param tf
     *            A ThreadFactory to get threads to handle events in from. If
     *            this is null, new threads will be created manually for each
     *            event.
     */
    public Ticker(MessageHandler mh, ThreadFactory tf) {
        this.mh = mh;
        this.tf = tf;
        immediateExecutionThread.start();
    }

    /**
     * Returns the MessageHandler currently used by this Ticker.
     */
    public final MessageHandler getMessageHandler() {
        return mh;
    }

    /**
     * Schedules a MessageObject.
     * 
     * @param time
     *            The time to wait (in milliseconds) before handling the
     *            MessageObject. If the MessageObject implements the
     *            Schedulable interface, then it will be given a TickerToken it
     *            can use to cancel itself.
     * @param mo
     *            The MessageObject to schedule.
     * @see Schedulable
     */
    public final void add(long time, MessageObject mo) {
        if(mo == null) throw new NullPointerException();
        if (time < 0) {
            Core.logger.log(this, "Scheduling " + mo + " for " + time, new IllegalStateException("scheduling in the past!"), Logger.NORMAL);
            // not ERROR since we have an adequate workaround
            time = 0;
        }
        addAbs(time + System.currentTimeMillis(), mo);
    }

    /**
     * Run the event on the immediate execution thread if possible (@see
     * StandardMessageHandler.handle) unless there are too many events enqueued
     * there already in which case the calling thread itself will be used to
     * execute the event
     */
    public final void addNowOrRun(MessageObject mo) {
        if (!immediateExecutionThread.enqueue(mo)) runOrSchedule(mo);
    }

    /**
     * Run the event immediately if possible (@see
     * StandardMessageHandler.handle) Otherwise add(now, mo)
     */
    private final void runOrSchedule(MessageObject mo) {
        long startTime = System.currentTimeMillis();
        try {
            if (!mh.handle(mo, true)) add(0, mo);
        } catch (Throwable t) {
            Core.logger.log(this, "Caught " + t + " handling " + mo + " in Ticker.addNowOrRun", Logger.ERROR);
        }
        long endTime = System.currentTimeMillis();
        long len = endTime - startTime;

        // Typical worst case GC is around 2 seconds
        if (len > 3000) {
            Core.logger.log(this, "Handling " + mo + " took more than 3000ms: " + len + " at " + endTime + "!", len > 10000 ? Logger.ERROR
                    : Logger.NORMAL);
        } else
            if(Core.logger.shouldLog(Logger.MINOR,this)) Core.logger.log(this, "Handling " + mo + " took " + len + "ms", Logger.MINOR);
    }

    /**
     * Like add(), but the time is given as an absolute in milliseconds of the
     * epoch (System.currentTimeMillis() format).
     */
    public final void addAbs(long time, MessageObject mo) {

        if(mo == null) throw new NullPointerException();
        long x = System.currentTimeMillis();
        if (time < x) time = x;

        Event evt = new Event(mo, time);

        if (Core.logger.shouldLog(Logger.DEBUG, this))
                Core.logger.log(this, "scheduling " + evt + " to run at " + time + " at " + x, new Exception("debug"), Logger.DEBUG);
        synchronized (this) {
            evt.heapElement = events.put(evt);

            if (mo instanceof Schedulable) {
                ((Schedulable) mo).getToken(evt);
            }
            this.notify(); // wake up ticker thread
        }
    }

    public void run() {
        Vector jobs = new Vector();
        while (true) {
            boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
            if (logDEBUG) timeLog("Running Ticker loop");
            synchronized (this) {
                if (logDEBUG) timeLog("Synchronized in Ticker loop");
                // prepare ready events for immediate execution
                while (events.size() > 0 && ((Event) events.top()).time() <= System.currentTimeMillis()) {
                    if (logDEBUG) timeLog("Popping events from Ticker loop");
                    Event e = (Event) events.pop();
                    jobs.addElement(e);
                }
                if (logDEBUG) timeLog("No more events");
                // if none were ready, sleep until one is
                if (jobs.isEmpty()) {
                    if (logDEBUG) timeLog("Jobs empty");
                    try {
                        if (events.size() > 0) {
                            if (logDEBUG) timeLog("Waiting for next event");
                            long wait = ((Event) events.top()).time() - System.currentTimeMillis();
                            if (logDEBUG) timeLog("Waiting for next event in " + wait + " ms");
                            if (wait > 0) wait(wait);
                        } else {
                            if (logDEBUG) timeLog("Waiting for more jobs");
                            wait(1000);
                        }
                    } catch (InterruptedException e) {
                        if (logDEBUG) timeLog("Interrupted");
                    }
                    if (logDEBUG) timeLog("Continuing Ticker loop");
                    continue; // and jump back to preparing ready events
                }
            }
            if (logDEBUG) timeLog("Released monitor");
            // then execute the events *after* releasing the monitor
            Enumeration e = jobs.elements();
            do {
                Event v = ((Event) e.nextElement());
                boolean executed = false;
                for (int x = 0; x < 5 && !executed; x++) {
                    if (logDEBUG) timeLog("Executing " + v);
                    try {
                        v.execute();
                        if (logDEBUG) timeLog("Executed " + v);
                        executed = true;
                    } catch (Throwable t) {
                        if (t instanceof OutOfMemoryError) {
                            // Don't bother logging it
                            System.gc();
                            System.runFinalization();
                            Thread.yield();
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException ie) {
                                // Who cares?
                            }
                            System.gc();
                            System.runFinalization();
                        }
                        Core.logger.log(this, "Could not execute " + v + ": " + t, t, Logger.ERROR);
                        System.err.println("Could not execute " + v + ": " + t);
                        t.printStackTrace(System.err);
                        if (!(t instanceof OutOfMemoryError)) break;
                    }
                }
                if (!executed) {
                    Core.logger.log(this, "Failed to execute " + e, Logger.ERROR);
                    System.err.println("Failed to execute " + e);
                }
            } while (e.hasMoreElements());
            if (logDEBUG) timeLog("Executed jobs");
            jobs.setSize(0);
            if (logDEBUG) timeLog("Looping outer Ticker loop");
        }
    }

    protected void timeLog(String s) {
        if (Core.logger.shouldLog(Logger.DEBUG, this)) Core.logger.log(this, s + " at " + System.currentTimeMillis(), Logger.DEBUG);
    }

    public synchronized String toString() {
        StringBuffer sb = new StringBuffer();
        Enumeration e = events.elements();
        while (e.hasMoreElements()) {
            Event evt = (Event) e.nextElement();
            sb.append(evt.time()).append(" - ").append(Long.toHexString(evt.getOwner().id())).append(" : ").append(
                    evt.getOwner().getClass().getName()).append('\n');
        }
        return sb.toString();
    }

    public synchronized void writeEventsHtml(PrintWriter pw) {
        pw.println("<h2>Fred Ticker Contents</h2> <b>At date:");
        pw.println(new Date());
        pw.println("</b><br />");
        immediateExecutionThread.writeEventsHtml(pw);

        pw.println("<br /><b>Pending tasks (" + events.size() + ")</b><table border=\"1\">");
        pw.println("<tr><th>Time</th><th>Event</th></tr>");
        Heap.Element[] el = events.elementArray();
        QuickSorter.quickSort(new ArraySorter(el));
        for (int i = 0; i < el.length; i++) {
            pw.print("<tr><td>");
            Event ev = (Event) el[i].content();
            pw.print(new Date(ev.time()).toString().replaceAll(" ", "&nbsp;"));
            pw.print("</td><td>");
            pw.print(ev.mo);
            pw.println("</td></tr>");
        }
        pw.println("</table>");
    }

    private static long eventCounter = 0;

    private static Object eventCounterSync = new Object();

    private class Event implements Runnable, TickerToken, Comparable {

        private MessageObject mo;

        private final long time;

        private Heap.Element heapElement;

        private long id;

        private Event(MessageObject mo, long time) {
            this.mo = mo;
            this.time = time;
            synchronized (eventCounterSync) {
                id = eventCounter++;
            }
        }

        private final void execute() {
            tf.getThread(this);
        }

        public final boolean cancel() {
            boolean ret;
            synchronized (Ticker.this) {
                ret = heapElement.remove();
            }
            if (Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(Ticker.this, (ret ? "cancelled " : "failed to cancel ") + this, Logger.DEBUG);
            return ret;
        }

        public final long time() {
            return time;
        }

        public final void run() {
            long now = System.currentTimeMillis();
            long delay = now - time;
            tickerDelay.count(delay);
            if (delay > 500 && Core.logger.shouldLog(Logger.DEBUG, this)) {
                Core.logger.log(this, "Long tickerDelay (" + delay + "), for event for " + time + " at " + now, new Exception("debug"), Logger.DEBUG);
            }
            if (Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(Ticker.this, "running " + this + " at " + now + " for " + time, Logger.DEBUG);
            try {
                mh.handle(mo, false);
            } catch (RuntimeException e) {
                Core.logger.log(mh, "Unhandled throw in message handling", e, Logger.ERROR);
                throw e;
            } catch (Error e) {
                throw e;
            }
        }

        public final int compareTo(Object o) {
            long m = ((Event) o).time;
            if (time < m) return 1;
            if (time > m) return -1;
            long oid = ((Event) o).id;
            if (id > oid) return 1;
            if (id < oid) return -1;
            Core.logger.log(this, "oid's equal... what's the chance of that?", Logger.NORMAL);
            return 0;
        }

        public final String toString() {
            return mo + " @ " + time;
        }

        public final MessageObject getOwner() {
            return mo;
        }
    }

    protected class ImmediateMessageExecutionThread extends Thread {

        private BlockingQueue immediateExecutionQueue = new BlockingQueue();

        //The maximum number of events to allow in the queue.
        //Might be overshot.
        private int desiredMaximumQueue;

        ImmediateMessageExecutionThread(int desiredMaximumQueue) {
            super("Ticker immediate execution thread");
            this.setDaemon(true);
            this.desiredMaximumQueue = desiredMaximumQueue;
        }

        public int getCurrentQueueSize() {
            return immediateExecutionQueue.size();
        }

        //Enqueue the message for execution.
        //Returns false if the thread already has too much work enqueued
        public boolean enqueue(MessageObject mo) {
            // No synch here since we are allowed to oovershoot the limit by a
            // couple of events or so..
            if (immediateExecutionQueue.size() < desiredMaximumQueue) {
                immediateExecutionQueue.enqueue(new EventQueueItem(mo));
                return true;
            } else
                return false;
        }

        public void run() {
            while (true) {
                try {
                    Object o = immediateExecutionQueue.dequeue();
                    runOrSchedule(((EventQueueItem) o).mo);
                } catch (InterruptedException e) {
                    // Doesn't matter
                }
            }
        }

        public void writeEventsHtml(PrintWriter pw) {
            pw.println("<b>Pending immediate execution tasks (Count: " + immediateExecutionThread.getCurrentQueueSize() + ", Desired max: "
                    + desiredMaximumQueue + ")</b>");
            if (immediateExecutionQueue.size() > 0) {
                pw.println("<table border=\"1\"><tr><th>Age</th><th>Event</th></tr>");
                Object[] events = immediateExecutionQueue.toArray();
                for (int i = 0; i < events.length; i++) {
                    pw.print("<tr><td>");
                    EventQueueItem evt = ((EventQueueItem) events[i]);
                    pw.print(System.currentTimeMillis() - evt.timeQueued);
                    pw.print("</td><td>");
                    pw.print(evt);
                    pw.println("</td></tr>");
                }
                pw.println("</table>");
            }

        }

        private class EventQueueItem {

            MessageObject mo;

            long timeQueued;

            EventQueueItem(MessageObject mo) {
                this.mo = mo;
                timeQueued = System.currentTimeMillis();
            }

            public String toString() {
                return mo.toString();
            }
        }
    }
}
