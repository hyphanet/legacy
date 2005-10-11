package freenet.node;

/**
 * This exception is thrown to designate events occuring in a terminally
 * bad state.
 *
 * @author Oskar
 */
public class BadStateException extends StateException {

    public BadStateException() {
        super();
    }

    public BadStateException(String comment) {
	super(comment);
    }

}
