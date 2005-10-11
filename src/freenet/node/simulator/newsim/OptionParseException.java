package freenet.node.simulator.newsim;

/**
 * Exception used in parsing simulator options
 */
public class OptionParseException extends Exception {

    public OptionParseException(String message) {
        super(message);
    }

    public OptionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
