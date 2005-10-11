package freenet.message.client.FEC;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.message.client.ClientMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewFECMakeMetadata;
import freenet.node.states.FCP.NewIllegal;

/** Message to make SplitFile metadata from a list of 
 *  segment header, block map pairs.
 */
public class FECMakeMetadata extends ClientMessage {

    public static final String messageName = "FECMakeMetadata";

    private String description = null;
    private String mimeType = null;
    private String checksum = null;

    // From wire
    public FECMakeMetadata(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw, true);
        try {
            description = otherFields.getString("Description");
            mimeType = otherFields.getString("MimeType");
            checksum = otherFields.getString("Checksum");
        }
        catch (Exception e) {
            e.printStackTrace();
            formatError = true;
        }
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing FECMakeMetadata message.")
            : (State) new NewFECMakeMetadata(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }

    public final String getDescription() { return description; }
    public final String getMimeType() { return mimeType; }
    public final String getChecksum() { return checksum; }
}





