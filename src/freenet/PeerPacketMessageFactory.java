package freenet;

/**
 * An interface to create PeerPacketMessages from their serialized form.
 * There are several kinds of messages, for example FNP messages,
 * FNC messages, trailer chunks, trailer termination blocks, low level void
 * messages, and identify messages (which should not be regular
 * messages as they are not FNP). 
 * @author amphibian
 */
public interface PeerPacketMessageFactory {

	/**
	 * @return the message type ID code for messages parsed by this
	 * factory.
	 */
	int getMessageTypeCode();
	
	/**
	 * Create a message from serialized form, excluding type and length
	 * prefixes.
	 * @param mch the MuxConnectionHandler the message originated from.
	 * Needed for Identify, trailers, etc etc.
	 * @param buf the buffer to read from
	 * @param offset the offset to start to read at
	 * @param length the length to read
	 * @param needsCopy if true, any bytes kept from the buffer must be copied
	 * into a new byte[]. If false, we can use the existing buffer. Many
	 * implementations won't need to worry about this.
	 * @return a valid message, or null if we can't create one from the
	 * provided data.
	 */
	public PeerPacketMessage create(MuxConnectionHandler mch, byte[] buf, int offset, int length, boolean needsCopy);
}
