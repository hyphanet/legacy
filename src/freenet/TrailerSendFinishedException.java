package freenet;

/**
 * Exception thrown when we try to send a chunk on a trailer that
 * has already terminated.
 * @author amphibian
 */
public class TrailerSendFinishedException extends TrailerException {
    // No methods
}
