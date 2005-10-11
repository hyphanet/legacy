package freenet.message.client;
import freenet.*;
import freenet.node.State;
import freenet.node.states.FCP.*;

/**
 * A request for the node to return diagnostics data.
 *
 * @author oskar
 */

public class GetDiagnostics extends AdminMessage {

    public static final String messageName = "GetDiagnostics";

    public GetDiagnostics(BaseConnectionHandler source, RawMessage message) {
        super(source, message);
    }

    public State getInitialState() {
        return (formatError ? 
                (State) new NewIllegal(id, source.getPeerHandler(), 
                                       "Error parsing GetDiagnostics message."):
                (isAuthorized() ?                 
                 (State) new ReturnDiagnostics(id, source.getPeerHandler()) :
                 (State) new NewIllegal(id, source.getPeerHandler(),
                                        "Authorization failed")));
    }

    public String getMessageName() {
        return messageName;
    }
}
