package freenet;

public class ConnectFailedException extends CommunicationException {

    public ConnectFailedException(Address peer, Identity id, String comment,
                                  boolean terminal) {
        super(peer, id, comment, terminal);
    }

    /**
     * CFEs default terminal to true.
     */
    public ConnectFailedException(Address peer) {
        super(peer, "Cannot connect to: " + peer, true);
    }

    /**
     * CFEs default terminal to true.
     */
    public ConnectFailedException(Address peer, String s) {
        super(peer, s, true);
    }

    public ConnectFailedException(CommunicationException e) {
        super(e);
    }
}
