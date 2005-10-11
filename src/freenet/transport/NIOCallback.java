/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */

//this probably belongs elsewhere
package freenet.transport;



/**
 * Attachments to Selection Keys should implement this interface
 */
public interface NIOCallback {

	
    
    /**
     * notifies that the channel/connection has been closed
     */
    public void closed();
    
	/**
	 * notifies that the channel/connection will be closed ASAP
	 */
	public void queuedClose();
	
    /**
     * notifies that we were registered
     */
    public void registered();
    
    /**
     * and unregistered
     */
    public void unregistered();
	
	/**
	 * @return true if the connection should be throttled with the
	 * default bandwidth limiter, false if it should not
	 */
	public boolean shouldThrottle();
	
	/**
	 * @return true to count bytes toward throttle, without actually
	 * slowing them down - so it leaches off other throttled connections
	 */
	public boolean countAsThrottled();
}
