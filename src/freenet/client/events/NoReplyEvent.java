package freenet.client.events;
import freenet.client.ClientEvent;
/**
 * Generated when the client fails because no response is heard from 
 * Freenet within the expected time.
 *
 * @author oskar
 */

public class NoReplyEvent implements ClientEvent {
    public static final int code = 0x0A;
    
    public NoReplyEvent() {
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return "No reply was received from Freenet within the calculated expected time. Are you not running a local node?";
    }
}
