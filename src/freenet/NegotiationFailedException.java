package freenet;

/**
 * The NegotiationException is thrown when a compatible choice of session
 * and presentation cannot be negotiated with the remote node.  It is
 * always terminal.
 */
public class NegotiationFailedException extends CommunicationException {

    /**
     * If the peer identity is not known.
     */
    public NegotiationFailedException(Address remote, String comment) {
        this(remote, null, comment);
    }

    /**
     * @param remote   address of the remote node
     * @param id       PK identity of the remote node
     * @param comment  description of the auth failure
     */
    public NegotiationFailedException(Address remote, Identity id, String comment) {
        super(remote, id, comment, true);
    }
}



