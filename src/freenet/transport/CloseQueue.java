/*
 * Created on Mar 29, 2004
 * Most recent author: raoksane.
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.transport;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import freenet.Connection;
import freenet.diagnostics.ExternalContinuous;
import freenet.support.Irreversible;
import freenet.support.Logger;

class CloseQueue {
	protected final Logger logger;
	private final LinkedList closeQueue = new LinkedList();
	private final LinkedList preCloseQueue = new LinkedList();
	private final Hashtable closeUniqueness = new Hashtable(512); //FIXME:hardcoded
	private volatile boolean stopProcessingRequested = false;
	protected final ExternalContinuous closePairLifetimeCallback;
	
	CloseQueue(Logger logger, ExternalContinuous closePairLifetime) {
		this.logger = logger;
		this.closePairLifetimeCallback = closePairLifetime;
	}
	public final int closeUniquenessLength() {
		return closeUniqueness.size();
	}
	public final int closeQueueLength() {
		return closeQueue.size();
	}

	/**
	 * Enqueue the CloseTriplet for closing
	 */
	void enqueue(CloseTriplet cp) {
		if(cp.attachment != null) //Notify callback that this item is now queued for closing
			cp.attachment.queuedClose();
		synchronized (closeQueue) {
			CloseQueueItem oldCP = (CloseQueueItem) closeUniqueness.get(cp.conn);
			if (oldCP == null) {
				oldCP = new CloseQueueItem(cp);
				preCloseQueue.addLast(oldCP);
				closeUniqueness.put(cp.conn, oldCP);
			} else {
				oldCP.merge(cp);
				/**
				 * It happens sometimes For example, a given channel will be connected to an 
				 * NIOInputStream during negotiation and then switch to a MuxConnectionHandler. 
				 * If it gets closed at the wrong time, we can maybe have it queued twice.
				 * 2004-04-13: Is this still true?
				 */
			}
		} /* synchronized */
	}

	/**
	 * Handle the process of moving close-items from the pre-CloseQueue to the actual closeQueue. This method should
	 * be called by the selector thread and allows it to control when actual channels are closed.
	 * 
	 * Now this cancel()'s the keys and doesn't notify the close thread --zab
	 */
	public void handleCloseQueuePipe() {
		// Always when handling the lists, closeQueue lock must be
		// held
		LinkedList workList = new LinkedList();
		synchronized (closeQueue) {
			if (preCloseQueue.isEmpty())
				return;
			//Remove all entries from the closeQueue into a local queue
			//in order to prevent other threads from beeing blocked on
			// closeQueue
			//during the processing below (locking optimization)
			workList.addAll(preCloseQueue);
			preCloseQueue.clear();
		} /* synchronized */

		// No need to hold locks, sinice we only use the workList 
		// Cancel the keys that have been queued for closing 
		// on all "known" selector threads
		Iterator it = workList.iterator();
		CloseQueueItem current;
		while (it.hasNext()) {
			current = (CloseQueueItem) it.next();
			if (current.sc != null) {
				SelectionKey k = null;
				if (tcpConnection.getRSL() instanceof AbstractSelectorLoop)
					k = ((AbstractSelectorLoop) tcpConnection.getRSL()).keyFor(current.sc);
				else
					logger.log(this, "tcpConnection.RSL nor an AbstractSelectorLoop!", 
						   new Exception("debug"), 
						   Logger.ERROR);
				if (k != null) {
					k.cancel();
				} else {
					if (tcpConnection.getWSL() instanceof AbstractSelectorLoop)
						k = ((AbstractSelectorLoop) tcpConnection.getWSL()).keyFor(current.sc);
					else
						logger.log(this, 
							   "tcpConnection.WSL nor an AbstractSelectorLoop!", 
							   new Exception("debug"), 
							   Logger.ERROR);
					if (k != null) {
						k.cancel();
					}
				}
			}
		}
		if (workList.isEmpty() ) // Avoid taking lock
			return;

		synchronized (closeQueue) {
			closeQueue.addAll(workList);
		} /* synchronized */
		notifyCloseThread(); /* wake up processQueue */
	}
	
	/**
	 * Wake up processQueue to handle the new items in the queue.
	 */
	void notifyCloseThread() {
		synchronized (closeQueue) {
			if (closeQueue.size() > 0)
				closeQueue.notifyAll();
		}
	}

	/**
	 * These are what is stored in the close queue. Methods here MUST not be called without 
	 * the external closeQueue lock.
	 */
	protected final class CloseQueueItem {
		public final Connection conn;
		private final CloseTriplet firstCP;
		private CloseTriplet secondCP;
		private LinkedList otherCPs; //Use a list as fallback if we recieve more than two CloseTriplets

		public final SocketChannel sc;
		private final Irreversible closed = new Irreversible(false);
		public CloseQueueItem(CloseTriplet cp) {
			firstCP = cp;
			this.conn = cp.conn;
			this.sc = cp.sc;
		}
		public String toString() {
			return conn.toString() + ":" + ((firstCP == null) ? "(null)" : secondCP.toString()) + ":" + sc;
		}

		/**
		 * Merges another CloseTriplet into this ClosequeueItem.
		 * 
		 * @param cp,
		 *            another CloseTriplet to merge into this CloseQueueItem
		 *
		 * Called from enqueue(), with locks held.
		 */
		private void merge(CloseTriplet cp) {
			if (closed.state()) //Not good, notify caller.. he will probably never get his desired callback
				throw new IllegalStateException("Already closed, close-notification will not happen");
			if (cp == null) //Tell the coder that he is dong something wrong so that he has a chance of fixing his code!
				throw new NullPointerException("Cannot add null attachment");
			if (cp.conn != this.conn) //Tell the coder that he is dong something wrong so that he has a chance of fixing his code!
				throw new IllegalArgumentException("Cannot merge CloseTriplet with different connection");
			if (cp.sc != this.sc) //Tell the coder that he is dong something wrong so that he has a chance of fixing his code!
				throw new IllegalArgumentException("Cannot merge CloseTriplet with different SocketChannel");
			if(secondCP == null){
				secondCP = cp;
				return;
			}
			if(otherCPs == null)
				otherCPs = new LinkedList();
			otherCPs.add(cp);
			
		}
		/**
		 * Close all attachments. Can only be called once. The caller must ensure
		 * that it has exclusive access to the object.
		 */
		private void closedAttachments() {
			if (closed.state()) //Not good, notify caller.. this situation means that his callback will not get notified..
				throw new IllegalStateException("Already closed, close-notification cannot be done twice");
			closed.change();
			if (firstCP.attachment != null)
				firstCP.attachment.closed();
			if (secondCP != null && secondCP.attachment != null)
				secondCP.attachment.closed();
			if(otherCPs != null)
			{
				Iterator it = otherCPs.iterator();
				while(it.hasNext()){
					CloseTriplet next = (CloseTriplet)it.next();
					if(next.attachment != null)
						next.attachment.closed();
				}
			}
		}
		
		/**
		 * Reports the lifetimes of the underlying closetriplets to the supplied continuous.
		 * Called from processQueue with locks held. 
		 */
		private void reportLifeTimes(ExternalContinuous closePairLifetimeCallback) {
			double d = (System.currentTimeMillis() - firstCP.timeStamp);
			closePairLifetimeCallback.count(d);
			if (secondCP != null) {
				d = (System.currentTimeMillis() - secondCP.timeStamp);
				closePairLifetimeCallback.count(d);
			}
			if(otherCPs != null) {
				Iterator it = otherCPs.iterator();
				while(it.hasNext()){
					CloseTriplet next = (CloseTriplet)it.next();
					d = (System.currentTimeMillis() - next.timeStamp);
					closePairLifetimeCallback.count(d);
				}
			}
		}
	} /* class CloseQueueItem */
	

	/**
	 * Notify processQueue that it's time to exit. 
	 */
	public void stopProcessQueue() {
		stopProcessingRequested = true;
		synchronized (closeQueue) {
			closeQueue.notifyAll(); // FIXME: Is it possible that there are more than one waiting?
		}
	}
	
	/**
	 *
	 * Start processing the close-queue. Will not return until stopProcessQueue() is called.
	 * When any work is done, the closeQueue lock is held, only when sleeping and handling 
	 * exeptions the lock is released.
	 */
	public void processQueue() {
		stopProcessingRequested = false;
		CloseQueueItem current = null;
		while (true) {
			try {
				Connection c = null;
				// Drop lock on exception
				// We remove the CloseQueueItem frrom the closeQueue and closeUniquness holding the lock
				synchronized (closeQueue) {
					while (closeQueue.isEmpty() || stopProcessingRequested) {
						if (stopProcessingRequested)
							return;
						try {
							//Wait until someone notifies us
							closeQueue.wait();
						} catch (InterruptedException e) {
						}
					} 
					current = (CloseQueueItem) closeQueue.removeFirst();
					if (current != null)
						c = current.conn;
					else {
						logger.log(this, "removeFirst() failed?", Logger.ERROR);
						continue;
					}
					Object o = closeUniqueness.remove(c);
					if(o == null) // This would be bad
						logger.log(this, "Huh, no uniqueness element for CloseQueueItem. "
							   +"Report this to devl@freenetproject.org: "+current, 
							   Logger.ERROR);
				} /* synchronized */

				try {
					c.close(true);
				} catch (Throwable t) {
					logger.log(this, "Caught throwable when closing " + c, t, Logger.ERROR);
				} finally {
					// No need to take any locks, current held exclusively
					if (closePairLifetimeCallback != null)
						current.reportLifeTimes(closePairLifetimeCallback); 
					current.closedAttachments();
				}

				current = null;

			} catch (OutOfMemoryError e) {
				System.gc();
				System.runFinalization();
				System.gc();
				System.runFinalization();
				try {
					logger.log(this, "Ran emergency GC in connection close Thread", Logger.ERROR);
				} catch (Throwable any) {
				    // We're stuffed, Do nothing.
				}
			} catch (Throwable t) {
				try {
					logger.log(this, "Caught Throwable during connection closing!", t, Logger.ERROR);
					// FIXME
					// System.err, System.out may be files on disk
					// PrintWriter on a disk file behaviour is to block
					// until more disk space is available (tested, Sun
					// 1.4.1) so don't use them!
				} catch (Throwable anything) {
				    // We're stuffed. Do nothing.
				}
			} /* end catch */
		} /* while */
	}

	/**
	 * these CloseQueue. are supplied to the queueClose() for 
	 * later close-processing
	 */
	static class CloseTriplet {
		public final Connection conn;
		private final NIOCallback attachment;
		public final SocketChannel sc;
		
		private final long timeStamp; //debugging. Can be removed eventually
		private final Exception whenCreated = new Exception(); //TODO: Remove!

		public CloseTriplet(
			Connection conn,
			NIOCallback attachment,
			SocketChannel sc) {
			this.conn = conn;
			this.attachment = attachment;
			this.sc = sc;
			timeStamp = System.currentTimeMillis();
		}
	}
}
