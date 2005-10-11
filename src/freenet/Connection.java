package freenet;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/**
 * Connections are raw streams of data between two peers.
 *
 * @author oskar (mostly)
 */
public abstract class Connection {

    //protected boolean initialized=false;
    //public Object initLock=new Object();

    protected final Transport t;

    protected Connection(Transport t) {
        this.t = t;
    }

    /**
     * Generic object containing info about this
     * Connection, for use by the protocol
     */
    public Object info;

    /**
     * Returns the incoming stream on this connection.
     */
    public abstract InputStream getIn();

    /**
     * Returns the outgoing stream on this connection.
     */
    public abstract OutputStream getOut();

    /**
     * Closes the connection
     */
    public abstract void close();
    
    /**
     * Closes the connection
     * @param blocking - if true, block and wait for close() to finish,
     * if false, do blocking close() ourself
     */
    public abstract void close(boolean blocking);
    
    /**
     * Returns the transport of this Connection.
     */
    public final Transport getTransport() {
        return t;
    }

    /**
     * Sets the amount of time a read off this connection should
     * lock before timing out. 
     * @param timeout The time in milliseconds. Zero means no timeout
     */
    public abstract void setSoTimeout(int timeout) throws IOException;

    /**
     * Gets the amount of time a read off this connection should
     * lock before timing out. 
     **/
    public abstract int getSoTimeout() throws IOException;

    /**
     * Get the full address of this connection of the listener
     * this connection is from.
     * @param  lstaddr   The ListeningAddress of the listener.
     * @return          The full address of the Listener.
     */
    public abstract Address getMyAddress(ListeningAddress lstaddr);
    /**
     * Get the full address of the listener this connection is from. 
     * @return     A full address. The Listening part of the address
     *             does not have to be correct for outgoing connections.   
     */
    public abstract Address getMyAddress();

    /**
     * Get the full address of this connection of the listener
     * this connection is to.
     * @param  lstaddr   The ListeningAddress of the listener.
     * @return          The full address of the Listener.
     */
    public abstract Address getPeerAddress(ListeningAddress lstaddr);
    /**
     * Get the full address of the listener this connection is to.
     * @return     A full address. The Listening part of the address
     *             does not have to be correct for incoming connections.
     */
    public abstract Address getPeerAddress();
    
    /**
     * Call to enable the throttling, which is off by default even if 
     * explicitly requested. This is so that Connections can determine
     * whether to throttle based on various init-time criteria, but then
     * not throttle until initialization is finished.
     */
    public abstract void enableThrottle();
    
    /**
     * Returns true if the connection input side has closed or been closed
     */
    public abstract boolean isInClosed();
    
    /**
     * Returns true if the connection output side has closed or been closed
     */
    public abstract boolean isOutClosed();
    
    /**
     * Returns true if the connection is fully set up and ready to
     * be used
     */
    //public boolean ready() {
    //    return initialized;
    //}

    /**
     * Called when a presentation deems the connection ready
     */
    //public void setReady() {
    //    initialized=true;
    //}
}


