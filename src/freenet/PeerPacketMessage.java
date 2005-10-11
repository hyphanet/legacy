package freenet;

/**
 * A really low-level message.
 * We have 3 levels:
 * PeerPacketMessage - FNP messages, trailer chunks, flow control messages, etc.
 * RawMessage - FNP or FCP messages, low level i.e. not properly parsed yet.
 * Message - messages used by the state machine. See freenet/message/
 * @author amphibian
 */
public interface PeerPacketMessage {

	/** Set the message up to send on a connection using a specific
	 * Presentation. Also resets message finished. Can be called
	 * multiple times.
	 * @param p the presentation to use to transform the message into
	 * a RawMessage and thence to a byte array. Can be null to clear the
	 * cached message.
	 * @param onlyIfNeeded if true, don't use the new Presentation if there
	 * is an old one.
	 */
	void resolve(Presentation p, boolean onlyIfNeeded);

	/**
	 * @return the Identity of the peer this message will be sent to/from.
	 */
	Identity getIdentity();

	/**
	 * @return the length of any attached trailing field
	 */
	long trailerLength();

	/**
	 * Notify the callback that we failed to send the message.
	 * @param sfe the excuse exception
	 */
	void notifyFailure(SendFailedException sfe);

	// Packet priorities
	static final int EXPENDABLE = 0;
	static final int NORMAL = 1;

	/**
	 * @return the priority of the message, see above.
	 */
	int getPriorityClass();

	/**
	 * The priority delta for this particular message on the packet
	 */
	int getPriorityDelta();
	
	/**
	 * @return true if there is an attached trailing field
	 */
	boolean hasTrailer();

	/**
	 * @return the length in bytes of the message when sent on the 
	 * most recently specified Presentation
	 */
	int getLength();

	/**
	 * @return absolute time at which this message should be timed out,
	 * even if it has not been sent.
	 */
	long expiryTime();

	/**
	 * @return true if this message is terminal for the link.
	 */
	boolean isCloseMessage();

	/**
	 * @return the message in byte[] form, ready to send over the network.
	 * Should include type and length if muxing.
	 */
	byte[] getContent();

	/**
	 * Notify that the message send succeeded.
	 * @param tw the TrailerWriter to write the trailing field to, IF we are
	 * using classic mode. In muxing mode, the trailerwriter is handled 
	 * differently.
	 */
	void notifySuccess(TrailerWriter tw);

	/**
	 * Do whatever it is we are supposed to do with a new incoming 
	 * message.
	 */
	void execute();

	/**
	 * @return the type ID for this class of message
	 */
	int getTypeCode();
	
	// Do not change these numbers, they are part of the protocol
	static final int TYPE_IDENTIFY = 0;
	static final int TYPE_MESSAGE = 1;
	static final int TYPE_TRAILER_CHUNK = 2;
	static final int TYPE_TRAILER_CREDIT = 3;
	static final int TYPE_TRAILER_ABORT = 4;
	static final int TYPE_VOID = 5;
	/** Sent from the receiver to the sender to request that the sender
	 * not send any more chunks on the given trailer ID.
	 */
	static final int TYPE_TRAILER_KILL = 6;
	static final int TYPE_RATE_LIMITING_VIOLATED = 8;
	static final int TYPE_GO_AWAY = 9;
	static final int TYPE_DETECT = 10;
	static final int TYPE_REQUEST_INTERVAL = 11;
	
	/**
	 * @return the trailer ID that this packet starts, if this packet has a
	 * trailer. Only used if multiplexing.
	 */
	int trailerMuxCode();

	/**
	 * @return true if this message is a request, in the sense that it should not be
	 * sent until rate limiting is set up.
	 */
	boolean isRequest();

    /**
     * @return true if this message carries a Minimum Request Interval
     */
    boolean hasMRI();

    /**
     * @return the Minimum Request Interval contained on this message, if any.
     * Negative if none. Note that this is intended to be the MRI that will
     * be sent out, if any, not the MRI received on this packet, if there is
     * any ambiguity.
     */
    double sendingMRI();

    /**
     * @return true if this message was an insert message carrying a trailer.
     * Note that the actual trailer chunks won't return true.
     */
    boolean wasInsert();
}
