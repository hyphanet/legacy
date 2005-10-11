package freenet;

import freenet.client.ClientMessageObject;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.NodeMessageObject;
import freenet.node.State;

/**
 * Messages represent message objects that can be turned into rawmessages
 * and sent between nodes.
 *
 * @author oskar
 */
public abstract class Message implements NodeMessageObject, 
                                         ClientMessageObject {
    


    /** ConnectionHandler the message was received on. */
    public BaseConnectionHandler source;

    public PeerHandler getSource() {
        return source.getPeerHandler();
    }

    /** A randomly generated unique ID used to identify related messages */
    public /* final */ long id;
    
    /** Any unknown / unused fields */
    public final FieldSet otherFields;

    /** Whether to close the connection after sending this message */
    protected boolean close = false;
    /** Whether to sustain the connection after sending this message */
    protected boolean sustain = false;

    /** The time this message was received from the network, or -1 */
    protected long receivedTime = -1;

    /**
     * Creates a new message.
     * @param  id           The message's Unique ID, should be a random long.
     * @param  otherFields  The remaining set of fields which are not directly
     *                      related to routing the message.
     */
    protected Message(long id, FieldSet otherFields) {
        this.id = id;        
        this.otherFields = otherFields;
    }

    /**
     * Creates a new message
     * @param id  The message's Unique Id.
     */
    protected Message(long id) {
        this(id, new FieldSet());
    }
    
    /** Creates a message from a RawMessage with the given id number
     * @param  source  The connection on which the message was received.
     * @param  id      The unique id value to give the message.
     * @param  raw     A rawmessage describing the message body. 
     */
    protected Message(BaseConnectionHandler source, long id, RawMessage raw) {
        this(id, raw.fs);
        this.source = source;
    }
    
    /** Converts this message to something that can be sent over the wire,
      * using the given Presentation.
      * @argument ph some messages need to know what PeerHandler they are going to
      */
    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
        RawMessage r = t.newMessage(getMessageName(), close , sustain,
                                    otherFields, 0, "EndMessage", null, -1);
        
        return r;
    }
    
    /** Part of the MessageObject implementation.
      * @return the unique ID of this message
      */
    public long id() {
        return id;
    }

    /**
     * Return the name of the message.
     */
    public abstract String getMessageName();

    /**
     * Part of the NodeMessageObject interface that allows messages to be
     * handled by the node.
     *
     * Override for messages that can start a chain - by default this will
     * throw a BadStateException with the comment "This message is a 
     * response".
     */
    public State getInitialState() throws BadStateException {
        throw new BadStateException("This message is a response");
    }
    
    /**
     * Part of the NodeMessageObject interface. Messages are external.
     */
    public boolean isExternal() {
        return true;
    }

    /**
     * Called on a message object when it is dropped because it was undesired, 
     * should clean up  (killing trailing fields and alike). The default 
     * implementation does nothing.
     */ 
    public void drop(Node n) {
        // Do nothing
    }

    /**
     * Set receive time.
     */
    public void setReceivedTime(long time) {
        receivedTime = time;
    }

    /**
     * Get receive time.
     */
    public long getReceivedTime() {
        return receivedTime;
    }

    /** 
     * @return  the Identity of the peer this message was received from,
     *          or null if inapplicable
     */
    public Identity peerIdentity() {
        return source == null ? null : source.peerIdentity();
    }
    
    public String toString() {
        return super.toString()+" "+getMessageName()
                +" @"+source+" @ "+Long.toHexString(id);
    }
    
    public abstract boolean hasTrailer();
    
    public abstract long trailerLength();

    /**
     * @return the priority of this message. Lower is better.
     */
    public abstract int getPriority();

    /**
     * Callback for when we have successfully sent the message to some
     * external entity. Override to do message-specific things if needed
     * after sending. Example: PeerHandler's sentRequestInterval could
     * be called by an onSent() on Accepted and QueryRejected.
     */
    public void onSent(PeerHandler target) {
        // Intentionally empty
    }

    /**
     * Callback for when we have attempted to send a message and not
     * succeeded. Override to do message-specific things if necessary.
     */
    public void onNotSent(PeerHandler target) {
        // Intentionally empty
    }
}


