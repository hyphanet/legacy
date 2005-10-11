package freenet.message.client;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewGetSize;
import freenet.node.states.FCP.NewIllegal;

/** This is the message for FCP GetSize
 */
public class GetSize extends ClientRequest {
    public static final String messageName = "GetSize";
    
    public GetSize(BaseConnectionHandler source, RawMessage raw) {
	super(source, raw);
    }
    
    public State getInitialState() {
	return formatError ?
	    (State) new NewIllegal(id, source.getPeerHandler(), 
				   "Error parsing GetSize message.") :
	    (State) new NewGetSize(id, source.getPeerHandler());
    }
    
    public String getMessageName() {
	return messageName;
    }
}
