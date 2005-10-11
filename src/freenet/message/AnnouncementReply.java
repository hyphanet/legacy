package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.support.HexUtil;

/**
 * The message returned when an announcement has reached it's final node
 *
 * @author oskar
 */

public class AnnouncementReply extends NodeMessage {

    public static final String messageName = "AnnouncementReply";

    private byte[] returnVal;

    public AnnouncementReply(long id, byte[] returnVal) {
        super(id, null);
        this.returnVal = returnVal;
    }

    public AnnouncementReply(BaseConnectionHandler ch, RawMessage raw) 
        throws InvalidMessageException {

        super(ch, raw);

        String returnS = otherFields.getString("ReturnValue");
        if (returnS == null || returnS.length()==0) {
            throw new InvalidMessageException("No return value");
        } else {
            try {
                returnVal = HexUtil.hexToBytes(returnS);
            } catch (NumberFormatException e) {
                throw new InvalidMessageException("Malformed return value");
            }
            otherFields.remove("ReturnValue");
        }
    }

    public RawMessage toRawMessage(Presentation p, PeerHandler ph) {
        RawMessage raw = super.toRawMessage(p,ph);
        //raw.messageType = messageName;
        raw.fs.put("ReturnValue",HexUtil.bytesToHex(returnVal));
        return raw;
    }
    
    public final boolean hasTrailer() {
	return false;
    }
    
    public final long trailerLength() {
	return 0;
    }
    
    public String getMessageName() {
        return messageName;
    }

    /**
     * Retuns the contents of the ReturnValue field
     */
    public byte[] getReturnValue() {
        return returnVal;
    }

    public int getPriority() {
        return -19; // important
    }

}
