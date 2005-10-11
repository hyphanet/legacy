package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.node.BadReferenceException;
import freenet.node.NodeReference;
import freenet.node.State;
import freenet.node.states.FNP.NewIdentify;

public class Identify extends NodeMessage {
    
    protected final FieldSet nodeRef;
    protected final NodeReference sourceRef;
    
    public static final String messageName="Identify";
    
    public Identify(long id, NodeReference ref) {
	super(id);
	sourceRef = ref;
	nodeRef = null;
    }
    
    public Identify(BaseConnectionHandler source, RawMessage raw) 
	throws InvalidMessageException {
	super(source, raw);
	nodeRef  = otherFields.getSet("Source");
	if(nodeRef == null) 
	    throw new InvalidMessageException("No reference found");
	try {
	    // Verifying it is far too expensive to do here
	    sourceRef = new NodeReference(nodeRef, false, 
					  source.peerIdentity());
	} catch (BadReferenceException e) {
	    throw new InvalidMessageException("Failed to read NodeReference: "
					      +e);
	}
	otherFields.remove("Source");
    }
    
    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
	RawMessage raw=super.toRawMessage(t, ph);
	raw.fs.put("Source", sourceRef.getFieldSet(false));
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
    
    public final State getInitialState() {
	return new NewIdentify(id);
    }
    
    public final NodeReference getVerifiedSource() 
	throws BadReferenceException {
	if(sourceRef.isSigned()) return sourceRef;
	if(nodeRef == null) return null;
	return new NodeReference(nodeRef, true, 
				 source.peerIdentity());
    }
    
    public final NodeReference getRef() {
	return sourceRef;
    }

    public int getPriority() {
        return -10; // fairly important
    }
}
