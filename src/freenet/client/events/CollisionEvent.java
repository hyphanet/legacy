package freenet.client.events;
import freenet.client.ClientEvent;
import freenet.client.ClientKey;

/**
 * A CollisionEvent occurs when an insert fails because
 * the key already existed on Freenet.
 *
 * @author oskar
 **/
public class CollisionEvent implements ClientEvent {
    public static final int code = 0x07;
    protected ClientKey key;

    public CollisionEvent(ClientKey k) {
        key=k;
    }

    /**
     * Return the key on which the insert collided
     */
    public ClientKey getKey() {
        return key;
    }

    public String getDescription() {
        return "'"+key+"' already exists in Freenet. (Key Collision)";
    }
    
    public int getCode() {
        return code;
    }
    
}

