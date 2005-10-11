package freenet.client.listeners;

import freenet.ConnectFailedException;
import freenet.client.*;
import freenet.client.events.*;
import java.util.*;

/** Use the ExceptionListener to track ExceptionEvents
  */
public class ExceptionListener implements ClientEventListener {

    private Stack exceptions  = new Stack();
    private boolean nodeError = false;

    public void receive(ClientEvent ce) {
        if (ce instanceof ExceptionEvent) {
            try {
                ((ExceptionEvent) ce).rethrow();
            } catch (Exception e) {
                if (e instanceof ConnectFailedException) nodeError = true;
                exceptions.push(e);
            }
        }
    }

    /** @return true if a node connection failure occurred */
    public boolean nodeError() {
        return nodeError;
    }

    /**
     * Returns an array of any exceptions that occured before reaching
     * this state.
     * @return An array of the Exceptions that have occured during the
     *         the request. If no exceptions occured, then null.
     **/
    public Exception[] getExceptions() {
        if (exceptions.empty())
            return null;
        else {
            Exception[] array = new Exception[exceptions.size()];
            exceptions.copyInto(array);
            return array;
        }
    }
}

