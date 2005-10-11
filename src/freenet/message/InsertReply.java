package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;

public class InsertReply extends NodeMessage {

    public static final String messageName = "InsertReply";

    public InsertReply(long idnum) {
	super(idnum, null);
    }

    public InsertReply(BaseConnectionHandler source, 
                       RawMessage raw) throws InvalidMessageException {
	super(source, raw);
    }

    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
	RawMessage raw=super.toRawMessage(t,ph);
        //	//raw.messageType="InsertReply";
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
        return -5; // same as DataInsert and DataReply - FIXME is this right?
    }
}



