package freenet.node.states.data;
import freenet.Core;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Logger;

/** Implemented by states that deal with handling data.  A DataState needs
  * to run on its own chain, then send a reply back to a parent chain.
  * @author oskar
  * @author tavin
  */
abstract class DataState extends State {

    protected final long parent;
    private volatile boolean scheduled = false;
    protected boolean logDEBUG = true;
    
    /** @param id      the id of the chain this DataState will run under
      * @param parent  the id of the chain the DataStateReply goes to
      */
    DataState(long id, long parent) {
        super(id);
        this.parent = parent;
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
    }

    /** Schedule a message on the node's ticker that will run this DataState.
      */
    public void schedule(Node n) {
        if (!scheduled) {
            n.schedule(new DataStateInitiator(this));
            scheduled = true;
        }
    }

    /** @return  true if this DataState has been scheduled to run
      */
    public boolean scheduled() {
        return scheduled;
    }

    /** @return  the control byte that finished this stream,
      *          or -1 if it is still in progress
      */
    public abstract int result();

    /** @return  the id of the parent chain
      */
    public final long parent() {
        return parent;
    }
    
    public String toString() {
	return super.toString()+": "+Long.toHexString(id)+"/"+
	    Long.toHexString(parent);
    }
}


