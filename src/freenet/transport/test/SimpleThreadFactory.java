/*
 * Created on Feb 17, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.transport.test;

import freenet.thread.ThreadFactory;


public class SimpleThreadFactory implements ThreadFactory {
	public void configUpdater(int targetMaxThreads, int tolerableQueueDelay, int absoluteMaxThreads) {
	}
	public int maximumThreads() {
		return 0;
	}
	public int availableThreads() {
		return 0;
	}
	public int activeThreads() {
		return 0;
	}
	public Thread getThread(Runnable r) {
		r.run();
		return Thread.currentThread();
	}
}