package freenet.message.client;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.states.FCP.NewClientPut;
import freenet.node.states.FCP.NewIllegal;

/** This is for the FCP ClientPut message.
  */
public class ClientPut extends ClientRequest {

    public static final String messageName = "ClientPut";

    public ClientPut(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw, true);
    }

    public State getInitialState() {
        if (!Node.fcpInserts)
            return new NewIllegal(id, source.getPeerHandler(),
                "FCP inserts have been disabled with the fcpInserts option.");
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing ClientPut message.")
            : (State) new NewClientPut(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }
}
