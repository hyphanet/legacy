/*
 * Created on Mar 22, 2004
 */
package freenet.transport;

import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;

/**
 * @author Iakin
 * An interface for classes that conducts asynchronous transfering of data over TCP connection(s)
 */
public interface AsyncTCPCommunicationManager {
	public void register(SelectableChannel ch, Object attachment);
	
	//TODO: Where in the hiearchy should this method go really?..
	/*Will queue a channel for closure, the callback will be notified when the channel actually becomes closed*/	
	public void queueClose(SocketChannel chan, NIOCallback nc);
}
