package freenet.message.client;

import freenet.*;
import freenet.node.State;
import freenet.node.states.FCP.*;

/** This is for the FCP handshake.
  */
public class ClientHello extends ClientMessage {

    public static final String messageName = "ClientHello";

    public ClientHello(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw);
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing ClientHello message.")
            : (State) new NewHello(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }
}
