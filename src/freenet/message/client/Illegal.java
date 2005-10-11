package freenet.message.client;

import freenet.*;
import freenet.node.*;
import freenet.node.states.FCP.NewIllegal;

/**
  * This is the general FCP response for unintelligible messages.
  */
public class Illegal extends ClientMessage {

    public Illegal(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw);
    }

    public State getInitialState() {
        return new NewIllegal(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return "Illegal";
    }
}
