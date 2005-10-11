package freenet.node.states.FNP;
import freenet.Core;
import freenet.PeerHandler;
import freenet.message.Identify;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.State;
import freenet.support.Logger;
/**
 * Handles Identify messages, verifies noderef contained and sets ref on PeerHandler
 */

public class NewIdentify extends State {
    
    public NewIdentify(long id) {
	super(id);
    }
    
    public String getName() {
	return "New Identify message";
    }
    
    public State receivedMessage(Node n, Identify msg) {
	NodeReference origRef = msg.getRef();
	PeerHandler ph = msg.source.getPeerHandler();
	// Don't need to verify signature
	// Because the connection is already authorized
	// BUT we do need to verify the identity
	if(!(ph.getIdentity().equals(origRef.getIdentity()))) {
	    Core.logger.log(this, "Spoofed Identify detected!: "+msg+
	            ": "+origRef+" on "+ph, Logger.ERROR);
	    return null;
	}
	n.rt.updateReference(origRef);
	n.connections.updateReference(origRef);
	return null;
    }
    
    public void lost(Node n) {
	Core.logger.log(this, "Lost a NewIdentify! "+this,
		     Logger.NORMAL);
    }
}