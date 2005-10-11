package freenet.message;
import java.io.IOException;

import freenet.BaseConnectionHandler;
import freenet.InvalidMessageException;
import freenet.RawMessage;
import freenet.support.KeyList;

/**
 * The final message in an AnnouncementChain (phew).
 *
 * @author oskar
 */

public class AnnouncementComplete extends AnnouncementTrailerMessage {

    public AnnouncementComplete(BaseConnectionHandler ch, RawMessage raw) throws InvalidMessageException {
        super(ch, raw);
    }

    public AnnouncementComplete(long id, KeyList keys) {
        super(id, keys);
    }
    
    public static final String messageName = "AnnouncementComplete";

    public String getMessageName() {
        return messageName;
    }

    public int getPriority() {
        return -20; // very important!
    }

    protected String getTrailingFieldName() {
        return "Keys";
    }
    
    public void readKeys(int limit) throws IOException {
        if (in == null)
            return;
        super.readKeys(limit);
    }
}
