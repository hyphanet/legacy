package freenet.client.events;
import freenet.client.*;

/**
 * The StreamEvent is the superclass for all transfer-related events
 *
 * @author oskar
 **/
public abstract class StreamEvent implements ClientEvent {

    protected long bytes;

    protected StreamEvent(long bytes) {
        this.bytes = bytes;
    }

    /**
     * Returns the number of bytes read/written when this Event occured.
     */
    public final long getProgress() {
        return bytes;
    }
}
