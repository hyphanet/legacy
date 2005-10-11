package freenet.client;
/**
 * Event handeling for clients.
 *
 * @author oskar
 **/

public interface ClientEvent {

    /**
     * Returns a string descriping the event.
     **/
    public String getDescription();

    /**
     * Returns a code for this event.
     **/
    public int getCode();

}
