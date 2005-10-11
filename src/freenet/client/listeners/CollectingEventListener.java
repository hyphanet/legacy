package freenet.client.listeners;
import java.util.Vector;
import java.util.Enumeration;
import freenet.client.ClientEventListener;
import freenet.client.ClientEvent;
/**
 * A ClientEventListener that collects the events and returns them as an
 * enumeration. Also stores a timestamp for each of the events recieved.
 *
 * @author oskar
 */

public class CollectingEventListener implements ClientEventListener {
    
    private final Vector events;
    private final Vector timestamps;
    private final int maxLength;
    
    public CollectingEventListener() {
        maxLength = -1;
        events = new Vector();
        timestamps = new Vector();
    }
    
    public CollectingEventListener(int maxLength) {
        this.maxLength = maxLength;
        events = new Vector(maxLength+1);
        timestamps = new Vector(maxLength+1);
    }
    
    public void receive(ClientEvent ce) {
        events.addElement(ce);
        timestamps.addElement(new Long(System.currentTimeMillis()));
        if(maxLength > 0 && events.size() > maxLength){
            events.removeElementAt(0);
            timestamps.removeElementAt(0);
        }
    }
    
    public Enumeration events() {
        return events.elements();
    }
    
    /** 
     * @return an Enumeration containing the timestamps for the events.
     * The timestamps are boxed in Long:s.
     */
    public Enumeration timestamps() {
        return timestamps.elements();
    }
    
    public Vector asVector() {
        return events;
    }
}
