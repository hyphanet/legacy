package freenet;
import java.io.*;

/**
 * Listeners are generalized ServerSockets.
 */

public interface Listener {

    /**
     * Return the Address this is listening on.
     */
    ListeningAddress getAddress();

    /**
     * Accept a new connection. Locks until one comes in.
     */
    Connection accept() throws IOException;

    /**
     * Stes the timeout after which a InterruptedIOException will be
     * generated on calls to accept.
     */
    void setTimeout(int n) throws IOException;

    /**
     * Stop listening on this address.
     */
    void close() throws IOException;
}
