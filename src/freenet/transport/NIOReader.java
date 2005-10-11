/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.transport;

import java.nio.*;
public interface NIOReader extends NIOCallback {
/**
	 * this method is called when something is read into a byte buffer.
	 * the return value could indicate whether a complete message was
	 * received.
	 * return values:
	 * 1 : everything went ok, keep us registered
	 * 0 : we got trailing field, unregister us but keep connection open
	 * -1 : error or close message; unregister us and put Connection on close queue
	 */
	public int process(ByteBuffer b);

	/**
	 * @return the ByteBuffer to read into. Null means terminate.
	 */
	public ByteBuffer getBuf();	
	//note to self: the AbstractSelectorLoop will stay with Objects,
	//but the Poller will use this interface cause the listener doesn't
	//need buffers
}
