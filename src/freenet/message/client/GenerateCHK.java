package freenet.message.client;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewGenerateCHK;
import freenet.node.states.FCP.NewIllegal;

/** This is for the FCP GenerateCHK message.
  */
public class GenerateCHK extends ClientMessage {

    public static final String messageName = "GenerateCHK";

    public GenerateCHK(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw, true);
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing GenerateCHK message.")
            : (State) new NewGenerateCHK(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }
}
