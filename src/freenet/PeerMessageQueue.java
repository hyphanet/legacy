/*
 * Created on Feb 9, 2004
 *
 */
package freenet;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.support.BlockingQueue;
import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.Logger;

class PeerMessageQueue {
	
	//A class that handles asyncronous message expiration notification
	private static class MessageExpirer implements Runnable{
		BlockingQueue queue = new BlockingQueue();
		MessageExpirer(){
			Thread t = new Thread(this);
			t.setName("PeerMessage expiration thread");
			t.setDaemon(true);
			t.start();
		}
		void enqueue(PeerPacketMessage message){
			queue.enqueue(message);
		}

		public void run() {
			while(true){
				try {
					notifyExpiredMessage((PeerPacketMessage)queue.dequeue());
				} catch (InterruptedException e) {
					//Ignore
				}
			}
		}
		
		private void notifyExpiredMessage(PeerPacketMessage expiredMessage) {
		    try {
			SendFailedException sfe = new SendFailedException(null, expiredMessage.getIdentity(), "Message timed out on queue", false);
			expiredMessage.notifyFailure(sfe);
		    } catch (Throwable t) {
		        String asString = "(failed)";
		        try {
		            asString = expiredMessage.toString();
		        } catch (Throwable t1) {
		            asString = "(" + t1.toString() + ")";
		        }
	            Core.logger.log(this, "Caught "+t+" expiring "+asString, t, Logger.ERROR);
		    }
		}
	}
	
	static class PeerMessageQueueElement extends DoublyLinkedListImpl.Item {
		int priority;
		private final LinkedList messages=new LinkedList();
		
		PeerMessageQueueElement(int prio) {
			this.priority = prio;
		}
		
		public boolean equals(Object o) {
			if(o instanceof PeerMessageQueueElement) {
				PeerMessageQueueElement pq = (PeerMessageQueueElement)o;
				return (pq.priority == priority);
			} else return false;
		}
		
		/**
		 * @param presentation
		 * @return
		 */
		public long queuedBytes(Presentation presentation) {
			long ret = 0;
			Iterator i = messages.listIterator();
			while(i.hasNext()) {
				PeerPacketMessage msg =
					(PeerPacketMessage) (i.next());
				msg.resolve(presentation, true);
				ret += msg.getLength();
			}
			return ret;
		}
		
		/**
		 * @param pm
		 * @param cb
		 * @return
		 */
		public PeerPacketMessage remove(PeerPacketMessage pm, MessageSendCallback cb) {
			Iterator i = messages.listIterator();
			while(i.hasNext()) {
				PeerPacketMessage msg =
					(PeerPacketMessage) (i.next());
				if((pm != null && msg == pm) || 
				   (cb != null && msg instanceof HighLevelPeerPacketMessage &&
	                            ((HighLevelPeerPacketMessage)msg).cb == cb)) {
					i.remove();
					return msg;
				}
			}
			return null;
		}
		
		//Expires all messages that have timed out
		//Returns the number of messages that where cancelled
		public int expireAll() {
			long now = System.currentTimeMillis();
			int count = 0;
			Iterator i = messages.listIterator();
			while(i.hasNext()) {
				PeerPacketMessage msg =
					(PeerPacketMessage) (i.next());
				long expiry = msg.expiryTime();
				if(expiry > 0 && expiry < now) {
				    if(Core.logger.shouldLog(Logger.DEBUG, this))
				        Core.logger.log(this, "Queueing for expire: "+
				                msg, Logger.DEBUG);
					expirer.enqueue(msg);
					i.remove();
					count++;
				}
			}
			return count;
		}

        /**
         * @return true if any messages have an MRI
         */
        public boolean messagesWithMRI() {
			Iterator i = messages.listIterator();
			while(i.hasNext()) {
				PeerPacketMessage msg =
					(PeerPacketMessage) (i.next());
				if(msg.hasMRI()) return true;
			}
            return false;
        }
	} // PeerMessageQueueElement

	/** 
	 * List of PeerMessageQueueElement's, First = highest prio 
	 * = lowest .priority value, Last = lowest prio.
	 */
	private final DoublyLinkedList elements =new DoublyLinkedListImpl();
	private final Hashtable elementsByPriority = new Hashtable();
	private int messagesQueued=0;
	private static final MessageExpirer expirer = new MessageExpirer();
	
	private synchronized PeerPacketMessage removeFirst() {
		PeerMessageQueueElement pmq = 
			(PeerMessageQueueElement)elements.head();
		while(pmq != null && pmq.messages.isEmpty()) {
			pmq = (PeerMessageQueueElement)elements.next(pmq);
		}
		if(pmq == null) return null;
		if(pmq.messages.isEmpty()) return null;
		messagesQueued--;
		return (PeerPacketMessage)pmq.messages.removeFirst();
	}
	
	synchronized void addLast(PeerPacketMessage msg) {
		PeerMessageQueueElement pmq = 
			makeQueue(msg.getPriorityDelta());
		messagesQueued++;
		pmq.messages.addLast(msg);
	}
	synchronized void failAllMessages(SendFailedException reason) {
		// FIXME: should probably recurse, is more robust
		// FIXME: Do we ever verify messagesQueued?
		while (!isEmpty()) {
			PeerPacketMessage m = removeFirst();
			if (m == null)
				break;
			m.notifyFailure(reason);
		}
	}
    
	private synchronized void addFirst(PeerPacketMessage msg) {
		PeerMessageQueueElement pmq = 
			makeQueue(msg.getPriorityDelta());
		messagesQueued++;
		pmq.messages.addFirst(msg);
	}
	
	private synchronized PeerMessageQueueElement makeQueue(int prio) {
		Integer i = new Integer(prio);
		PeerMessageQueueElement e =
			(PeerMessageQueueElement) (elementsByPriority.get(i));
		if(e != null) return e;
		PeerMessageQueueElement ret =
			new PeerMessageQueueElement(prio);
		elementsByPriority.put(i, ret);
		// Search the list for a place to insert it
		e = (PeerMessageQueueElement) (elements.head());
		int prevPrio = 0;
		while(e != null) {
			int p = e.priority;
			if (e.priority > prio) {
				elements.insertPrev(e, ret);
				return ret;
			}
			prevPrio = p;
			e = (PeerMessageQueueElement) (elements.next(e));
			if(e != null && prevPrio >= e.priority) {
				throw new IllegalStateException("Inconsistent list");
			}
		}
		elements.push(ret);
		return ret;
	}

	/**
	 * @param presentation
	 * @return
	 */
	public synchronized long queuedBytes(Presentation presentation) {
		long ret = 0;
		PeerMessageQueueElement e = 
			(PeerMessageQueueElement) (elements.head());
		while(e != null) {
			ret += e.queuedBytes(presentation);
			e = (PeerMessageQueueElement) elements.next(e);
		}
		return ret;
	}
	
	//Expires all messages that have timed out
	public synchronized int expireAll() {
		int count = 0;
		PeerMessageQueueElement e = 
			(PeerMessageQueueElement) (elements.head());
		while(e != null) {
			count += e.expireAll();
			e = (PeerMessageQueueElement) elements.next(e);
		}
		messagesQueued -= count;
		return count;
	}
	
	/**
	 * @return the total number of messages on the queue
	 */
	public int messageCount() {
		return messagesQueued;
	}
	
	/**
	 * @return
	 */
	public boolean isEmpty() {
		return messagesQueued == 0;
	}
	
	private synchronized int countMessages(){
		int count =0;
		PeerMessageQueueElement e = 
			(PeerMessageQueueElement) elements.head();
		while(e != null) {
			count += e.messages.size();
			e = (PeerMessageQueueElement) elements.next(e);
		}
		return count;
	}
	
	/**
	 * @param pm
	 * @param cb
	 * @return
	 */
	public synchronized PeerPacketMessage remove(PeerPacketMessage pm, 
						     MessageSendCallback cb) {
		// FIXME: speed up - hashtable?
		// FIXME: get rid of the MessageSendCallback form...
		if(pm != null) {
			int prio = pm.getPriorityDelta();
			PeerMessageQueueElement pmq = 
				(PeerMessageQueueElement) (elementsByPriority.get(new Integer(prio)));
			if(pmq == null) return null;
			PeerPacketMessage msg = pmq.remove(pm,cb);
			if(msg != null){
				messagesQueued--;
			}
			return msg;
		} else {
			// Search the lot :(
			// FIXME: how common is this? Hashtable?
			PeerMessageQueueElement e =
				(PeerMessageQueueElement) (elements.head());
			while(e != null) {
				
				PeerPacketMessage msg = e.remove(pm,cb);
				if(msg != null){
					messagesQueued--;
					return msg;
				}
				e = (PeerMessageQueueElement)(elements.next(e));
			}
			return null;
		}
	}
	public MessagePicker getPicker(Presentation pres,Identity id, boolean includeRequests){
		return new MessagePicker(pres,id,includeRequests);
	}
	private long lastPickTime = System.currentTimeMillis();

	public class MessagePicker{
		public /*static*/ class PickResult{
			//A list containing picked messages
			LinkedList pickedMessages = new LinkedList();
			
			//The number of trailerchunk bytes that is attached to the messages in the list above
			int trailerChunkBytes =0;
		}
		
		private final Presentation pres; //Presentation to use for calculating message sizes
		private final Identity id;
		private final boolean includeRequests;
		MessagePicker(Presentation pres,Identity id, boolean includeRequests){
			this.pres = pres;
			this.id = id;
			this.includeRequests = includeRequests;
		}
		
		/*
		 * Collects as many messages as is sizewise allowed in one picklist.
		 * If a single message is bigger than the allowed maximum,
		 * add it to the list and returned the oversized list anyway.
		 * If desired, the picking can be prematurely ended if the last message
		 * includes a trailing field
		 */
		PickResult pick(int maxAllowedPacketSize,boolean stopOnTrailerMessage){
			boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
			if(logDEBUG)
			    Core.logger.log(this, "Picking...", Logger.DEBUG);
			PickResult r = new PickResult();
			LinkedList skippedMessages = null;
			int pickedMessagesLength =0; //Sum of the sizes of all picked messages
			synchronized (PeerMessageQueue.this) {
				long now = System.currentTimeMillis();
				while (!PeerMessageQueue.this.isEmpty()) {
					PeerPacketMessage msg = null;
					if(logDEBUG)
					    Core.logger.log(this, "Picking: outer while", Logger.DEBUG);
					while (true) {
					    if(logDEBUG)
					        Core.logger.log(this, "Picking: inner while", Logger.DEBUG);
						if(PeerMessageQueue.this.isEmpty()){ //Are we out of messages? Dont try to fetch one..
							msg = null;
							if(logDEBUG)
							    Core.logger.log(this, "PMQ empty", Logger.DEBUG);
							break;
						}
						msg = PeerMessageQueue.this.removeFirst();
						if(logDEBUG)
						    Core.logger.log(this, "removeFirst() = "+msg, Logger.DEBUG);
						if(msg == null) {
							Core.logger.log(this, "PMQ not empty but returned null for "+this,
									Logger.NORMAL);
							break;
						}
						if(msg instanceof TrailerChunkPacketMessage)
							r.trailerChunkBytes += msg.getLength();
						long expiry = msg.expiryTime();
						if(expiry > 0 && //If the message has an expiry-time..
						   now > expiry) { //..and we are past it... 
							Core.logger.log(this, "Expired message " + msg + " for " + this+", last pick was "+(now-lastPickTime)+" milliseconds ago",new Exception("debug"), Logger.MINOR);
							Core.diagnostics.occurrenceContinuous("expiredPacketPriority",
											      msg.getPriorityDelta());
							expirer.enqueue(msg);
							continue;
						}
						break;
					}
					if (msg == null)
						break;
					if(logDEBUG)
					    Core.logger.log(this, "Resolving "+msg, Logger.DEBUG);
					msg.resolve(pres, false); // we need the length
					if(logDEBUG)
					    Core.logger.log(this, "Resolved "+msg, Logger.DEBUG);
					if((msg.getLength() > maxAllowedPacketSize) || 
					   (msg.hasTrailer() && stopOnTrailerMessage)) {
						if (logDEBUG)
							Core.logger.log(this, "Adding " + msg + 
									" to packet (" + r.pickedMessages.size() +
									") for " + this, Logger.DEBUG);
						r.pickedMessages.addLast(msg);
						pickedMessagesLength += msg.getLength();
						break;
					}
					if ((msg.getLength() + pickedMessagesLength) > maxAllowedPacketSize
					    && !r.pickedMessages.isEmpty()) { //Oops..one message to many.. put the it back again....
					    if(logDEBUG)
					        Core.logger.log(this, "Pushing back "+msg, Logger.DEBUG);
						PeerMessageQueue.this.addFirst(msg);
						break;
					}
					if (logDEBUG)
						Core.logger.log(
								this,
								"Adding " + msg + " to packet for " + this,
								Logger.DEBUG);
					if(includeRequests || !msg.isRequest()) {
					    if(logDEBUG)
					        Core.logger.log(this, "Really adding to packet for "+this,
					                Logger.DEBUG);
						r.pickedMessages.addLast(msg);
						pickedMessagesLength += msg.getLength();
					} else {
						if (logDEBUG)
							Core.logger.log(this, "Not adding "+msg+" because not request",
									Logger.DEBUG);
						if(skippedMessages == null) skippedMessages = new LinkedList();
						skippedMessages.add(msg);
					}
				}//while(!list.empty)
				if(skippedMessages != null) {
					if(logDEBUG)
					    Core.logger.log(this, "Putting messages back", Logger.DEBUG);
					PeerPacketMessage ppm;
					while(!skippedMessages.isEmpty()) {
						ppm = (PeerPacketMessage)skippedMessages.removeFirst();
						PeerMessageQueue.this.addFirst(ppm);
					}
				}
			} //sync(list)
			if(logDEBUG)
			    Core.logger.log(this, "Returning "+r, Logger.DEBUG);
			lastPickTime = System.currentTimeMillis();
			return r;
		}
	} // MessagePicker
	public synchronized String toString() {
		return super.toString()+" messageCount="+messagesQueued+", messageCount when counted="+countMessages();
	}

    /**
     * @return true if any messages queued have an MRI
     */
    public synchronized boolean messagesWithMRI() {
   		PeerMessageQueueElement e = 
   			(PeerMessageQueueElement) elements.head();
   		if(e == null) return false;
   		while(e != null) {
   		    if(e.messagesWithMRI()) return true;
   			e = (PeerMessageQueueElement) elements.next(e);
   		}
   		return false; 
   		// very unlikely that we'd get here unless there are very few messages queued
   		// nonetheless, we could optimize?
    }
}
