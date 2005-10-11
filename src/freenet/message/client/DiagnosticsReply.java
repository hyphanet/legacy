package freenet.message.client;

/**
 * A message that carries back node data.
 *
 * @author oskar
 */
public class DiagnosticsReply extends ClientMessage {

    public static String messageName = "DiagnosticsReply";

    public DiagnosticsReply(long id, long dataLength) {
        super(id);
        this.dataLength = dataLength;
    }

    public String getMessageName() {
        return messageName;
    }
}
