package freenet;

/**
 * A superclass of the inter-node com exceptions (SendFailed and 
 * ConnectFailed.
 *
 * @author oskar
 */
public abstract class CommunicationException extends Exception {

    public Identity peer;
    protected final Address addr;
    protected final boolean terminal;

    /**
     * Make a communciation exception for a peer whose identity is known.
     * @param addr   The physical address of the peer.
     * @param peer   The identity of the peer.
     * @param comment  Exception comment.
     * @param terminal  Whether the error was terminal - in other words,
     *                  whether retrying is unlikely to help.
     */
    protected CommunicationException(Address addr, Identity peer, 
                                     String comment, boolean terminal) {
        super(comment);
        this.peer = peer;
        this.addr = addr;
        this.terminal = terminal;
    }

    /**
     * Make a communciation exception for a peer whose identity is not known.
     * @param addr   The physical address of the peer.
     * @param comment  Exception comment.
     * @param terminal  Whether the error was terminal - in other words,
     *                  whether retrying is unlikely to help.
     */
    protected CommunicationException(Address addr, String comment, 
                                     boolean terminal) {
        this(addr, null, comment, terminal);
    }
    
    /**
     * Make a CommunicationException as a copy of another.
     */    
    protected CommunicationException(CommunicationException e) {
        this(e.addr, e.peer, e.getMessage(), e.terminal);
    }

    public final boolean isTerminal() {
        return terminal;
    }

    public final Address peerAddress() {
        return addr;
    }

    public final void setIdentity(Identity peer) {
        this.peer = peer;
    }

    public final Identity peerIdentity() {
        return peer;
    }

    public String toString() {
        return getClass().getName() + ": Against peer "
            + (peer == null ? "(null)" : peer.toString()) + " @ " + addr + " - "
            + getMessage() + (terminal ? " (terminal)" : " (nonterminal)");
    }
}





