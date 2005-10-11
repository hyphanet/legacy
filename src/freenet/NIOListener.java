package freenet;
import java.io.*;
import freenet.interfaces.NIOInterface;
import java.net.Socket;

/**
 * NIOListeners are listeners that listen on selectors
 * most of this code is copy/pasted from Listener.java
 */

public interface NIOListener {


    /**
     * Return the Address this is listening on.
     */
    ListeningAddress getAddress();

    /**
     * Stes the timeout after which a InterruptedIOException will be
     * generated on calls to accept.
     */
    void setTimeout(int n) throws IOException;
    
    /**
     * register with a selector
     */
    void register(SelectorLoop loop, NIOInterface dispatcher);
    
    /**
     * callback indicating we have a connection ready
     */
    void accept(Socket sock) throws IOException;

    /**
     * Stop listening on this address.
     */
    void close() throws IOException;
}