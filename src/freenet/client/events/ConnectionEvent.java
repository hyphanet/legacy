package freenet.client.events;
import freenet.client.*;
import freenet.*;

/**
 * The super class of all connection related events
 *
 * @author oskar
 **/
public abstract class ConnectionEvent implements ClientEvent {
    protected Peer peer;
    protected String comment;
    protected String messageName;

    protected ConnectionEvent(Peer peer, ClientMessageObject m, String comment) {
        this.peer = peer;
        this.comment = comment;
        this.messageName = m.getClass().getName();
    }

}
