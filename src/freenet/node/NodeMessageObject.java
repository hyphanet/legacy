package freenet.node;
import freenet.MessageObject;

/**
 * NodeMessageObject's represent internal MessageObjects in the node.
 *
 * @author oskar
 */

public interface NodeMessageObject extends MessageObject {

    /**
     * If this MessageObject is received, but the id is previously unknown
     * this message will be called to generate an initial state of the chain.
     * @return      A new state object.
     * @exception   BadStateException if this MessageObject cannot be the 
     *              initial message of a chain.
     */
    public State getInitialState() throws BadStateException;

    /**
     * Returns true if this at least some of this chain takes place outside
     * this node.
     */
    public boolean isExternal();

    /**
     * Tells a message object that it has been dropped (for example 
     * BadStateException was thrown while handeling it) so it should
     * clean up it's resources.
     */
    public void drop(Node n);


}
