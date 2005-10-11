package freenet.message.client;

import freenet.*;
import freenet.node.State;
import freenet.node.states.FCP.*;

/** This is for the FCP handshake.
  */
public class ClientInfo extends ClientMessage {

    public static final String messageName = "ClientInfo";

    public ClientInfo(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw);
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing ClientInfo message.")
            : (State) new NewInfo(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }
}
