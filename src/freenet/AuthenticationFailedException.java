package freenet;

/**
 * The AuthenticationFailedException is thrown when the remote node
 * provides bad auth data.  It is always terminal.  If the remote node
 * hangs up at any time during authentication, a ConnectFailedException
 * is thrown instead.
 */
public class AuthenticationFailedException extends CommunicationException {

    /**
     * If the peer identity is not known.
     */
    public AuthenticationFailedException(Address remote, String comment) {
        this(remote, null, comment);
    }

    /**
     * @param remote   address of the remote node
     * @param id       PK identity of the remote node
     * @param comment  description of the auth failure
     */
    public AuthenticationFailedException(Address remote, Identity id, String comment) {
        super(remote, id, comment, true);
    }
}



