package freenet.node.states.request;

import freenet.node.State;

public class RequestAbortException extends Exception {
    public final State state;
    public RequestAbortException(State s) {
        super();
        state = s;
    }
    
    public String toString() {
	return super.toString()+" for "+state;
    }
}
