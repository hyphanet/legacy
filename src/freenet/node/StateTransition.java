package freenet.node;

/** This exception is thrown when a state requires extraordinary behavior
  * beyond a normal transition;  such as returning a queue of delayed
  * messages to the MessageHandler for immediate processing.
  */
public class StateTransition extends StateException {
    
    State state;
    NodeMessageObject[] mess;
    boolean exec;
    
    /** @param state  the State that should be transitioned to
      * @param mess   the MessageObjects that need attention
      * @param exec   true to pass the MOs to the new State, false to drop them
      */
    public StateTransition(State state, NodeMessageObject[] mess, boolean exec) {
        super();
        this.state = state;
        this.mess  = mess;
        this.exec  = exec;
    }

    /** Like the above, but there is only one MO to deal with.
      */
    public StateTransition(State state, NodeMessageObject mo, boolean exec) {
        this(state, new NodeMessageObject[] { mo }, exec);
    }
}
