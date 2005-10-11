package freenet.client;

import java.io.PrintStream;
import java.util.Hashtable;
import java.util.Vector;

import freenet.Core;
import freenet.MessageHandler;
import freenet.MessageObject;
import freenet.support.Logger;

/**
 * A MessageHandler specifically for the Freenet default clients. It enqueues
 * repsonses indexed by their id so clients can check them out and handle one
 * at a time.
 * 
 * @author oskar
 */
public class ClientMessageHandler extends MessageHandler {

	private ClientCore cc;
	private Hashtable queues;

	private class MessageQueue extends Vector {

		public MessageQueue() {
			super(3);
		}

		public void enqueue(ClientMessageObject m) {
			addElement(m);
		}

		public ClientMessageObject dequeue() {
			if (size() > 0) {
				Object o = firstElement();
				removeElementAt(0);

				// Take up slack if there is a non-trivial amount.
				if (capacity() - size() > 500) {
					trimToSize();
				}
				
				return (ClientMessageObject) o;
			} else
				return null;
		}
	}

	/**
	 * Creates a new ClientMessageHandler.
	 * 
	 * @param cc
	 *            The ClientCore this handler belongs to (used for sending
	 *            HandShakeReply-s).
	 */
	public ClientMessageHandler(ClientCore cc) {
		this.cc = cc;
		this.queues = new Hashtable();
	}

	/**
	 * Handles incoming messages. If the MessageObject is a HandshakeRequest, a
	 * HandshakeReply is sent back, if the MessageObject is any other Message
	 * it is put in a queue and can be checked out with getNextReply. Non
	 * Message MessageObjects are ignored.
	 * 
	 * @param mo
	 *            The MessageObject to handle.
	 * @param onlyIfCanRunFast
	 *            Must be true.
	 * @return true.
	 * @see MessageHandler
	 */
	public boolean handle(MessageObject mo, boolean onlyIfCanRunFast) {
		if (onlyIfCanRunFast)
			return false;
		if (mo instanceof ClientMessageObject) {
			MessageQueue mq = getQueue(mo.id());
			synchronized (mq) {
				mq.enqueue((ClientMessageObject) mo);
				mq.notify();
			}
		} else {
			Core.logger.log(
				this,
				"Got a MessageObject that I can't handle: " + mo,
				Logger.NORMAL);
		}
		return true;
	}

	/**
	 * Does nothing.
	 */
	public void printChainInfo(long id, PrintStream ps) {
		// This could print the queue if there was one - but nobody uses
		// this anyways anymore, so who cares...
	}

	/**
	 * Returns the next message received with the given id.
	 * 
	 * @param id
	 *            The id of the chain of messages for which to return the next
	 *            response. If there are no messages enqueued currently, this
	 *            will lock until one is received or the timelimit is reached.
	 * @param millis
	 *            The time to wait for response before an InterruptedException
	 *            is thrown. If this is 0 the call will lock indefinitely.
	 * @return The next ClientMessageObject with the given id that this
	 *         MessageHandler receives, or null if the time limit is reached.
	 * @exception InterruptedException
	 *                Thrown if the thread is interrupt()ed while waiting for
	 *                the next message.
	 */
	public ClientMessageObject getNextReply(long id, long millis)
		throws InterruptedException {

		MessageQueue mq = getQueue(id);
		ClientMessageObject m;

		synchronized (mq) {
			long endTime =
				millis == 0 ? 0 : System.currentTimeMillis() + millis;

			while ((m = mq.dequeue()) == null) {
				if (endTime == 0) {
					mq.wait(200);
				} else {
					long waitTime = endTime - System.currentTimeMillis();
					if (waitTime <= 0)
						break;
					mq.wait(waitTime);
				}
			}
		}
		return m;
	}

	/**
	 * Removes the queue of messages for a given id. This is used to avoid
	 * leaking memory in clients, but should only be called when you are sure
	 * you expect no more messages with this id.
	 * 
	 * @param id
	 *            The id of the ended chain of messages. Note that if there are
	 *            responses enqeued for this they will be lost.
	 */
	public synchronized void removeQueue(long id) {
		Object o = queues.remove(new Long(id));
		if (o != null)
			o.notifyAll();
	}

	private synchronized MessageQueue getQueue(long id) {
		Long longid = new Long(id);
		Object o = queues.get(longid);
		if (o == null) {
			o = new MessageQueue();
			queues.put(longid, o);
		}
		return (MessageQueue) o;
	}
}
