/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.thread;

import freenet.Core;
import freenet.diagnostics.ExternalContinuous;
import freenet.diagnostics.ExternalCounting;
import freenet.support.Logger;

/**
 * A derivative of QThreadFactory that queues jobs when it runs out of threads.
 * All jobs go onto the queue, and are removed by idle threads.
 * 
 * <p>
 * Unnecessary creation of threads is avoided in two ways. First, the thread
 * deletion rate is limited. This avoids the overhead of deleting the thread
 * and then creating it again right away. Second, thread creation is delayed so
 * long as the oldest job on the queue hasn't been there more than a specified
 * time.
 * </p>
 * 
 * @author ejhuff
 */
public final class YThreadFactory implements ThreadFactory {

	/*
	 * Thread creation parameters:
	 * 
	 * If available falls below either minimum, thread creation is allowed.
	 * Only one thread creates threads at a time and that thread waits until
	 * the oldest job on queue is old enough.
	 */

	/** The minimum available (total - active) threads. */
	private static final int threadCreationThreshold = 9;

	/** The minimum available/active ratio. */
	private static final double threadCreationRatio = 0.1;

	/**
	 * The minimum age in millis of the oldest job on queue before a new thread
	 * is actually created.
	 */
	private long tolerableQueueDelay;

	/*
	 * Thread deletion parameters:
	 * 
	 * Both of these maximums must be exceeded for deletion to start:
	 */

	/** The maximum available (total - active) threads. */
	private static final int threadDeletionThreshold = 27;

	/** The maximum available/active ratio. */
	private static final double threadDeletionRatio = 1;

	/** The minimum delay in millis between thread deletions. */
	private static final long threadDeletionDelay = 1000; // 1 per second

	private final ThreadGroup tg;

	private final JobQueue jobQueue = new JobQueue();

	private final boolean logDEBUG;

	private int targetMaxThreads;

	private final String prefix;

	private long absoluteMaxThreads;

	/**
	 * @param tg
	 *            ThreadGroup all created threads will belong to.
	 * @param targetMaxThreads
	 *            Avoid creating more than this number of threads.
	 * @param prefix
	 *            Threads are named as prefix + threadNumber.
	 * @param tolerableQueueDelay
	 *            Don't create new thread until oldest job on queue is older
	 *            than this (milliseconds).
	 * @param absoluteMaxThreads
	 *            As the number of threads approaches this number, the allowed
	 *            queue delay increases. Allowed delay = tQD * aMT / (aMT -
	 *            total) i.e. delay is 10x when 9/10's are used. When aMT -
	 *            total == 0, delay is infinite.
	 */
	public YThreadFactory(
		ThreadGroup tg,
		int targetMaxThreads,
		String prefix,
		int tolerableQueueDelay,
		int absoluteMaxThreads) {
		this.tg = tg;
		this.targetMaxThreads = targetMaxThreads;
		this.prefix = prefix;
		this.tolerableQueueDelay = tolerableQueueDelay;
		this.logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		this.absoluteMaxThreads = absoluteMaxThreads;
		new YThread();
	}

	/**
	 * @param target
	 *            Avoid creating more than this number of threads.
	 * @param delay
	 *            Don't create new thread until oldest job on queue is older
	 *            than this (milliseconds).
	 * @param absMaxThreads
	 *            As the number of threads approaches this number, the allowed
	 *            queue delay increases. Allowed delay = tQD * aMT / (aMT -
	 *            total) i.e. delay is 10x when 9/10's are used. When aMT -
	 *            total == 0, delay is infinite.
	 */
	public final void configUpdater(
		int target,
		int delay,
		int absMaxThreads) {
		synchronized (jobQueue) {
			this.targetMaxThreads = target;
			this.tolerableQueueDelay = delay;
			this.absoluteMaxThreads = absMaxThreads;
		}
	}

	/**
	 * @return the target maximum executing jobs. Caller may use this, together
	 *         with activeThreads(), to determine load. Note that config
	 *         paramater targetMaxThreads defaults to the value of param
	 *         maximumThreads. maximumThreads is used for other purposes in
	 *         addition to controlling the thread factory.
	 */
	public final int maximumThreads() {
		return targetMaxThreads;
	}

	/**
	 * @return the number of currently executing jobs
	 */
	public final int activeThreads() {
		return jobQueue.getActiveThreads();
	}

	/**
	 * @return the instantaneous number of idle threads
	 */
	public final int availableThreads() {
		return jobQueue.getAvailableThreads();
	}

	/**
	 * @param job
	 *            The job to be executed.
	 * @return null
	 */
	public final Thread getThread(Runnable job) {
		jobQueue.enqueue(job);
		return null;
	}

	private final class YThread extends Thread implements PooledThread {

		private int jobsDone = 0;
		private long maxQueueDelay = 0;
		private long sumQueueDelay = 0;
		private Runnable job = null;
		private final ExternalContinuous jobsPerYThread =
			Core.diagnostics.getExternalContinuousVariable("jobsPerYThread");
		private final ExternalContinuous maxQueueDelayThisYThread =
			Core.diagnostics.getExternalContinuousVariable(
				"maxQueueDelayThisYThread");
		private final ExternalContinuous avgQueueDelayThisYThread =
			Core.diagnostics.getExternalContinuousVariable(
				"avgQueueDelayThisYThread");
		private final ExternalContinuous jobQueueDelayAllYThreads =
			Core.diagnostics.getExternalContinuousVariable(
				"jobQueueDelayAllYThreads");
		private final ExternalCounting jobsExecuted =
			Core.diagnostics.getExternalCountingVariable("jobsExecuted");

		/**
		 * Name the thread and start it.
		 */
		public YThread() {
			super(tg, "YThread-unnamed-as-yet");
			super.start();
		}

		public Runnable job() {
			return job;
		}

		/**
		 * Try to create a new YThread(). Only called from run().
		 */
		private void createThread() {
			// Before we can create a thread, we must wait
			// for the deadline
			while (true) {
				long timeBeforeDeadline = jobQueue.timeBeforeDeadline();
				if (timeBeforeDeadline <= 0)
					break;
				try {
					Thread.sleep(timeBeforeDeadline);
				} catch (InterruptedException e) {
				}
			}
			try {
				new YThread(); 	 // new thread sets notCreatingThreads = true.
				return;
			} catch (OutOfMemoryError e) {
				// It's possible to run out of VM when using a large thread stack size
				Core.logger.log(this, "Not enough virtual memory to create new thread.", Logger.ERROR);
			} catch (Throwable e) {
				// This indicates that something is VERY wrong, perhaps we should shut
				// down Fred. The least we can do is log the error.
				Core.logger.log(this, "Unexpected exeption when creating thread.", e, Logger.ERROR);
			}
			
			try {
				Thread.sleep(500); // Try to avoid looping on errors 
			} catch (InterruptedException e) { }						
			jobQueue.notCreatingThreads = true; // Enable thread creation
		}			

		/**
		 * @param result JobQueueResult of the thread (only used in debug log)
		 * Final accounting for an exiting thread. Only called from run() before 
		 * the thread will exit.
		 */
		private void threadEndAccounting(JobQueueResult result) {
			// An unneeded thread exits.
			double avgQueueDelay = sumQueueDelay;
			jobsPerYThread.count(jobsDone);
			if (jobsDone > 0) {
				avgQueueDelay /= jobsDone;
				maxQueueDelayThisYThread.count(maxQueueDelay);
				avgQueueDelayThisYThread.count(avgQueueDelay);
				//same
			}
			if (logDEBUG)
				Core.logger.log(
								this,
								Thread.currentThread().getName()
								+ " ended. "
								+ result.available
								+ " threads available. "
								+ result.active
								+ " threads active. "
								+ jobsDone
								+ " jobs done. "
								+ maxQueueDelay
								+ " max queue delay. "
								+ avgQueueDelay
								+ " avg queue delay.",
								Logger.DEBUG);
			return;						
		}

		/**
		 * while(true): retrieve new jobs and execute them.
		 */
		public final void run() {
			jobQueue.newThreadStarting();
			JobQueueResult result = new JobQueueResult();
			while (true) {
				jobQueue.dequeue(result);
				if (result.job == null) {
					if (result.exit) { // This thread is selected to exit
						threadEndAccounting(result);
						return;
					}
					// (result.job == null && ! result.exit) means we
					// _must_ create and start one new thread, 
					createThread();
				} else { // (result.job != null) means we have a job to do.
					job = result.job;
					sumQueueDelay += result.queueDelay;
					if (maxQueueDelay < result.queueDelay) {
						maxQueueDelay = result.queueDelay;
					}
					try {
						jobQueueDelayAllYThreads.count(result.queueDelay);
						jobsExecuted.count(1);
						job.run();
					} catch (Throwable e) {
						Core.logger.log(
							this,
							"Unhandled exception " + e + " in job " + job,
							e,
							Logger.ERROR);
						freenet.node.Main.dumpInterestingObjects();
					} finally {
						jobsDone++;
						job = null;
					}
				}
			} /* while */
		}
	} /* class YThread */

	private final class JobQueueSnapshot {
		int active = 0;
		int available = 0;
	}

	private final class JobQueueResult {
		Runnable job = null;
		boolean exit = false;
		int active = 0;
		int available = 0;
		long queueDelay = 0;
	}

	private final class JobQueue {
		// information about threads
		private int total = 0;
		private volatile int active = 0;
		private volatile int available = 0; // total == active + available
		private int threadNumber = 0;
		private long nextThreadDeletionTime = 0;
		private boolean notCreatingThreads = true;

		// information about the job queue.
		private final JobQueueItem head = new JobQueueItem();
		private JobQueueItem tail = head;
		private JobQueueItem free = null;     // Cache FREELIST_MAX objects for reuse 
		private int freeListLength = 0;
		// Magic value. This should perhaps tune itself according to the load
		private static final int FREELIST_MAX = 10000; 

		private final class JobQueueItem {
			JobQueueItem next = null;
			Runnable job = null;
			long enqueueTime = 0;
		}

		
		/**
		 * A new thread has started. Account for it. Called only from YThread.run().
		 */
		private synchronized void newThreadStarting() {
			total++;
			active++; // active + available == total
			Thread.currentThread().setName(prefix + (threadNumber++));
			notCreatingThreads = true;
		}

		// Returns the number of active threads as well as the number of
		// threads idling
		private synchronized void snap(JobQueueSnapshot snap) {
			snap.active = active;
			snap.available = available;
		}

		private int getActiveThreads() {
			return active;
		}

		private int getAvailableThreads() {
			return available;
		}

		/**
		 * @param job The job that is to be queued for execution.
		 *
		 * Add a new job in the jobqueue for the threadpool to handle. Called only from getThread().
		 */
		private synchronized void enqueue(Runnable job) {
			if (free == null) {
				free = new JobQueueItem();
				freeListLength++;
			}
			JobQueueItem mine = free;
			free = free.next;
			freeListLength--;
			mine.next = null;
			mine.job = job;
			mine.enqueueTime = System.currentTimeMillis();
			tail.next = mine;
			tail = mine;
			this.notify(); // wake a thread
		}

		/**
		 * @return ms to wait before creating a new thread
		 *
		 *  Called only from YThread.run(). 
		 */
		private synchronized long timeBeforeDeadline() {
			long allowedThreads = absoluteMaxThreads - total;
			if (head.next == null || allowedThreads <= 0)
				return tolerableQueueDelay;
			return Math.min(
				tolerableQueueDelay,
				head.next.enqueueTime
					- System.currentTimeMillis()
					+ tolerableQueueDelay * absoluteMaxThreads / allowedThreads);
		}

		/**
		 * @param result The threads JobQueueResult
		 * @param delete When there is nothing to do, delete is set to true.
		 * @return action taken. true = we have a new work for the thread, false = continue
		 * 
		 * Called only from dequeue(..)
		 * Take care of thread creation and deletion jobs. When requested to delete jobs,
		 * we also do the job waiting (and return false if there are new jobs).
		 */
		private synchronized boolean threadAccounting(JobQueueResult result, boolean delete) {
		 
			if (delete) {
				long now = System.currentTimeMillis();
				// Check if we should delete this thread (due to excess idle threads)
				if (available > threadDeletionThreshold
					&& available > active * threadDeletionRatio) {
					long deleteDelay = nextThreadDeletionTime - now;
					if (deleteDelay > 0)
						try { 
							this.wait(deleteDelay);
						} catch (InterruptedException e) {
							return false; // We got interrupted, there are jobs to do
						}
					total--;
					available--; // active + available == total
					nextThreadDeletionTime = now + threadDeletionDelay;
					result.active = active;
					result.available = available;
					result.exit = true;
					result.job = null;
					result.queueDelay = 0;
					this.notify();
					return true; // Let the thread delete itself
				} else        // Deletion criteria not met, wait for next job
					try {
						this.wait(); // Wait for job
					} catch (InterruptedException e) { }

			} else if ( ( (available < threadCreationThreshold)
						  || (available < active * threadCreationRatio) )
						&& notCreatingThreads ) {   // Only one thread can create another thread at a time
				// then make one new thread.
				notCreatingThreads = false;
				active++;
				available--; // active + available == total
				result.active = active;
				result.available = available;
				result.exit = false;
				result.job = null;
				result.queueDelay = 0;
				this.notify(); 	// wake another thread in case there is work
				return true; // Let the thread create a new thread
			}
			return false;  /* no job change (delete / create) to this thread */
		}

		/**
		 * called only from YThread.run() to retrieve a new job
		 */
		private synchronized void dequeue(JobQueueResult result) {
			active--;
			available++; // active + available == total
			while (true) {
				if (threadAccounting(result, false) ) // => Should this thread create a new thread
					return;
				if (head.next != null) { // There are jobs to do
					long now = System.currentTimeMillis();
					active++;
					available--; // active + available == total
					JobQueueItem mine = head.next;
					head.next = mine.next;
					if (head.next == null)
						tail = head;
					result.job = mine.job;
					result.queueDelay = now - mine.enqueueTime;
					mine.job = null;
					mine.enqueueTime = 0;
					mine.next = free;
					free = mine;
					freeListLength++;
					while (freeListLength > FREELIST_MAX) {
						mine = free;
						free = free.next;
						freeListLength--;
						mine.next = null;
						// mine will be garbage collected later
					}
					result.active = active;
					result.available = available;
					result.exit = false;
					if (head.next != null)
						this.notify(); // wake the next thread if there are jobs to do
					return;
				}

				// No jobs left on queue to run.
				// If thread deletion is allowed at this time
				// and if there are too many threads, delete one.
				if (threadAccounting(result, true) )// => Should this thread exit
					return;
			} /* while */
		}
	} /* class JobQueue */
} /* class YThreadFactory */
