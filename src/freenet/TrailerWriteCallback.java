package freenet;

/**
 * Interface for a client class (e.g. SendData) writing a trailer to the network.
 * Callback class implemented by the client. @see freenet.TrailerWriter
 * @author amphibian
 */
public interface TrailerWriteCallback {
    /**
     * Indicates that the write failed and the connection closed
     */
    void closed();
    
    /**
     * Indicates that the write succeeded and the caller should send another one
     */
    void written();
    
    /**
     * Get the number of bytes currently ready to send reasonably quickly
     */
    long bytesAvailable();
}
