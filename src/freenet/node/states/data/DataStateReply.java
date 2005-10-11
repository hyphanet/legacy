package freenet.node.states.data;

import freenet.node.*;

/** A DataStateReply is sent back to the parent chain after the
  * handling of the data is completed.
  */
public abstract class DataStateReply implements NodeMessageObject {

    private final long id;
    private final DataState ds;
    
    /** @param ds  the DataState that sent this MO
      */
    DataStateReply(DataState ds) {
        this.id = ds.parent();
        this.ds = ds;
    }

    public final long id() {
        return id;
    }

    public final State getInitialState() throws BadStateException {
        throw new BadStateException("Parent chain of "+ds.getName()+" already gone!");
    }

    public final boolean isExternal() {
        return true;
    }
    
    /** A reference to the DataState that sent this reply.
      */
    public final DataState source() {
        return ds;
    }

    /** The control byte indicates what happened to a data transfer.
      * @see freenet.Presentation for values
      */
    public final int getCB() {
        return ds.result();
    }

    /** There is not much we can do - scheduling another DataStateReply
      * is pretty pointless.
      */
    public final void drop(Node n) {}

    public String toString() {
        return super.toString() + "@" + Long.toHexString(id) + "  CB: " + 
            ds.result();
    }
}

