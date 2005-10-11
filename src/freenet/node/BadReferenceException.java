package freenet.node;

/**
 * Exception thrown when a NodeReference is corrupt.
 *
 * @author oskar
 */

public class BadReferenceException extends Exception {

    public BadReferenceException(String s) {
        super(s);
    }

    public BadReferenceException() {
        super();
    }
    
}
