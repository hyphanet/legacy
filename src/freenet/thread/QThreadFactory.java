/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.thread;

import freenet.Core;
import freenet.support.Irreversible;
import freenet.support.Logger;

/**
 * A derivative of PThreadFactory that attempts the keep the available
 * threads within a sane ratio of the number of active ones. It does not
 * stop pooling when overflow is reached.
 *
 * @author oskar
 * @author tavin
 * @author lostlogic
 * @author ejhuff
 */
public final class QThreadFactory implements ThreadFactory, Runnable {
    
    /** The absolute minimum amount of active threads to be available */
    public static final int MINIMUM_AVAILABLE_ABS = 3;
    /** The minimum ratio of the number of active threads to be available */
    private static final double MINIMUM_AVAILABLE_RATIO = 0.1;
    /** The maximum ratio of the number of active threads to keep available */
    private static final double MAXIMUM_AVAILABLE_RATIO = 1;
    /** The ideal ratio of the number of active threads to keep available */
    private static final double IDEAL_AVAILABLE_RATIO = 
        (2 * MINIMUM_AVAILABLE_RATIO + MAXIMUM_AVAILABLE_RATIO) / 3;
    
    private final ThreadGroup tg;
    
    private final CountLock countLock = new CountLock();
    
    private final NumLock numLock = new NumLock();
    
    private final HeadLock headLock = new HeadLock();
    
    private final boolean logDEBUG;
    
    private final MaxLock maxLock = new MaxLock();
    
    /**
     * @param tg     ThreadGroup all created threads will belong to
     */
    public QThreadFactory(ThreadGroup tg, int desiredMax) {
        this.tg = tg;
        this.maxLock.setDesiredMax(desiredMax);
        this.logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        (new Thread(this, "Thread creation thread.")).start();
    }
    
	public final void configUpdater(
		int targetMaxThreads,
		int tolerableQueueDelay,
		int absoluteMaxThreads) {
	}

    public final void run() {
        CountSnap snap = new CountSnap();
        while (true) {
            Throwable lastEx = null;
            try {
                boolean doLog = false;
                int required;
                countLock.snap(snap);
                // start with a minimal required value.
                required = (int)(snap.active * MINIMUM_AVAILABLE_RATIO);
                if ( snap.available < MINIMUM_AVAILABLE_ABS || 
                     snap.available < required ) {
                    // if we fell below that, set target higher.
                    required =
                        Math.max((int) (snap.active * IDEAL_AVAILABLE_RATIO),
                                 2 * MINIMUM_AVAILABLE_ABS);
                }
                while ( snap.available < required ) {
                    doLog = true;
                    headLock.push(new QThread(numLock.newThreadNumber()));
                    // getThread could sneak in here,
                    // while available is one too small.  That's ok.
                    countLock.snapFree(snap); // does available++
                    required = 
                        Math.min(required, // required must not increase.
                                 Math.max((int) (snap.active * IDEAL_AVAILABLE_RATIO),
                                          2 * MINIMUM_AVAILABLE_ABS));
                } 
                if (logDEBUG && doLog)
                    Core.logger.log(this,"Thread creation thread past creation loop" + 
                                    ", available: " + snap.available + 
                                    ", required: " + required + 
                                    ", active: " + snap.active,
                                    Logger.DEBUG);
                doLog = false;
                int allowed;
                countLock.snap(snap);
                // Start with a maximal allowed value.
                allowed = Math.max((int)(snap.active * MAXIMUM_AVAILABLE_RATIO),
                                   2 * MINIMUM_AVAILABLE_ABS);
                // Repeatedly remove a thread from the stack and signal it to die.
                // But if all of the threads have meanwhile started jobs, then
                // do nothing, and the loop will terminate with snap.available == 0
                while ( snap.available > allowed ) {
                    doLog = true;
                    if (countLock.snapTake(snap)) {
                        // getThread can sneak in here repeatedly,
                        // but it can never make headLock.pop() be null.
                        headLock.pop().die();
                    }
                    // Let there be up to relative maximum threads
                    // sitting around.  This will reduce thread flux a
                    // lot which should help CPU usage further.  But
                    // don't let it go below 2 * absolute minimum.
                    allowed = 
                        Math.max((int) (snap.active * MAXIMUM_AVAILABLE_RATIO),
                                 Math.max(allowed, // allowed must not decrease.
                                          2 * MINIMUM_AVAILABLE_ABS));
                } 
                if (logDEBUG && doLog)
                    Core.logger.log(this,"Thread creation thread past destruction loop" + 
                                    ", available: " + snap.available + 
                                    ", allowed: " + allowed + 
                                    ", active: " + snap.active,
                                    Logger.DEBUG);
                
            } catch (Throwable e) {
                if (lastEx == null || !lastEx.getClass().equals(e.getClass())) {
                    countLock.snap(snap);
                    Core.logger.log(this, "Exception in QThreadFactory. "
                                    + snap.available + " threads available ," 
                                    + snap.active + " running. "
                                    + "Top: " + headLock.headerThread, 
                                    e, Logger.ERROR);
                }
                lastEx = e;
                try {
                    Thread.sleep(20); // avoid runaway loop.
                } catch (InterruptedException e2) {}
            }
            countLock.waitForNotify(1000); 
        }
    }
    
    /**
     * @return  the target maximum executing jobs.
     *          Caller may use this, together with
     *          activeThreads(), to determine load.
     *          This value is decreased by a thread
     *          which dies on outOfMemory exception.
     */
    public final int maximumThreads() {
        return maxLock.desiredMax();
    }
    
    /**
     * @return  the number of currently executing jobs
     */
    public final int activeThreads() {
        CountSnap snap = new CountSnap();
        countLock.snap(snap);
        return snap.active;
    }
    
    /**
     * @return  the instantaneous number of idle threads
     */
    public final int availableThreads() {
        CountSnap snap = new CountSnap();
        countLock.snap(snap);
        return snap.available;
    }
    
    /**
     * @param job       The job to be executed
     * @return the thread which was popped or created.
     */
    public final Thread getThread(Runnable job) {
        QThread thread = null;
        if (countLock.makeItMyself()) {
            thread = new QThread(numLock.newThreadNumber());
        } else {
            thread = headLock.pop(); // thread cannot be null.
        }
        // synchronize so that getThread can't sneak in between
        // test for job == null and this.wait() in thread.run().
        synchronized (thread) {
            thread.next = null;
            thread.job = job; 
            thread.notify();
        }
        
        countLock.mightNotify();
        return thread;
    }
    
    private final class QThread extends Thread implements PooledThread {
        
        private QThread next; // link for stack of available threads.
        
        private Runnable job = null;
        
        private int jobsDone = 0;
        
        private final Irreversible alive = new Irreversible(true);
        
        public QThread(long num) {
            super(tg, "QThread-"+num);
            super.start();
        }
        
        public final Runnable job() {
            return job;
        }
        
        public final void run() {
            while (alive.state()) {
                synchronized (this) {
                    while (alive.state() && job == null) {
                        // If getThread didn't synchronize (thread),
                        // it could sneak in here and change job and
                        // issue it's notify after the test but before
                        // the wait.
                        try {
                            // There is no need to timeout this wait,
                            // so long as getThread() synchronizes.
                            // getThread() is the only way to get a
                            // thread.  It synchronizes (thread), then
                            // changes thread.job, and only then does
                            // thread.notify().  thread.die() also
                            // synchronizes, then changes
                            // alive.state(), and only then does
                            // thread.notify().
                            this.wait();
                        }
                        catch (InterruptedException e) {}
                    }
                }
                
                if (job != null) {
                    try {
                        Core.diagnostics.occurrenceCounting("jobsExecuted", 1);
                        job.run();
                    } catch (OutOfMemoryError e) {
                        Core.logger.log(this, "Got out of memory error, " +
                                        "decreasing maximum threads by 5 from ~"+
                                        maxLock.desiredMax()+".", 
                                        e, Logger.ERROR);
                        freenet.node.Main.dumpInterestingObjects();
                        maxLock.decrementDesiredMax(5);
                    } catch (Throwable e) {
                        Core.logger.log(this, "Unhandled throw in job: "+e,
                                        e, Logger.ERROR);
                    } finally {
                        jobsDone++;
                        job = null;
                        if (alive.state()) {
                            headLock.push(this); // first push it
                            countLock.free(); // then announce it is there.
                        } // else the thread will exit now.
                    }
                }
            }
            
            Core.diagnostics.occurrenceContinuous("jobsPerQThread", jobsDone);
            
            CountSnap snap = new CountSnap();
            countLock.snap(snap);
            if (logDEBUG)
                Core.logger.log(this, getName() + " ended. " + 
                                snap.available + "threads available." +
                                snap.active + "threads active.",
                                Logger.DEBUG);
        }
        
        /**
         *  Called only from QThreadFactory.run() in this context:
         *  if (countLock.snapTake(snap)) {
         *      headLock.pop().die();
         *  }
         */
        synchronized final void die() {
            if (alive.state()) {
                alive.change();
                this.notify();
            }
        }
    }
    
    private final class MaxLock {
        private int desiredMax;
        synchronized void setDesiredMax(int desiredMax) {
            this.desiredMax = desiredMax;
        }
        synchronized int desiredMax() {
            return this.desiredMax;
        }
        synchronized void decrementDesiredMax(int decr) {
            this.desiredMax = Math.max(this.desiredMax - decr, 0);
        }
    }
    
    private final class NumLock {
        private int threadNumber = 0;
        synchronized int newThreadNumber() {
            return threadNumber++;
        }
    }
    
    private final class HeadLock {
        QThread headerThread = null;
        synchronized void push(QThread thread) {
            thread.next = headerThread;
            headerThread = thread;
        }
        synchronized QThread pop() {
            QThread thread = headerThread;
            headerThread = thread.next;
            return thread;
        }
    }
    
    private final class CountSnap {
        int active = 0;
        int available = 0;
    }
    
    private final class CountLock {
        private int active = 0;
        // available is always either the number of threads on headLock.headerThread,
        // or else one fewer, in the case where it has been decremented but the
        // thread hasn't been removed yet.
        private int available = 0;
        
        // take a snapshot
        synchronized void snap(CountSnap snap) {
            snap.active = active;
            snap.available = available;
        }
        
        // free a thread, then take a snapshot.
        synchronized void snapFree(CountSnap snap) {
            available++; // thread was already pushed onto stack.
            snap.active = active;
            snap.available = available;
        }
        
        // maybe take a thread, then take a snapshot.
        // return true if got a thread.
        synchronized boolean snapTake(CountSnap snap) {
            boolean gotOne = false;
            if (available >= 1) { // There is at least one on stack.
                available--;      // Make sure it stays that way,
                gotOne = true;    // even if getThread sneaks in.
            }
            snap.active = active;
            snap.available = available;
            return gotOne;
        }
        
        // Before calling free, available is one too small
        // compared to the actual state of headLock.headerThread.
        // QThreadFactory.run() or getThread() could sneak in before this.
        synchronized void free() {
            active--;
            available++; // thread is already on the stack
        }
        
        synchronized boolean makeItMyself() {
            boolean makeItMyself = false;
            active++;
            if (available > 0) {
                available--; 
            } else {
                makeItMyself = true;
            }
            return makeItMyself;
        }
        
        synchronized void mightNotify() { 
            if ( ( available < MINIMUM_AVAILABLE_ABS) ||
                 ( available < active * MINIMUM_AVAILABLE_RATIO) || 
                 ( ( available > (3 * MINIMUM_AVAILABLE_ABS)) &&
                   ( available > active * MAXIMUM_AVAILABLE_RATIO))) {
                this.notifyAll();
            }
        } 
        
        synchronized void waitForNotify(int maxDelayMillis) {
            try { 
                this.wait(maxDelayMillis); 
            } catch ( InterruptedException ie ) {}
        }
        
    }
}
