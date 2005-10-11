package freenet.client.events;
import freenet.client.*;

/**
 * Produced when the InsertReply is received
 * (or the Pending message, from FCP).
 */
public class PendingEvent implements ClientEvent {
    
    public static final int code = 0x05;
    
    private int secs;
    
    public PendingEvent(long millis) {
        this.secs = (int) (millis / 1000);
    }
    
    public final String getDescription() {
        return "The insert has been accepted;  waiting up to "
               + secs + " seconds for the StoreData";
    }
    
    /**
     * Returns the number of seconds the library will continue to wait
     */
    public final int getTime() {
        return secs;
    }

    public final int getCode() {
        return code;
    }
}


