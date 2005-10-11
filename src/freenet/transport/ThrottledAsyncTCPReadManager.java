/*
 * Created on Mar 22, 2004
 *
 */
package freenet.transport;

import java.nio.channels.SocketChannel;

/**
 * @author Iakin
 */
public interface ThrottledAsyncTCPReadManager extends ThrottledAsyncTCPCommunicationManager{

	//TODO: Where to put these
	public void scheduleMaintenance(SocketChannel cb,NIOReader attachment);
	public void unregister(Object attachment);
}
