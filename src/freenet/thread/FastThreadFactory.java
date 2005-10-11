package freenet.thread;

import freenet.Core;
import freenet.support.Logger;

import java.util.*;

/**
 * The FastThreadFactory creates a new thread if none are available
 * in the current pool, otherwise it reuses an old thread.  Once
 * allocated threads are never destroyed.
 *
 * @author Pascal
 * @author I stole much of this code from tavin
 */
public final class FastThreadFactory implements ThreadFactory {

    private final ThreadGroup tg;

    private int total;
    private final int desiredMax;
    private final Stack freeThreads = new Stack();

    /**
     * @param tg     ThreadGroup all created threads will belong to
     */
    public FastThreadFactory(ThreadGroup tg, int desiredMax) {
        this.tg = tg;
        this.desiredMax = desiredMax;
    }

    public final void configUpdater(
        int targetMaxThreads,
        int tolerableQueueDelay,
        int absoluteMaxThreads) {
    }

    public final int maximumThreads() {
        return desiredMax;
    }


    /**
     * @return  the number of currently executing jobs
     */
    public final int activeThreads() {
        return total-freeThreads.size();
    }

    /**
     * @return  the instantaneous number of idle threads
     */
    public final int availableThreads() {
        return freeThreads.size();
    }

    /**
     * @param job       The job to be executed
     */
    public final Thread getThread(Runnable job) {
        try {
            FThread thread = (FThread) freeThreads.pop();
            thread.job = job;
            thread.start();
        return thread;
        } catch (EmptyStackException e) {
            return new FThread(threadID(), job);
        }
    }
    
    private synchronized final int threadID() {
        return total++;
    }

    private final class FThread extends Thread implements PooledThread {

        private Runnable job;
    
        FThread(int num, Runnable job) {
            super(tg, "FThread-" + num);
            this.job = job;
            super.start();
        }

        public final Runnable job() {
            return job;
        }

        public final synchronized void start() {
            this.notify();
        }

        public final synchronized void waitForJob() {
            while (job == null) {
                try {
                    this.wait(1000);
                }
                catch (InterruptedException e) {}
            }
        }

        public final void run() {
            while (true) {
                try {
                    job.run();
                }
                catch (Throwable e) {
                    Core.logger.log(this, "Unhandled throw in job",
                                    e, Logger.ERROR);
                }
                Core.diagnostics.occurrenceCounting("jobsExecuted", 1);
                job = null;
                freeThreads.push(this);
                waitForJob();
            }
        }
        
        protected final void finalize() {
            String message = "";
            if (freeThreads.remove(this)) message = "Free thread ";
            message += this.getName() + " died ";
            if (job == null) message += "without a job!";
                else message += "in " + job.toString();
            Core.logger.log(this, message, Logger.ERROR);
        }
    }
}


