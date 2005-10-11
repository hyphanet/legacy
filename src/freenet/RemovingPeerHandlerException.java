package freenet;

/**
 * Exception thrown when we try to use a PeerHandler while it is
 * being removed from the OCM. Shouldn't be thrown very often!
 * @author amphibian
 */
public class RemovingPeerHandlerException extends Exception {
    // No unusual methods, just a different name
}
