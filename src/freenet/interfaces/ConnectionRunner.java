package freenet.interfaces;

import freenet.Connection;

/**
 * After the Interface has allowed the connection and acquired
 * a thread, the ConnectionRunner will be executed on that thread.
 */
public interface ConnectionRunner {

    /**
     * Handles the connection.  Must be thread-safe.
     * Should arrange for the closing of the connection.
     */
    void handle(Connection conn);

    /** 
     * Late initialization - called at start of Interface's run()
     */
    void starting();
    
    /**
     * NIO - do we need the caller to allocate a thread first, or can we run
     * in-place (i.e. register with nio loops etc).
     */
    boolean needsThread();
}

