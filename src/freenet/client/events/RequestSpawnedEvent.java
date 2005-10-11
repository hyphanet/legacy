package freenet.client.events;
import freenet.client.*;

/** This happens when AutoClient spawns a request. */
public class RequestSpawnedEvent implements ClientEvent {

    public static final int code = 0xA2;
    private Request r;
    
    public RequestSpawnedEvent(Request r) {
	this.r = r;
    }
    
    /** Returns the Request spawned, or null to signal completion. */
    public Request getRequest() {
	return r;
    }
    
    public String getDescription() {
        return r != null ? "Request spawned." : "Request chain complete.";
    }

    public int getCode() {
        return code;
    }
    
}

