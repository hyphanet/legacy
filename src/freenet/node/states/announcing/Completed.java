package freenet.node.states.announcing;
import freenet.Identity;
import freenet.node.EventMessageObject;

/**
 * Indicates that an announcement is completed (for better or worse).
 *
 * @author oskar
 */
public class Completed extends EventMessageObject {

    Identity peer;
    boolean successful;
    int htl;
    boolean terminal;
    String reasonString = null;

    public Completed(long id, Identity peer, int htl) {
        super(id, true);
        this.peer = peer;
        this.successful = true;
        this.htl = htl;
    }

    public Completed(long id, Identity peer, int htl, boolean terminal) {
        super(id, true);
        this.peer = peer;
        this.successful = false;
        this.terminal = terminal;
        this.htl = htl;
    }
    
    public Completed(long id, Identity peer, int htl, boolean terminal,
		     String reasonString) {
        super(id, true);
        this.peer = peer;
        this.successful = false;
        this.terminal = terminal;
        this.htl = htl;
	this.reasonString = reasonString;
    }
    
    public String toString() {
        return ("Announce " + (successful ? "succeeded" : "failed") 
                + ". id: " + 
                Long.toHexString(id) + 
                " target: " + peer.fingerprintToString() +
		((reasonString == null) ? "" : (": "+reasonString)));
    }
}
