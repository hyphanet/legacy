package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;

/**
 * QueryRestarted messages are sent when the Query is restarted do to no
 * response coming back from a node, or bad data being discovered etc.
 * Since the scheduled time is relative to the hops to live, this ensures
 * that even on an error, the query is only restarted at the "deepest" possible
 * node.
 *
 * @author oskar
 */

public class QueryRestarted extends NodeMessage {

	public static final String messageName = "QueryRestarted";

	public QueryRestarted(BaseConnectionHandler source, RawMessage m) throws InvalidMessageException {
		super(source, m);
	}

	public QueryRestarted(long idnum) {
		super(idnum, null);
	}

	public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
		RawMessage raw = super.toRawMessage(t, ph);
		raw.trailingFieldName = "EndMessage";
		return raw;
	}

	public final boolean hasTrailer() {
		return false;
	}

	public final long trailerLength() {
		return 0;
	}

	public String getMessageName() {
		return messageName;
	}

	public int getPriority() {
		return -2; // Above Accepted, below DataRequest
	}

} 







