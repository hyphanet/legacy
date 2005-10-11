package freenet.client.events;
import freenet.client.*;

/**
 * The StateReachedEvent is produced each time the library
 * transitions from one state to another.  
 *
 * @author oskar
 */
public class StateReachedEvent implements ClientEvent {
    public static final int code = 0x00;
    private int state;
    
    public StateReachedEvent(int state) {
        this.state = state;
    }
    
    public final String getDescription() {
        return "State " + Request.nameOf(state) + " reached.";
    }
        
    public final int getCode() {
        return code;
    }

    /**
     * Returns the new state of the library
     */
    public final int getState() {
        return state;
    }
}
