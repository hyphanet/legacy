package freenet.message.client;

import freenet.*;
import freenet.node.State;
import freenet.node.states.FCP.*;
import freenet.support.Fields;

/** This is the message for FCP ClientGet
  */
public class ClientGet extends ClientRequest {

    public static final String messageName = "ClientGet";

    private boolean metadataHint = false;
    private long timeSec = -1;

    // From wire
    public ClientGet(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw);

        if (!formatError) {
            try {
                String mdHintAsString = otherFields.getString("MetadataHint");
                if (mdHintAsString != null) {
                    metadataHint = Fields.stringToBool(mdHintAsString, false);
                    otherFields.remove("MetadataHint");
                }

                String timeSecAsString = otherFields.getString("RedirectTimeSec");
                if (timeSecAsString != null) {
                    timeSec = Long.parseLong(timeSecAsString, 16);
                    otherFields.remove("RedirectTimeSec");
                }
            }
            catch (Exception e) {
                formatError = true;
            }
        }
    }

    public State getInitialState() {
        return formatError
            ? (State) new NewIllegal(id, source.getPeerHandler(), "Error parsing ClientGet message.")
            : (State) new NewClientGet(id, source.getPeerHandler(), metadataHint, uri, timeSec);
    }

    public String getMessageName() {
        return messageName;
    }
}





