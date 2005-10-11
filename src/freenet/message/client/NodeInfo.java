package freenet.message.client;

import freenet.FieldSet;

/** This is the FCP handshake.
  */
public class NodeInfo extends ClientMessage {

    public static final String messageName = "NodeInfo";

    public NodeInfo(long id, FieldSet fs) {
        super(id, fs);
    }

    public String getMessageName() {
        return messageName;
    }
}
