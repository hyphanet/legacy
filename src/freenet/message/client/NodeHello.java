package freenet.message.client;

import freenet.FieldSet;

/** This is the FCP handshake.
  */
public class NodeHello extends ClientMessage {

    public static final String messageName = "NodeHello";

    public NodeHello(long id, FieldSet fs) {
        super(id, fs);
    }

    public String getMessageName() {
        return messageName;
    }
}
