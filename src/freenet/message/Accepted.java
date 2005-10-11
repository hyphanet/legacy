package freenet.message;
import freenet.*;

/**
 * This is a single hops message sent back to notify the node from which an InsertRequest was received that it can go ahead and send the data.
 *
 * @author oskar
 */
public class Accepted extends NodeMessage {

    public static final String messageName = "Accepted";

    public Accepted(long idnum) {
		super(idnum);
    }

	public Accepted(BaseConnectionHandler source, RawMessage raw) throws InvalidMessageException {
	super(source, raw);
    }
    
    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
	RawMessage raw = super.toRawMessage(t,ph);
	//raw.messageType=messageName;
	return raw;
    }
    
    public String getMessageName() {
        return messageName;
    }

    public final boolean hasTrailer() {
	return false;
    }
    
    public final long trailerLength() {
	return 0;
    }
    
    public int getPriority() {
        return -1; // better than a QR, worse than a DR
    }
}
