/*
 * Created on Mar 22, 2004
 */
package freenet.transport;

import java.io.IOException;
import java.nio.channels.SelectableChannel;

/**
 * @author Iakin
 * An interface for classes that conducts asynchronous writing of data over TCP connection(s) 
 */
public interface ThrottledAsyncTCPWriteManager extends ThrottledAsyncTCPCommunicationManager {
	public static final int NEGOTIATION = -10;
	public static final int MESSAGE = 0;
	public static final int TRAILER = 1;
	
	//TODO: Replace selectablechannel by some abstraction?!
	public boolean send(byte [] data, int offset, int len, SelectableChannel destination, 
			   NIOWriter client, int priority) throws IOException;
	 
	/**
	* this method adds a byte[] to be sent to a Channel. 
	* it should be called from a different thread 
	* return value: false if there's a job already on this channel.
	*/
 	//TODO: Replace selectablechannel by some abstraction?!
	public boolean send(byte [] data, SelectableChannel destination, NIOWriter client, 
							   int priority) throws IOException;
	
	//TODO: Where to put this one??
	public void onClosed(SelectableChannel sc);
}
