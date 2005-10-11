package freenet;

/**
 * This exception is encountered when a component fails create or resolve an
 * Address.
 **/
public class BadAddressException extends Exception {

    public BadAddressException(String comment) {
	super(comment);
    }

}
