package freenet.message.client;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewGenerateSHA1;
import freenet.node.states.FCP.NewIllegal;

/** FCP SHA1 hash command.
  */
public class GenerateSHA1 extends ClientMessage {

    public static final String messageName = "GenerateSHA1";

    public GenerateSHA1(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw, true);
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing GenerateSHA1 message.")
            : (State) new NewGenerateSHA1(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }
}
