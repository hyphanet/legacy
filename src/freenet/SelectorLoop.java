package freenet;

import java.nio.channels.*;
import java.net.Socket;

/**
 * an interface for a generic selector loop.
 */

 public interface SelectorLoop extends Runnable {
 	/**
	 * registers a channel with attachment
	 */
 	public void register(SelectableChannel ch, Object attachment)throws IllegalBlockingModeException;

	/**
	 * registers a socket with attachment
	 */
 	public void register(Socket sock, Object attachment)throws IllegalBlockingModeException;

	/**
	 * unregisters a registered channel
	 */
	public void unregister(SelectableChannel ch);
	
	/**
	 * unregisters a channel by attachment
	 */
	public void unregister(Object attachment);
	
	/**
	 * tests whether the selector is open
	 */
	public boolean isOpen();
	
	/**
	 * closes and finalizes the selectorLoop
	 */
	public void close();
 }