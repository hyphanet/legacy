package freenet.node.states.FCP;

import freenet.FieldSet;
import freenet.KeyException;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.client.ComputeSizeRequest;
import freenet.client.FreenetURI;
import freenet.message.client.GetSize;
import freenet.message.client.Success;
import freenet.message.client.URIError;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;

/** This is the state pertaining to GetSize's in their
 * initial state
 */

public class NewGetSize extends NewClientRequest {
    
    public NewGetSize(long id, PeerHandler source) {
	super(id, source);
    }
    
    public String getName() {
	return "New Client GetSize";
    }
    
    public State received(Node n, MessageObject mo) throws BadStateException {
	if (!(mo instanceof GetSize)) {
            throw new BadStateException("expecting GetSize");
        }
        GetSize gs = (GetSize) mo;
	
	FreenetURI uri = gs.getURI();
	
	ComputeSizeRequest r = new ComputeSizeRequest(uri);
	long sz;
	try {
	    sz = r.execute();
	} catch (KeyException ke) {
	    sendMessage(new URIError(id, ke.getMessage()));
	    return null;
	}
	
	FieldSet fs = new FieldSet();
	
	fs.put("Length", Long.toHexString(sz));
	
	sendMessage(new Success(id, fs));
	
	return null;
    }
}
