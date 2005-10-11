package freenet;

/**
 * Indicates an error while trying to open a Listener on a transport.
 */
public class ListenException extends Exception {

    public ListenException(String s) { 
	super(s);
    }

}
