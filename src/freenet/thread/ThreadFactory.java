/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.thread;

/**
 * This is more convenient than using a ThreadManager for some purposes.
 * It is generally assumed that thread pooling is taking place
 * @author tavin
 */
public interface ThreadFactory {

	/**
	 * @param targetMaxThreads    a config parameter
	 * @param tolerableQueueDelay another config parameter
	 * @param absoluteMaxThreads  yet another config parameter
	 */
	void configUpdater(
		int targetMaxThreads,
		int tolerableQueueDelay,
		int absoluteMaxThreads);

    /**
     * @return  the desired maximum number of threads for the factory.
     * It should be able to overflow, but that is not desired.
     */
    int maximumThreads();

    /**
     * @return  the number of available threads at this instant
     */
    int availableThreads();

    /**
     * @return  the number of currently executing jobs
     */
    int activeThreads();

    /**
     * @param r      the job to run
     * @return  the thread executing the job, or null if the
     *          job was queued.
     */
    Thread getThread(Runnable r);

}


