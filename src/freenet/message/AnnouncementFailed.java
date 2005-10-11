package freenet.message;
import freenet.*;
import freenet.support.Fields;

/**
 * Message that indicates that an Announcement has failed.
 *
 * @author oskar
 */

public class AnnouncementFailed extends NodeMessage {

    
    public static final String messageName = "AnnouncementFailed";

    // see also freenet.node.states.announcing.FailedAnnouncement
    public static final int KNOWN_ANNOUNCEE = 0x01;
    public static final int CORRUPT_EXECUTE = 0x02;
    public static final int UNACCEPTABLE_HTL = 0x03;
    public static final int TOO_MANY_RESTARTS = 0x04;
    public static final int NO_EXECUTE = 0x05;

    public static String code(int code) {
        if (code == KNOWN_ANNOUNCEE)
            return "Announcee already known to network";
        if (code == CORRUPT_EXECUTE)
            return "Corrupt data in AnnouncementExecute";
        if (code == UNACCEPTABLE_HTL)
            return "Announcement HTL too high";
        if (code == TOO_MANY_RESTARTS)
            return "The announcement was restarted too many times.";
	if (code == NO_EXECUTE)
	    return "The announcement timed out waiting for the "+
		"execute message";
        else
            return "Code: " + Integer.toString(code);
    }
    
    private int reason;
    
    public AnnouncementFailed(long id, int reason) {
        super(id, null);
        this.reason = reason;
    }

    public AnnouncementFailed(BaseConnectionHandler ch, RawMessage raw)
        throws InvalidMessageException {

        super(ch, raw);

        String reasonS = otherFields.getString("Reason");
        try {
            if (reasonS == null) 
                reason = 0;
            else {
                reason = (int) Fields.hexToLong(reasonS);
                otherFields.remove("Reason");
            }
        } catch (NumberFormatException e) {
            reason = 0;
        }
    }

    public RawMessage toRawMessage(Presentation p, PeerHandler ph) {
        RawMessage raw = super.toRawMessage(p,ph);
        //raw.messageType = messageName;
        raw.fs.put("Reason",Long.toHexString(reason));

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

    public int getReason() {
        return reason;
    }

    public String reasonName() {
        return code(reason);
    }

    public int getPriority() {
        return -1; // not very important, it happens all the time :(
    }
    
    public String toString() {
        return super.toString() + ": reason="+reasonName();
    }
}

