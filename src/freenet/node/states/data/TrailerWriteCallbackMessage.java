package freenet.node.states.data;

import freenet.*;
import freenet.node.*;

/**
 * Message form of the TrailerWriteCallback. Used by states that need to do expensive stuff e.g. blocking reads after the write succeeds.
 */
class TrailerWriteCallbackMessage extends EventMessageObject implements TrailerWriteCallback {
	private boolean finished;
	private boolean success;
	private final Node n;
	private final SendData st;
	public TrailerWriteCallbackMessage(long id, Node n, SendData st) {
		super(id, false); // Data states are internal, see DataStateInitiator
		this.n = n;
		this.st = st;
		reset();
	}

	public synchronized void reset() {
		finished = false;
		success = false;
	}

	public synchronized void closed() {
		success = false;
		finished = true;
		n.schedule(this);
	}

	public synchronized void written() {
		success = true;
		finished = true;
		n.schedule(this);
	}
	public boolean isFinished()	{
		return success;
	}
	public boolean isSuccess(){
		return finished;
	}

	public String toString() {
		return getClass().getName() + ":" + finished + ":" + success;
	}

	public long bytesAvailable() {
		return st.bytesAvailable();
	}
}
