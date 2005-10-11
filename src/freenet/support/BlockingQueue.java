package freenet.support;
import java.util.LinkedList;

import freenet.Core;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Implements a thread-safe Blocking Queue. A BlockingQueue
 * is a FIFO (First In First Out) buffer
 * with the property that calls to dequeue() on an empty queue will block
 * until an element becomes available to dequeue.
 *
 * @author Scott G. Miller <scgmille@indiana.edu>
 */
public final class BlockingQueue {
    protected final LinkedList queue;

    /**
     * Construct an empty BlockingQueue.
     *
     *
     */
    
    public BlockingQueue() {
	queue=new LinkedList();
    }
    
    volatile long enqueuedAt = 0;
    
    /**
     * Queues an object onto this BlockingQueue.
     *
     * @param o the object to enqueue
     */
    public void enqueue(Object o) {
    boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	enqueuedAt = System.currentTimeMillis();
	if(logDEBUG)
		Core.logger.log(this, "enqueueing "+o, Logger.DEBUG);
	synchronized(queue) {
		if(logDEBUG)
	    	Core.logger.log(this, "enqueueing "+o+" (locked)",
			    Logger.DEBUG);
	    queue.add(o);
	    queue.notifyAll();
	    if(logDEBUG)
	    	Core.logger.log(this, "enqueued "+o+" (locked)"+
			    " to "+queue.getClass()+":"+queue, Logger.DEBUG);
	}
	if(logDEBUG)
		Core.logger.log(this, "enqueued "+o+" (locked)",
			Logger.DEBUG);
    }
    
    public Object dequeue() throws InterruptedException {
		return dequeue(-1);
    }
    
    long dequeuedCounted = 0;
    
    /**
     * Dequeues an object from this BlockingQueue.  This method will return the
     * next element in this BlockingQueue, or block until one is available.
     *
     * @param millis maximum time in milliseconds to wait. -1 means wait forever
     * if necessary.
     * @return the object on the top of this BlockingQueue
     * @throws InterruptedException if this thread is blocked in this method 
     * and something interrupts this thread.
     */
    public Object dequeue(int millis) throws InterruptedException {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		boolean logMINOR = Core.logger.shouldLog(Logger.MINOR,this);
        synchronized(queue) {
            //if (queue.isEmpty()) {
            //    synchronized(queue) {
            //        queue.wait();
            //    }
            //    return dequeue();
            //} else {
            //    Object tmp=queue.firstElement();
            //    queue.removeElementAt(0);
            //    return tmp;
            //}
	    if(millis == -1) {
		dequeuedCounted++;
		final int timeout = 200;
		while (queue.isEmpty()) {
			if(logDEBUG) Core.logger.log(this, "Waiting... "+queue.getClass()+":"+
				    queue, Logger.DEBUG);
		    long now = System.currentTimeMillis();
		    queue.wait(timeout); // wicked evil JVMs! (1.4.2 especially) - we have seen some interesting freezes here...
		    long andnow = System.currentTimeMillis();
		    if (andnow - now >= (timeout) && !queue.isEmpty()) {
			long x = andnow - enqueuedAt;

			if(queue.size() > 5 && x > 2000 && logMINOR) {
				String err = "Waited more than "+timeout+"ms to dequeue, "+queue.size()+" in queue, "+x+" millis since enqueued last item, "+dequeuedCounted+" maximum waits so far - could indicate serious JVM bug. Please report to support@freenetproject.org along with JVM and OS/kernel.";
			    Core.logger.log(this, err, Logger.MINOR);
			    System.err.println(err);
			} else {
				if(logDEBUG) {
					String err = "Waited more than "+timeout+"ms to dequeue, "+queue.size()+" in queue, "+x+" millis since enqueued last item, "+dequeuedCounted+" maximum waits so far - could indicate serious JVM bug. Please report to support@freenetproject.org along with JVM and OS/kernel.";
			    	Core.logger.log(this, err, Logger.DEBUG);
				}
			}
		    }
		}
			return innerDequeue();
	    }else {
			if (queue.isEmpty() && millis > 0)
		    	queue.wait(millis);
			if (queue.isEmpty())
		    	return null;
			else return innerDequeue();
	    }
        }
    }
    //Returns a snapshot of the current contents of the queue
    public Object[] toArray(){
		synchronized(queue) {
			return queue.toArray();
		}
    }
    // override in subclasses
    protected Object innerDequeue() {
	Object tmp = queue.removeFirst();
	return tmp;
    }
    
    /**
     * Returns the empty status of this BlockingQueue.
     *
     * @return true if this BlockingQueue is empty, false otherwise.
     */
    
    public boolean isEmpty() {
	return queue.isEmpty();
    }
    
    /**
     * Returns the number of objects in this BlockingQueue.
     *
     * @return the number of objects in this BlockingQueue.
     */
    
    public int size() {
	return queue.size();
    }
}

