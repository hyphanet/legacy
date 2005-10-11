package freenet.client.listeners;

import freenet.client.*;
import freenet.client.events.*;

/**
 * Listens for a Collision event
 *
 * @author Eric Armstrong
 **/

public class ClientCollisionListener implements ClientEventListener {
    
    protected boolean collision = false;

    public ClientCollisionListener() {}

    /**
     * Has a Collision happened?
     * @return true if one has
     **/
    public boolean collisionHappened() {
        return collision;
    }

    public void receive(ClientEvent ce) {
        if (ce instanceof CollisionEvent)
            collision = true;
    }
}
