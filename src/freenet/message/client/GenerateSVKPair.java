package freenet.message.client;

import freenet.*;
import freenet.node.*;
import freenet.node.states.FCP.*;

/** This is for the FCP GenerateSVKPair message.
  */
public class GenerateSVKPair extends ClientMessage {

    public static final String messageName = "GenerateSVKPair";

    public GenerateSVKPair(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw);
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing GenerateSVKPair message.")
            : (State) new NewGenerateSVKPair(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }
}
