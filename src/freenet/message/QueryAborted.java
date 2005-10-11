package freenet.message;

import freenet.*;

public class QueryAborted extends NodeMessage {

    public final static String messageName = "QueryAborted";

    public QueryAborted(BaseConnectionHandler source, RawMessage m)
        throws InvalidMessageException {
 
        super(source, m);
        //  keepAlive = false;
    }

    public QueryAborted(long idnum) {
        super(idnum, null);
        //        keepAlive = false;
    }

    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
        RawMessage raw=super.toRawMessage(t,ph);
        //raw.messageType=messageName;
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
        return -2; // between Accepted and DataRequest
    }

}


