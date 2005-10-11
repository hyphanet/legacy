package freenet.node.states.data;

import freenet.node.*;

/** This message causes a new chain to be started with a DataState as the
  * initial state to be executed.  It is not public;  instead it is used
  * by the schedule() method of the DataState.
  * @author tavin
  */
final class DataStateInitiator implements NodeMessageObject {

    private final DataState ds;

    /** Create a DataStateInitiator to launch a given DataState
      * @param ds  the DataState that will be launched on the new chain
      */
    DataStateInitiator(DataState ds) {
        this.ds = ds;
    }

    public final long id() {
	return ds.id();
    }

    public final State getInitialState() {
        return ds;
    }

    public final boolean isExternal() {
        return false;
    }

    /** There is not much we can really do - we could try to schedule
      * a DataStateReply indicating failure but it's just as likely
      * to be lost as this.
      */
    public final void drop(Node n) {
        // it is just as if the state were lost before it could receive
        // the DataStateInitiator MO
        ds.lost(n);
    }
}


