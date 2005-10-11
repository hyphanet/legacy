package freenet.client.metadata;

/**
 * Exception thrown when a part FieldSet doesn't contain the necessary parts.
 *
 * @author oskar
 */
public class InvalidPartException extends Exception {

    public InvalidPartException(String message) {
        super(message);
    }
}
