package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.support.Fields;
/**
 * The message sent when a DataRequest runs out of htl without finding the 
 * data.
 *
 * @author oskar
 */

public class DataNotFound extends NodeMessage {

    public static final String messageName = "DataNotFound";

    private long timeOfQuery = 0;

    public DataNotFound(long idnum) {
	super(idnum, null);
        timeOfQuery = System.currentTimeMillis();
    }

    public DataNotFound(long idnum, long timeOfQuery) {
        super(idnum, null);
        this.timeOfQuery = timeOfQuery;
    }

    public DataNotFound(BaseConnectionHandler source, 
                        RawMessage raw) throws InvalidMessageException {
	super(source, raw);
        String tsq = otherFields.getString("TimeSinceQuery"); //TODO: rename this field to TimeSinceQuery since that is what it is..
        if (tsq != null) {
            otherFields.remove("TimeSinceQuery");
            try {
                timeOfQuery = 
                    System.currentTimeMillis() - Fields.hexToLong(tsq);
            } catch (NumberFormatException e) {
            }
        }
        if (timeOfQuery <= 0) {
            timeOfQuery = System.currentTimeMillis();
        }
    }

    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
	RawMessage raw=super.toRawMessage(t,ph);
        raw.fs.put("TimeSinceQuery", 
                   Long.toHexString(System.currentTimeMillis() - timeOfQuery));
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

    public long getTimeOfQuery() {
        return timeOfQuery;
    }
    
    //Mrgg.. this method should be wasted as soon as the name of the field has been changed
    //to what it really should be named (see above)
	public long getTimeSinceQuery() {
		return getTimeOfQuery();
	}

    public int getPriority() {
        return -4; // between Requests and Data* messages
    }

}


