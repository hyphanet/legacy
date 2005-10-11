package freenet.message.client.FEC;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.message.client.ClientMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewFECSegmentSplitFile;
import freenet.node.states.FCP.NewIllegal;

/** Message to make a list of SegmentHeaders from
 *  SplitFile metadata.
 */
public class FECSegmentSplitFile extends ClientMessage {

    public static final String messageName = "FECSegmentSplitFile";

    // From wire
    public FECSegmentSplitFile(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw, true);
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing FECSegmentSplitFile message.")
            : (State) new NewFECSegmentSplitFile(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }
}





