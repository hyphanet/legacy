package freenet;

/**
 * Exception thrown when we try to send a trailer chunk when we are
 * already sending one on the same ID.
 * @author amphibian
 */
public class AlreadySendingTrailerChunkException extends TrailerException {
// No special members are necessary
}
