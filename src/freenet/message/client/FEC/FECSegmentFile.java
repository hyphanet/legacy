package freenet.message.client.FEC;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.message.client.ClientMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewFECSegmentFile;
import freenet.node.states.FCP.NewIllegal;

/** This is the FCP message to create a set of SegmentHeaders
 *  to FEC Encode a file with. 
 */
public class FECSegmentFile extends ClientMessage {

    public static final String messageName = "FECSegmentFile";

    private String algoName = null;
    private long fileLength = -1;

    // From wire
    public FECSegmentFile(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw);

        if (!formatError) {
            try {
                algoName = otherFields.getString("AlgoName");
                if (algoName != null) {
                    otherFields.remove("AlgoName");
                }
                String fileLengthAsString = otherFields.getString("FileLength");
                if (fileLengthAsString != null) {
                    otherFields.remove("FileLength");
                    fileLength = Long.parseLong(fileLengthAsString, 16);
                }
            }
            catch (Exception e) {
                formatError = true;
            }
        }
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing FECSegmentFile message.")
            : (State) new NewFECSegmentFile(id, source.getPeerHandler());
    }

    public String getMessageName() {
        return messageName;
    }

    public final String getAlgoName() { return algoName; }
    public final long getFileLength() { return fileLength; }
}







