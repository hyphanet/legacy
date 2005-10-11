package freenet.node;

import java.io.PrintStream;
import java.util.Date;
import java.util.Enumeration;
import java.util.Vector;

import freenet.Core;
import freenet.MessageObject;
import freenet.support.Logger;

/**
 * Acts as a handle for a state chain and contains the current state
 * for that chain.
 */
public class StateChain implements AggregatedState {
    
    public static boolean KEEP_HISTORY = false;
    
    protected State state;

    protected Vector history = KEEP_HISTORY?new Vector():null;
    protected long startTime = System.currentTimeMillis();
    
    public StateChain() {
	if(Core.logger.shouldLog(Logger.DEBUG, this))
	    Core.logger.log(this, "Creating "+this,
			    new Exception("debug"),
			    Logger.DEBUG);
    }
    
    public String toString() {
        return super.toString() + (state == null ? "(null)" : state.toString());
    }
    
    /**
     * @return  true if the message should go to this state
     */
    public synchronized boolean receives(MessageObject mo) {
        return state == null
            || !(state instanceof AggregatedState)
            || ((AggregatedState) state).receives(mo);
    }
    
    /**
     * @return  false if the state chain has ended (gone to state null)
     */
    public boolean alive() {
        return state != null;
    }

    /**
	 * Sends the MO to the internal state for this chain, and updates the chain to the new state.
	 * 
	 * @return true if the chain is still alive
	 */
	public synchronized boolean received(Node node, NodeMessageObject mo) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(this, toString() + ": state = " + state + ", received " + mo, Logger.DEBUG);
		state = received(state, node, mo);
		if (logDEBUG)
			Core.logger.log(this, toString() + ": state now = " + state + ", after received " + mo, Logger.DEBUG);
		if (KEEP_HISTORY)
			history.addElement(new StateRecord(mo.toString(), state == null ? null : state.getClass(), System.currentTimeMillis()));
		return state != null;
	}
    
    public synchronized boolean canRunFast(Node node, NodeMessageObject mo) {
	if(state == null) return false;
	return state.canRunFast(node, mo);
    }
    
    /**
     * Calls lost() for the active state on this chain and
     * updates the internal state to null.
     * @return  true if a live state was lost
     */
    public synchronized boolean lost(Node node) {
        if (state == null)
            return false;
        lost(node, state);
	Core.logger.log(this, "lost(): state was "+state,
			Logger.DEBUG);
        state = null;
        return true;
    }

    /**
     * Returns the priority of the current state.
     */
    public synchronized int priority() {
        return state == null ? State.EXPENDABLE : state.priority();
    }

    /**
     * Prints information, including history if activated.
     */
    public synchronized void printStateInfo(PrintStream ps) {
        ps.println("===============");
        ps.println("StateChain started at " + new Date(startTime));
        ps.println("Current state: " + state);
        if (KEEP_HISTORY) {
            ps.println();
            ps.println("State history:");
            ps.println("Message Received\t\tState Entered\t\tTime");
            for (Enumeration e = history.elements() ; e.hasMoreElements();) {
                StateRecord sr = (StateRecord) e.nextElement();
                ps.println(sr.message + "\t\t" + 
                           (sr.state == null ? "end" : sr.state.getName())
                           + "\t\t" + new Date(sr.time) + " (" 
                           + (sr.time % 1000) + "ms )");
            }
        }
        ps.println("===============");
    }


    
    // static methods


    /**
     * Calls drop() on the MO, and eats and logs any uncaught throwable.
     */
    public static void drop(Node node, NodeMessageObject mo) {
        try {
            mo.drop(node);
        }
        catch (Throwable e) {
            Core.logger.log(node, "Error dropping message: "+mo,
                            e, Logger.ERROR);
        }
    }

    /**
     * Calls lost() on the state, and eats and logs any uncaught throwable.
     */
    public static void lost(Node node, State state) {
        try {
            state.lost(node);
        }
        catch (Throwable e) {
            Core.logger.log(node, "Error discarding state: "+state,
                            e, Logger.ERROR);
        }
    }
    
    /**
     * @return  the new State when the given State receives the MO
     */
    public static State received(State state, Node node, NodeMessageObject mo) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, StateChain.class);
		boolean logMINOR = Core.logger.shouldLog(Logger.MINOR, StateChain.class);
		// new chain
		if (state == null) {
			try {
				state = mo.getInitialState();
			} catch (BadStateException e) {
				drop(node, mo);
				if (logMINOR)
					Core.logger.log(node, "Bad state on new message: " + mo, e, Logger.MINOR);
			} catch (Throwable e) {
				Core.logger.log(node, "Error getting initial state for message: " + mo, e, Logger.ERROR);
			}
			if (state == null) // still
				return null;
		}

		// pass message to state and record transition
		State newState = null;
		NodeMessageObject[] queue = null;

		try {
			if (logDEBUG)
				Core.logger.log(StateChain.class, "Running " + state + ".received(" + node + "," + mo + ")", Logger.DEBUG);
			newState = state.received(node, mo);
			if (logDEBUG)
				Core.logger.log(StateChain.class, "Finished running " + state + ".received(" + node + "," + mo + ")", Logger.DEBUG);
		} catch (BadStateException e) {
			if (logMINOR)
				Core.logger.log(node, "Message " + mo + " received during state " + state + " was not welcome", e, Logger.MINOR);
			// clean up
			drop(node, mo);
			// no state change; exit
			return state;
		} catch (StateTransition e) {
			newState = e.state;
			if (e.exec) {
				queue = e.mess;
			} else {
				for (int i = 0; i < e.mess.length; ++i)
					drop(node, e.mess[i]);
			}
		}
		// we'll treat an abnormal exit the same as a null exit
		// (newState is null)
		catch (Throwable e) {
			Core.logger.log(node, "Error while receiving message " + mo + " in state " + state + ": " + e, e, Logger.ERROR);
		}

		// null or abnormal exit
		if (newState == null) {
			if (logDEBUG)
				Core.logger.log(node, "Ending chain: " + Long.toHexString(state.id()), new Exception("debug"), Logger.DEBUG);
		}
		// normal transition
		else if (newState != state) {
			if (logDEBUG)
				Core.logger.log(StateChain.class, "Chain " + Long.toHexString(state.id()) + " state change: " + state.getName() + " -> " + newState.getName(), Logger.DEBUG);
		}

		// okay, watch out for weirdo recursion problems..
		if (queue != null) {
			for (int i = 0; i < queue.length; ++i) {
				if (newState == null)
					drop(node, queue[i]);
				else
					newState = received(newState, node, queue[i]);
			}
		}

		return newState;
	}

    private static class StateRecord {
        private String message;
        private Class state;
        private long time;

        public StateRecord(String message, Class state, long time) {
            this.message = message;
            this.state = state;
            this.time = time;
        }
    }
}







