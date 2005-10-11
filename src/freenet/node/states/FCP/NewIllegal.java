package freenet.node.states.FCP;

import freenet.*;
import freenet.node.*;
import freenet.message.client.*;

public class NewIllegal extends NewClientRequest {

    private String reason;

    public NewIllegal(long id, PeerHandler source) {
        super(id, source);
    }

    public NewIllegal(long id, PeerHandler source, String reason) {
        super(id, source);
        this.reason = reason;
    }
    
    public String getName() {
        return "New Client Illegal";
    }

    public State received(Node n, MessageObject mo) {
        sendMessage(
            new FormatError(id, reason == null
                                ? "Unrecognized FCP command." : reason)
        );
        return null;
    }
}

