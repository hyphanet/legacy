package freenet.client.events;
import freenet.client.ClientEvent;

/**
 * An ExceptionEvent is produced when the library internally generated
 * an exception.  The exception (if fatal) will be propogated to 
 * the client via this event.
 *
 * @author oskar
 **/
public class ExceptionEvent implements ClientEvent {
    public static final int code = 0x03;
    private Exception e;
        
    public ExceptionEvent(Exception e) {
        this.e = e;
    }

    public String getDescription() {
	// Useful in bug reports etc.
        e.printStackTrace();
        return "A fatal exception occured while processing: " + e;
    }

    public int getCode() {
        return code;
    }

    /**
     * Rethrows this exception.
     *
     **/
    public void rethrow() throws Exception {
        throw e;
    }
    
    public Exception getException() {
	return e;
    }
    
}
