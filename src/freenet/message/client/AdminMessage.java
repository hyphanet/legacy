package freenet.message.client;
import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.node.Node;

/**
 * A request for the node to return diagnostics data.
 */

public abstract class AdminMessage extends ClientMessage {

    private String password;

    public AdminMessage(BaseConnectionHandler source, RawMessage message) {
        super(source, message);
        this.password = otherFields.getString("Password");
    }
    
    public boolean isAuthorized() {
        return Node.isAuthorized(source.peerIdentity(),
                                 password);
    }
}
