package freenet.node;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Hashtable;

import freenet.Core;
import freenet.MessageObject;
import freenet.support.Logger;

/**
 * State objects describe the internal state of a message chain (identified by
 * an id), and are called when MessageObjects with that id are received.
 * 
 * @author oskar
 */
public abstract class State {

	// eliminate some overhead...

	/** The priority of states that absolutely should not be lost */
	public static final int CRITICAL = 4;
	/** The priority of states that should not be lost */
	public static final int IMPORTANT = 3;
	/** The priority of states that we strive to keep */
	public static final int OPERATIONAL = 2;
	/** The priority of states that we are free to loose */
	public static final int EXPENDABLE = 1;

	private static Hashtable methodCache = new Hashtable();

	private static final class MethodKey {
		private final Class stateClass, moClass;
		private MethodKey(Class stateClass, Class moClass) {
			this.stateClass = stateClass;
			this.moClass = moClass;
		}
		public final boolean equals(Object o) {
			return o instanceof MethodKey
				&& stateClass.equals(((MethodKey) o).stateClass)
				&& moClass.equals(((MethodKey) o).moClass);
		}
		public final int hashCode() {
			return stateClass.hashCode() ^ moClass.hashCode();
		}
	}

	/** unique ID to associate this state with a chain */
	protected final long id;

	/**
	 * Create a new State.
	 * 
	 * @param id
	 *            The message chain's id number.
	 */
	protected State(long id) {
		this.id = id;
	}

	public final long id() {
		return id;
	}

	/**
	 * Returns the name of the state.
	 * 
	 * @return The name of the state.
	 */
	public abstract String getName();

	// Oskar - I changed this to only select receivedMessage methods explicitly
	//         declared in the class -- I feel like this is safer; each state
	//         declares the messages it receives even if it just passes the
	//         implementation off to a superclass.

	/**
	 * The default implementation of this will look for a method of the class
	 * named "receivedMessage" that takes a Node and the class of the
	 * messageobject argument as an arguments, and which should return a State
	 * object. IE something like:
	 * 
	 * <p>
	 * <code>public State receivedMessage(Node n, DataRequest dr)</code>
	 * </p>
	 * 
	 * <p>
	 * and if no such method can be called to handle dr, BadStateException is
	 * thrown. Subclasses should either add a receivedMessage method for every
	 * expected message, or override this method.
	 * </p>
	 * 
	 * @param mo
	 *            The message.
	 * @param n
	 *            The controling node receiving the message.
	 * @return This object or another state object to take over for this chain.
	 */
	public State received(Node n, MessageObject mo) throws StateException {

		MethodKey k = new MethodKey(this.getClass(), mo.getClass());
		Method recv = (Method) methodCache.get(k);

		if (recv == null) {
			try {
				recv =
					this.getClass().getDeclaredMethod(
						"receivedMessage",
						new Class[] { freenet.node.Node.class, mo.getClass()});
			} catch (SecurityException e) {
				throw new BadStateException(
					"??? SecurityException ???  WTF!!: " + e);
			} catch (NoSuchMethodException e) {
				// This indicates something is significantly wrong
				Core.logger.log(
					this,
					"State does not receive: " + mo + ": " + e,
					Logger.ERROR);
				throw new BadStateException(
					"State does not receive this message: " + e);
			}

			if (!State.class.isAssignableFrom(recv.getReturnType())) {
				throw new BadStateException(
					"bad receivedMessage() return type declared: "
						+ recv.getReturnType());
			}

			Class[] exceptions = recv.getExceptionTypes();
			for (int i = 0; i < exceptions.length; ++i) {
				if (!StateException.class.isAssignableFrom(exceptions[i]))
					throw new BadStateException(
						"bad receivedMessage() exception declared: "
							+ exceptions[i]);
			}

			// and cache it
			methodCache.put(k, recv);
		}

		// and pass to receivedMessage()
		try {
			return (State) recv.invoke(this, new Object[] { n, mo });
		} catch (IllegalAccessException e) {
			throw new BadStateException("cannot access received method: " + e);
		} catch (InvocationTargetException e) {
			Throwable te = e.getTargetException();
			if (te instanceof StateException)
				throw (StateException) te;
			else if (te instanceof RuntimeException)
				throw (RuntimeException) te;
			else if (te instanceof Error)
				throw (Error) te;
			else {
				// well, this is impossible -- receivedMessage must have
				// declared an exception other than StateException,
				// but we explicitly checked that before calling it!
				Core.logger.log(
					this,
					"Got declared exception I know wasn't.",
					e,
					Logger.ERROR);
				throw new RuntimeException("PANIC! JAVA IS ON CRACK: " + te);
			}
		}
		// I'm not trapping everything into a BadStateException anymore
		// b/c I want the MessageHandler to be able to distinguish that
		// case from an abnormal exit
	}

	/**
	 * Can a message run more or less instantly on this state if called right
	 * now?
	 */
	public boolean canRunFast(Node node, MessageObject mo) {
		return false;
	}

	/**
	 * This gives the priority of the state.
	 * 
	 * @return By default, the method returns OPERATIONAL
	 */
	public int priority() {
		return OPERATIONAL;
	}

	/**
	 * A method called if this messagememory is lost from the datastore due to
	 * overflow
	 */
	public abstract void lost(Node n);

	/**
	 * Called when a state's received() method returns null or exits abnormally
	 * with a Throwable other than BadStateException.
	 */
	//public void cleanup(Node n) {}

	public String toString() {
		return super.toString() + " @ " + Long.toHexString(id);
	}
}
