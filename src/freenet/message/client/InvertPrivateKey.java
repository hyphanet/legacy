package freenet.message.client;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewIllegal;
import freenet.node.states.FCP.NewInvertPrivateKey;

/** FCP command to make a public key from a private one.
 * @author giannij
 */
public class InvertPrivateKey extends ClientMessage {

    public static final String messageName = "InvertPrivateKey";

    private String privateValue = null;

    public InvertPrivateKey(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw, false);
        if (!formatError) {
            formatError = true;
            try {
                privateValue = otherFields.getString("Private");
                if (privateValue != null) {
                    otherFields.remove("Private");
                    formatError = false;
                }
            }
            catch (Exception e) {
                // NOP, formatError == false
            }
        }
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing InvertPrivateKey message.")
            : (State) new NewInvertPrivateKey(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }

    public final String getPrivateValue() { return privateValue; }
}
