package freenet.client.listeners;

import freenet.client.ClientEvent;
import freenet.client.ClientEventListener;
import freenet.support.Logger;

/**
 * Event handeling for clients.
 * 
 * @author oskar
 */
public class EventLogger implements ClientEventListener {

    private Logger log;

    /**
     * Creates a new EventLogger.
     * 
     * @param log
     *            The Logger to log events with.
     */
    public EventLogger(Logger log) {
        this.log = log;
    }

    /**
     * Set the logger to which this logs.
     */
    public void setLogger(Logger log) {
        this.log = log;
    }

    /**
     * Returns the logger to which this logs events.
     */
    public Logger getLogger() {
        return log;
    }

    /**
     * Logs an event
     * 
     * @param ce
     *            The event that occured
     */
    public void receive(ClientEvent ce) {
        log.log(ce, ce.getDescription(), Logger.NORMAL);
    }
}