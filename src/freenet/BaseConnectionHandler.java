package freenet;

import java.net.Inet4Address;

import freenet.node.NodeReference;
import freenet.transport.NIOReader;
import freenet.transport.NIOWriter;

/**
 * Base class for ConnectionHandler and MuxConnectionHandler.
 * @author amphibian
 */
public interface BaseConnectionHandler extends NIOWriter, NIOReader {

	public static final int CHANNEL_CLOSED_PORTNUMBER = -5;
	public static final int CONNECTION_TERMINATED_PORTNUMBER = -4;
	
	/**
	 * @return The number of bytes that are available to send, 
	 * immediately, from the trailing fields currently transmitting on this
	 * connection.
	 */
	long trailerLengthAvailable();

	/**
	 * @return The Identity of the node on the other end.
	 */
	Identity peerIdentity();

	/**
	 * @return The NodeReference of the node on the other end,
	 * if known, otherwise null.
	 */
	NodeReference targetReference();

	/**
	 * @return The Presentation being used on this connection.
	 */
	Presentation getPresentation();

	/**
	 * Set and register on a PeerHandler.
	 * @param ph the PeerHandler for the node we are connected to
	 * @throws RemovingPeerHandlerException if we have a race 
	 * condition where the PH is being removed while we are adding to it.
	 */
	void setPeerHandler(PeerHandler ph) 
		throws RemovingPeerHandlerException;

	/**
	 * Was this connection originally an outgoing connection, or was it
	 * created for an incoming connection?
	 * @return true if we originated the connection
	 */
	boolean isOutbound();

	/**
	 * @return the Address of the node on the other end.
	 * This is not used for communication, only for stats, so it is
	 * acceptable to get the port wrong i.e. to return the address of
	 * the other side of the link, even though that is not the address
	 * we would contact due to not binding outgoing connections to
	 * the listenPort.
	 */
	Address peerAddress();

	/**
	 * @return true if we are receiving one or more trailing fields.
	 */
	boolean receiving();

	/**
	 * @return true if we are sending one or more trailing fields.
	 * In the sense that if we return true, we are blocked. Hence
	 * mux connections will always return false.
	 */
	boolean blockedSendingTrailer();

	/**
	 * @return true if we are open to send messages, false if our send
	 * side has closed.
	 */
	boolean isOpen();

	/**
	 * Terminate the connection. Close it, permanently. Closes the 
	 * underlying link and informs any waiting messages or trailers of
	 * failure.
	 */
	void terminate();

	/**
	 * Register ourselves on the OpenConnectionManager.
	 */
	void registerOCM();

	/**
	 * @return the number of messages received so far
	 */
	int messagesReceived();

	/**
	 * @return the number of messages sent so far
	 */
	int messagesSent();

	/**
	 * @return the time in milliseconds that this connection has been idle
	 * for
	 */
	long idleTime();

	/**
	 * @return the time in milliseconds that this connection has been 
	 * up for
	 */
	long runTime();

	/**
	 * @return true if we are currently sending a PeerPacket i.e. we
	 * have sent it to WSL and haven't had a completion acknowledgement
	 * yet.
	 */
	boolean isSendingPacket();

	/**
	 * Send a PeerPacketMessage immediately if possible. The 
	 * BaseConnectionHandler will call getPacket() on its PeerHandler with
	 * this message included, and send the resulting packet. Or it will
	 * return false, to indicate that it cannot send a packet right now, 
	 * usually because of a race condition.
	 * @param ppm the message to send
	 * @return true if we sent the packet, false if we failed to send the 
	 * packet because of a race condition, a closed connection, or any 
	 * other reason.
	 */
	boolean forceSendPacket(PeerPacketMessage ppm);

	/**
	 * @return the ConnectionDataTransferAccounter being used by
	 * this connection
	 */
	ConnectionDataTransferAccounter getTransferAccounter();

	/**
	 * @return the local port number, or one of the status codes defined
	 * above. REDFLAG: TCP independance!
	 */
	int getLocalPort();

	/**
	 * Set the target NodeReference
	 * @param ref
	 */
	void setTargetReference(NodeReference ref);

	/**
	 * @return the current PeerHandler this connection is attached to.
	 */
	PeerHandler getPeerHandler();

    /**
     * If this connection supports on-network address detection, return
     * a message to send back to the other end including the address we
     * detected for it.
     */
    AddressDetectedPacketMessage getDetectMessage();

    /**
     * Set the detected IPv4 address for this connection. This has been
     * sent by the other side of the connection, and it should be the
     * address of *this* node, the node running on this computer, and 
     * not the node on the other end.
     * One BaseConnectionHandler can have at most one detected address.
     * @param ip4addr the address detected.
     */
    void setDetectedAddress(Inet4Address ip4addr);
    
    /**
     * Get the detected IPv4 address for this connection. This has been
     * sent by the other side of the connection, and it is intended to be
     * the other side's detection of our address. I.e. the address of the
     * node running on _this_ computer. The reason this is useful is that
     * many nodes cannot directly detect their IP address. Mostly this is
     * referring to NATted nodes, especially where the NAT is on a 
     * dynamic IP address.
     * @return
     */
    Inet4Address getDetectedAddress();

    /**
     * @return true if there are messages currently being sent that carry
     * a minimum request interval.
     */
    boolean inFlightMessagesWithMRI();
}
