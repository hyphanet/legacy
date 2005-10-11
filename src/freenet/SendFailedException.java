package freenet;

public class SendFailedException extends CommunicationException {

    public SendFailedException(Address addr, Identity peer, String comment,
                               boolean terminal) {
        super(addr, peer, comment, terminal);
    }

    /**
     * SFEs default terminal to false.
     */
    public SendFailedException(Address addr) {
        this(addr, false);
    }

    public SendFailedException(Address addr, boolean terminal) {
        super(addr, "Can't send message to " + addr, terminal);
    }

    /**
     * SFEs default terminal to false.
     */
    public SendFailedException(Address addr, String s) {
        super(addr, s, false);
    }
    
    public SendFailedException(CommunicationException e) {
        super(e);
    }
}
