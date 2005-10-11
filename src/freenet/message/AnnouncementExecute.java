package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.support.KeyList;

/**
 * The third message in a complete announcement chain. This message 
 * contains the revealed commit values (trailing), as well as Alice' signature 
 * of the final result.
 *
 * @author oskar
 */

public class AnnouncementExecute extends AnnouncementTrailerMessage {

    
    public static final String messageName = "AnnouncementExecute";

    private String refSignature;

    public AnnouncementExecute(long id, KeyList vals, String refSignature) {
        super(id, vals);
        this.refSignature = refSignature;
    }

    public AnnouncementExecute(BaseConnectionHandler ch, RawMessage raw) 
        throws InvalidMessageException {
        
        super(ch, raw);

        refSignature = otherFields.getString("RefSignature");
        
        if (refSignature == null || refSignature.length()==0) {
            throw new InvalidMessageException("Lacking reference signature");
        } else if (raw.trailingFieldLength == 0) {
            throw new InvalidMessageException("Lacks value list");
        }

        otherFields.remove("RefSignature");
    }

    public RawMessage toRawMessage(Presentation p, PeerHandler ph) {
        RawMessage raw = super.toRawMessage(p,ph);
        raw.fs.put("RefSignature", refSignature);
        return raw;
    }
    
    public String getMessageName() {
        return messageName;
    }

    public String getRefSignature() {
        return refSignature;
    }

    public int getPriority() {
        return -19; // very important but less than AnnouncementComplete
    }

    protected String getTrailingFieldName() {
        return "Values";
    }
}
