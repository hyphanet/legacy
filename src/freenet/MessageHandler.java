package freenet;

import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Hashtable;

import freenet.support.Logger;

/**
 * MessageHandlers are responsible for handeling Messages and other
 * MessageObjects. They should make sure that all MessageObjects are handled
 * one by one as received.
 * 
 * @author oskar
 */
public abstract class MessageHandler {

	private class MessageType {

		private Class rawType;
		private String msgName;
		public MessageType(Class rawType, String msgName) {
			this.rawType = rawType;
			this.msgName = msgName;
		}
		public int hashCode() {
			return rawType.hashCode() ^ msgName.hashCode();
		}
		public boolean equals(Object o) {
			MessageType oo;
			try {
				oo = (MessageType) o;
			} catch (ClassCastException e) {
				return false;
			}
			return rawType.equals(oo.rawType) && msgName.equals(oo.msgName);
		}
	}

	private Hashtable messageTypes = new Hashtable();

	/**
	 * Registers a type of message with this MessageHandler, so that instances
	 * of it can be returned by getMessageFor()
	 * 
	 * @param rawType
	 *            The associated RawMessage class
	 * @param name
	 *            The name of the message
	 * @param mc
	 *            The class of the message - this must be a subclass of
	 *            freenet.Message
	 * @throws ClassCastException
	 *             If mc is not a subclass of freenet.Message or if it cannot
	 *             be constructed from a single BaseConnectionHandler and
	 *             RawMessage instance.
	 */
	public void addType(Class rawType, String name, Class mc) {
		addType(new MessageType(rawType, name), mc);
	}

	/**
	 * Registers a default Message for a particular class of RawMessage
	 * 
	 * @see #addType(Class rawType, String name, Class mc)
	 */
	public void addType(Class rawType, Class mc) {
		addType((Object) rawType, mc);
	}

	private void addType(Object o, Class mc) {
		if (!freenet.Message.class.isAssignableFrom(mc)) {
			throw new ClassCastException();
		}
		try {
			Constructor con =
				mc.getConstructor(
					new Class[] { BaseConnectionHandler.class, RawMessage.class });
			messageTypes.put(o, con);
		} catch (NoSuchMethodException e) {
			throw new ClassCastException();
		}
	}

	/**
	 * Returns an instance of a Message object for a RawMessage.
	 * 
	 * @param source
	 *            The source of the message
	 * @param r
	 *            The RawMessage to construct the Message from.
	 * @throws InvalidMessageException
	 *             if the messagetype has not been registered or and error
	 *             occurs during construction.
	 */
	public Message getMessageFor(BaseConnectionHandler source, RawMessage r)
		throws InvalidMessageException {
		Constructor con;

		con =
			(Constructor) messageTypes.get(
				new MessageType(r.getClass(), r.messageType));
		if (con == null)
			con = (Constructor) messageTypes.get(r.getClass());
		if (con == null)
			throw new InvalidMessageException("Type not recognized");

		try {
			return (Message) con.newInstance(new Object[] { source, r });
		} catch (InvocationTargetException e) {
			Throwable target = e.getTargetException();
			if (target instanceof InvalidMessageException)
				throw (InvalidMessageException) target;
			else {
				Core.logger.log(
					MessageHandler.class,
					"MessageFactory.java, Message constructor threw exception "
						+ target,
					target,
					Logger.ERROR);
				throw new InvalidMessageException(
					"Message constructor threw unknown exception "
						+ target
						+ ".");
			}
		} catch (InstantiationException e) {
			Core.logger.log(
				MessageHandler.class,
				"MesageFactory threw exception.",
				e,
				Logger.ERROR);
			throw new InvalidMessageException("Unknown Error");
		} catch (IllegalAccessException e) {
			Core.logger.log(
				MessageHandler.class,
				"MesageFactory threw exception.",
				e,
				Logger.ERROR);
			throw new InvalidMessageException("Unknown Error");
		}

	}
	
	/**
	 * Handles a MessageObject.
	 * 
	 * @param m
	 *            The MessageObject to handle.
	 * @param onlyIfCanRunFast
	 *            If true, only handle the message if we can do it quickly. If
	 *            false, just run it now regardless.
	 * @return true, unless onlyIfCanRunFast is true and we can't run the
	 *         message yet.
	 */
	public abstract boolean handle(MessageObject m, boolean onlyIfCanRunFast);

	/**
	 * A debug method that prints information known about the MessageObject
	 * chain identified by the id number.
	 * 
	 * @param ps
	 *            A PrintStream to write to.
	 */
	public abstract void printChainInfo(long id, PrintStream ps);

}
