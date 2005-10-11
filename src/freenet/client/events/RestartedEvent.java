package freenet.client.events;
import freenet.client.*;

/**
 * The RestartedEvent is produced when either a request was restarted
 * by an upstream node.  The library will then continue to wait for
 * the request to complete.
 *
 * @author oskar
 **/
public class RestartedEvent implements ClientEvent {
    public static final int code = 0x04;
    private int secs;
    private String reason;
    
    public RestartedEvent(long millis, String why) {
        this.secs = (int) (millis / 1000);
        this.reason = why;
    }
    
    public String getDescription() {
        return "The query was restarted somewhere on Freenet after a node failed to reply, rejected, or had to increase the timeout, waiting another " + 
        	secs + " seconds before I give up"+((reason==null)?"":(" (" + reason+")"));
    }
    
    /**
     * Returns the number of seconds the library will continue to wait
     */
    public int getTime() {
        return secs;
    }

    public int getCode() {
        return code;
    }
}
