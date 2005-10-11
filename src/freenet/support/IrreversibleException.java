package freenet.support;

/**
 * Thrown when a second attempt is made to change the state of
 * an irreversible.
 *
 * @author oskar
 */

public class IrreversibleException extends RuntimeException { 

    IrreversibleException() {
        super("Second attempt to change the state of an irreversible object");
    }

}
