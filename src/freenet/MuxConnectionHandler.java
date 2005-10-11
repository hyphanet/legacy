package freenet;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import freenet.node.Main;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.session.Link;
import freenet.support.Irreversible;
import freenet.support.Logger;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.ThrottledAsyncTCPWriteManager;
import freenet.transport.NIOReader;
import freenet.transport.NIOWriter;
import freenet.transport.tcpConnection;

/**
 * Class to handle multiplexed connections. Rewritten from the ground up
 * because ConnectionHandler is a mess and we can do away with much of its
 * complexity with muxing and other improvements.
 * 
 * @author amphibian
 */
public class MuxConnectionHandler
	implements BaseConnectionHandler, NIOWriter, NIOReader {

	// Basic NIO stuff
	private final ThrottledAsyncTCPWriteManager wsl;
	
	// Our connection
	final tcpConnection conn;
	final Link link;
	final Presentation p;
	final Identity identity;
	final Socket sock;
	final SocketChannel chan;
	final Peer peer;
	final boolean outbound;

	// Our PeerHandler etc
	final PeerHandler peerHandler;
	
	//Will change from false to true when we are first registered on our PH
	//Also acts as sync-obj for PH registration and unregistration
	private final Irreversible hasBeenRegisteredOnPeerHandler = new Irreversible(false);
	
	//Indicates wheter or not we are _currently_ registered on our PH
	//sync on the above object when needed
	private boolean currentlyRegisteredOnPeerHandler = false;
	
	private final OpenConnectionManager ocm;
	private final Irreversible registeredInOCM = new Irreversible(false);

	// State of each side
	private final Irreversible sendClosed = new Irreversible(false);
	private final Irreversible receiveClosed = new Irreversible(false);

	// Sending...
	private PeerPacket sendingPacket;
	private final Object sendingPacketLock = new Object();

	// Receiving
	private final PeerPacketParser messageParser;
	private final static Object decryptBufferLock = new Object();
	private final static byte[] decryptBuffer = new byte[tcpConnection.BUFFER_SIZE];
	final Ticker t;

	// Termination
	private Irreversible terminated = new Irreversible(false);

	// Misc
	long lastAccessTime;
	boolean logDEBUG;
	private final long startTime = System.currentTimeMillis();
	final Object statsLock = new Object(); // protects the below
	/** Total count of messages sent */
	int messagesSent;
	/** Total count of messages received */
	int messagesReceived;
	private final ConnectionDataTransferAccounter accounter;

	private static final long MINIMUM_ACCEPTABLE_CONNECTION_LIFETIME = 20000;

	long lastToString = -1;
	
	public String toString(long now) {
	    // Don't log more often than every 15 minutes
	    // Data DOES NOT CHANGE anyway! Well except for the socket.
	    if(now - lastToString < 15*60*1000) {
	        String ret = super.toString() + " MuxConnectionHandler[conn=[" + conn + "], link=[" + link + "], p=[" + p
				+ "], identity=[" + identity + "], sock=[" + sock + "], chan=[" + chan + "], "
				+ "peer=[" + peer + "], outbound=[" + outbound + "]]";
	        lastToString = now;
	        return ret;
	    } else return super.toString();
	}
	
	public String toString() {
	    return toString(System.currentTimeMillis());
	}

	public MuxConnectionHandler(
		tcpConnection conn,
		Link link,
		Presentation p,
		PeerPacketMessageParser mp,
		OpenConnectionManager ocm,
		Ticker t,
		boolean outbound,ThrottledAsyncTCPReadManager rsl,ThrottledAsyncTCPWriteManager wsl)
		throws IOException {
		if (wsl == null)
			throw new NullPointerException("wsl null in " + this);
		this.wsl = wsl;
		this.outbound = outbound;
		this.conn = conn;
		this.link = link;
		link.setTimeout(300*1000); // 5 mins
		this.t = t;
		this.p = p;
		this.identity = link.getPeerIdentity();
		if(identity == null) {
		    Core.logger.log(this, "Identity null on "+this, new Exception("debug"), Logger.ERROR);
		    conn = null;
		    link = null;
		    throw new IOException("null identity");
		}
		lastAccessTime = startTime;
		String c =
			"Connection already closed on construction of MuxConnectionHandler!";
		if (conn.isClosed())
			throw new IOException(c + ": conn closed");
		sock = conn.getSocket();
		if (sock == null)
			throw new IOException(c + ": null socket");
		chan = sock.getChannel();
		peer = new Peer(identity, link.getPeerAddress(), link.getManager(), p);
		messageParser = new PeerPacketParser(mp, this);
		this.ocm = ocm;
		accounter = new ConnectionDataTransferAccounter();
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		Main.node.reference(null, identity, null, null);
		peerHandler = ocm.getPeerHandler(identity);
		if(peerHandler == null) {
		    Core.logger.log(this, "PeerHandler not found on "+this+
		            "!!", new Exception("debug"), Logger.ERROR);
		    terminate();
		    throw new IOException("no peerhandler");
		}
	}

	public ByteBuffer getBuf() {
		return conn.getInputBuffer();
	}

	public boolean shouldThrottle() {
		return conn.shouldThrottle();
	}

	public boolean countAsThrottled() {
		return conn.countAsThrottled();
	}

	//TODO: We already know our ph.. remove the ph argument from this method and rename it.
	public void setPeerHandler(PeerHandler ph)
		throws RemovingPeerHandlerException {
		if(ph != peerHandler)
			throw new RuntimeException("Cannot switch PH");
		if (!hasBeenRegisteredOnPeerHandler.tryChange())
			throw new RuntimeException("Cannot register on PH again");
		synchronized (hasBeenRegisteredOnPeerHandler) {
			peerHandler.registerConnectionHandler(this);
			currentlyRegisteredOnPeerHandler = true;
		}
	}

	/**
	 * Force the ConnectionHandler to start sending a packet @returns true if
	 * we sent a packet, or there is nothing to send, false if we were already sending
	 * one.
	 * 
	 * Throws nothing:
	 *             Must log an error and return false if it was going to throw.
	 *             Called by PeerHandler.innerSendMessageAsync
	 */
	public final boolean forceSendPacket(PeerPacketMessage ppm) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		// Firstly, are we closed?
		if (terminated.state() || sendClosed.state())
			return false;
		// Are we sending a packet?
		synchronized (sendingPacketLock) {
			if (terminated.state() || sendClosed.state())
				return false;
			if (sendingPacket != null) {
				return false;
			} else {
				// Not sending a packet!
				sendingPacket =
					peerHandler.getPacket(p, identity, ppm, null, false, true);
				if (sendingPacket == null){
					if(ppm != null)
					Core.logger.log(this, "Asked to send " + ppm + " on " + this + ", but packet did not exist",
							Logger.ERROR);
					return true; 
				}
				// nothing to do, prevent caller from spinning.
				if (logDEBUG)
					Core.logger.log(this, "Sending " + sendingPacket + " on " + this + ".forceSendPacket()",
							Logger.DEBUG);
			}
		} // can only be changed in synched
		// But can now be used outside synch
		return sendPacket();
	}

	/**
	 * Send the packet in sendingPacket.
	 * 
	 * @return true if we sent sendingPacket, false if something broke
	 */
	private boolean sendPacket() {
		lastAccessTime = System.currentTimeMillis();
		PeerPacket packet = sendingPacket;
		if (packet == null) {
			Core.logger.log(this, "sendingPacket NULL in sendPacket", new Exception("debug"), Logger.ERROR);
			return false;
		}
		byte[] toSend = packet.getBytes();
		if (sendClosed.state()) {
		    Core.logger.log(this, "sendPacket() returning false because sendClosed",
		            Logger.DEBUG);
			return false;
		}
		if (conn.isClosed() || (!chan.isOpen())) {
		    Core.logger.log(this, "conn.isClosed(): "+conn.isClosed()+
		            ", chan.isOpen(): "+chan.isOpen(), Logger.DEBUG);
			terminate();
			return false;
		}
		try {
			link.encryptBytes(toSend, 0, toSend.length);
		} catch (IOException e) {
			Core.logger.log(this, "Couldn't encrypt " + sendingPacket + " for " + link + ": " + e, e, Logger.MINOR);
			terminate();
			return false;
		}
		synchronized (statsLock) {
			messagesSent += packet.countMessages();
		}
		try {
			if (!wsl.send(toSend, 0, toSend.length, chan, this, packet.priority())) {
				Core.logger.log(this, "Cannot write " + packet + " to " + this
						+ ": WSL returned false i.e. send in progress!", Logger.NORMAL);
				return false;
			}
		} catch (IOException e) {
			Core.logger.log(this, "Caught " + e + " sending " + sendingPacket + " on " + this, Logger.MINOR);
			terminate();
			return false;
		}
		accounter.registerSentData(toSend.length);
		return true;
	}

	/**
	 * Terminate the connection.
	 */
	public void terminate() {
		if (logDEBUG)
			logDEBUG("Terminating");
		// These two must be completed before returning, even if termination
		// is already in progress. See OpenConnectionManager.KillSurplusConnections().
		if(registeredInOCM.state()){ //TODO: Prevent repeated ocm.remove() calls..
			if(identity != null)
				ocm.remove(this);
			if (logDEBUG)
				logDEBUG("Removed from OCM in terminate");
		}
		synchronized (hasBeenRegisteredOnPeerHandler) {
			if (currentlyRegisteredOnPeerHandler) {
				peerHandler.unregisterConnectionHandler(this);
				currentlyRegisteredOnPeerHandler = false;
			} else {
				Core.logger.log(this, "Terminating " + this + " but not registered on a PeerHandler", Logger.MINOR);
				// could be just never got initted
			}
		}
		if (!terminated.tryChange()) {
		    if(logDEBUG) logDEBUG("Already terminated");
			return;
		}
		if (identity != null) {
			if (logDEBUG)
				logDEBUG("Scheduling connection opener");
			Main.node.rescheduleConnectionOpener();
			if (logDEBUG)
				logDEBUG("Scheduled connection opener");
		} else {
			if (logDEBUG)
				logDEBUG("Null identity");
		}
		long now = System.currentTimeMillis();
		long connectionLifetime = now - startTime;
		
		//If we for some reason got closed within a very short time of being created
		//we should try to avoid reopening a connection to the peer immediately.
		//This mesaure prevents outbound connection thrashing
		if(connectionLifetime < MINIMUM_ACCEPTABLE_CONNECTION_LIFETIME &&
		        peerHandler != null)
			peerHandler.revokeConnectionSuccess();
		
		if (registeredInOCM.state()) {
			// If it's actually started
			long messages = messagesSent + messagesReceived;
			if (logDEBUG)
				logDEBUG(
					"Logging stats: connectionLifeTime="
						+ connectionLifetime
						+ ", messages="
						+ messages);
			Core.diagnostics.occurrenceContinuous(
				"connectionLifeTime",
				connectionLifetime);
			Core.diagnostics.occurrenceContinuous(
				"connectionMessages",
				messages);
		}
		sendClosed.tryChange(); //TODO: Should we care about the returned value?
		receiveClosed.tryChange(); //TODO: Should we care about the returned value?

		PeerPacket packet = null;
		synchronized (sendingPacketLock) {
			if (sendingPacket != null) {
				packet = sendingPacket;
				sendingPacket = null;
				// TODO: review locking here
			}
		}
		if (packet != null)
			packet.jobDone(true, 0, peer, null, peerHandler);

		try {
			link.close();
		} catch (IOException e) {
			// Ignore it, probably just already closed
		} catch (Throwable tr) {
			Core.logger.log(
				this,
				"Link.close() in terminate caused " + tr + " on " + this,
				tr,
				Logger.MINOR);
		}
		logDEBUG("Closed link");

		synchronized(detectedAddressSync) {
		    if(detectedAddress != null) {
		        ocm.undetected(detectedAddress);
		        detectedAddress = null;
		    }
		}
		
		logDEBUG("Terminated");
	}

	///**
	// * Removes this MuxCH from the OCM.
	// */
	//private void removeFromOCM() {
	//	ocm.remove(this);
	//}

	private void logDEBUG(String string) {
		if( logDEBUG ) {
			Core.logger.log(this, string + " (" + this +")", Logger.DEBUG);
		}
	}

	public void jobDone(int size, boolean status) {
		lastAccessTime = System.currentTimeMillis();
		if (status) {
			PeerPacket packet = null;
			peerHandler.setResetSendPacket();
			synchronized (sendingPacketLock) {
				packet = sendingPacket;
				sendingPacket = null;
			}
			if (packet != null) {
				packet.jobDone(true, size, peer, null, peerHandler);
				if (packet.hasCloseMessage()) {
					sendClosed.tryChange(); //TODO: Should we care about the returned value?
					return;
				}
			}else{
				Core.logger.log(this, "jobDone(" + size + ","+status+") on " + this + " but sendingPacket == null!", Logger.ERROR);
			}
			// Send the next packet, if possible
			synchronized (sendingPacketLock) {
				if (terminated.state() || sendClosed.state())
					return;
				if (sendingPacket != null)
					return;
				// Not sending a packet!
				sendingPacket = peerHandler.getPacket(p, identity, null, null, false, true);
				if (sendingPacket == null)
					return;
				if (logDEBUG)
					Core.logger.log(this, "Sending " + sendingPacket + " on " + this + ".forceSendPacket()", Logger.DEBUG);
			} // can only be changed in synched
			// But can now be used outside synch
			if (sendingPacket != null)
				sendPacket();
		} else {
			// It failed. Uh oh.
			// Prevent any more being sent
			sendClosed.tryChange(); //TODO: Should we care about the returned value?
			// Now notify the messages
			PeerPacket packet = null;
			synchronized (sendingPacketLock) {
				packet = sendingPacket;
				sendingPacket = null;
			}
			if (packet != null) //FIXME was == null which is obviously
				// wrong...
				packet.jobDone(true, size, peer, null, peerHandler);
		}
		if (sendClosed.state() && receiveClosed.state()) {
			if (logDEBUG)
				logDEBUG("Terminating in jobDone");
			terminate();
		}
	}

	public void jobPartDone(int size) {
		lastAccessTime = System.currentTimeMillis();
		if (sendingPacket != null) {
			sendingPacket.jobDone(false, size, peer, null, peerHandler);
		} else {
			Core.logger.log(this, "jobPartDone(" + size + ") on " + this + " but sendingPacket == null!", Logger.ERROR);
		}
	}

	public int process(ByteBuffer b) {
		boolean kill = false;
		lastAccessTime = System.currentTimeMillis();
		// Decrypt to a temp buffer
		// Then feed to messageParser.
		if (logDEBUG)
			Core.logger.log(
				this,
				"process(" + b + ") on " + this,
				Logger.DEBUG);
		//if (b == null) //Not our problem... someone else has done wrong
		//	b = conn.getInputBuffer();
		try {
			int len = b.remaining();
			if (len > 0)
				accounter.registerReceivedData(len);
			synchronized (decryptBufferLock) {
				// Accumulator is kept in a ready to read state by RSL
				if (len > 0) {
					b.get(decryptBuffer, 0, len);
					b.limit(0); // consumed all data
					link.decryptBytes(decryptBuffer, 0, len);
					if (logDEBUG)
						Core.logger.log(
							this,
							"Decrypted " + len + " bytes, processing...",
							Logger.DEBUG);
					kill = messageParser.process(decryptBuffer, 0, len);
				}
			}
		} catch (IOException e) {
			// It broke :(
			Core.logger.log(this, "Caught " + e + " in process() on " + this, e, Logger.NORMAL);
			receiveClosed.tryChange(); //TODO: Should we care about the returned value?
			if (sendClosed.state() && receiveClosed.state()) {
				terminate();
				return -1;
			}
		}
		if (kill) {
			Core.logger.log(this, "Terminating corrupt mux connection " + this, Logger.NORMAL);
			terminate();
		}
		return 1;
	}

	public void closed() {
		terminate();
	}

	public void queuedClose() {
		boolean wasTerminated = terminated.state();
		terminate();
		if (wasTerminated && (messagesSent + messagesReceived) == 0) {
			if (identity != null)
			    Main.node.rescheduleConnectionOpener();
		}
	}

	public void registered() {
		// Irrelevant
	}

	public void unregistered() {
		// Irrelevant
	}

	public long trailerLengthAvailable() {
		// TODO Auto-generated method stub
		return 0;
	}

	public Identity peerIdentity() {
		return identity;
	}

	public NodeReference targetReference() {
		if (peerHandler != null)
			return peerHandler.getReference();
		else
			return null;
	}

	public Presentation getPresentation() {
		return p;
	}

	public boolean isOutbound() {
		return outbound;
	}

	public Address peerAddress() {
		return peer.getAddress();
	}

	public boolean receiving() {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean blockedSendingTrailer() {
		return false; // not blocked even if sending trailers
	}

	public boolean isOpen() {
		return !sendClosed.state();
	}

	public void registerOCM() {
		if (logDEBUG)
			Core.logger.log(this, "registerOCM() on " + this, Logger.DEBUG);
		if (terminated.state())
			return;
		// Don't register if we are not connected to a real node.
		// We don't use this now, but in future we might do some kind of multiplexed FCP.
		if (identity == null) return;
		if (logDEBUG) Core.logger.log(this, "Adding to OCM: " + this, Logger.DEBUG);
		if(!registeredInOCM.tryChange()){
			Core.logger.log(this, "registerOCM() called for the second time on " + this, Logger.ERROR);
			throw new RuntimeException("Cannot register in OCM twice");
		}
		ocm.put(this);

		Node node = freenet.node.Main.node;
		PeerPacketMessage im =
			new IdentifyPacketMessage(
				Main.node.getNodeReference(),peerHandler,
				this,
				peerHandler.getRequestInterval(),
				node);
		PeerPacketMessage dm = null;
        if(identity != null) {
            // Send message indicating detected address
            dm = getDetectMessage();
            if(dm != null) {
                if(logDEBUG) {
                    Core.logger.log(this, "Sending other side: "+dm, Logger.DEBUG);
                }
            }
        }
		boolean send = false;
		synchronized (sendingPacketLock) {
			if (sendingPacket == null) {
				sendingPacket =
					peerHandler.getPacket(p, identity, im, dm, false, true);
				send = true;
			}
		}
		if (send) {
			if ((!sendPacket()) && (!terminated.state())) {
				Core.logger.log(this, "registerOCM failed to send packet " + 
				        sendingPacket + " on " + this, Logger.NORMAL);
				terminate();
			}
		}
		if (receiveClosed.state() && sendClosed.state())
			terminate();
	}

	public int messagesReceived() {
		return messagesReceived;
	}

	public int messagesSent() {
		return messagesSent;
	}

	public long idleTime() {
		return System.currentTimeMillis() - lastAccessTime;
	}

	public long runTime() {
		return System.currentTimeMillis() - startTime;
	}

	public boolean isSendingPacket() {
		return sendingPacket != null;
	}

	public ConnectionDataTransferAccounter getTransferAccounter() {
		return accounter;
	}

	public int getLocalPort() {
		if (terminated.state())
			return CHANNEL_CLOSED_PORTNUMBER;
		if (!chan.isOpen())
			return CHANNEL_CLOSED_PORTNUMBER;
		return sock.getLocalPort();
	}

	public void setTargetReference(NodeReference ref) {
		if (peerHandler != null) {
			peerHandler.updateReference(ref);
		} else {
			Core.logger.log(this, "setTargetReference(" + ref + ") but no peerHandler! on " + this, Logger.NORMAL);
		}
	}

	public PeerHandler getPeerHandler() {
		return peerHandler;
	}

    public AddressDetectedPacketMessage getDetectMessage() {
        InetAddress addr = sock.getInetAddress();
        if(addr == null) {
            if(!sock.isClosed())
                Core.logger.log(this, "Could not detect address for "+this+
                        ": "+sock, Logger.NORMAL);
            return null;
        }
        if(addr instanceof Inet4Address) {
            Inet4Address a = (Inet4Address)addr;
            return new AddressDetectedPacketMessage(peerHandler, this, a);
        } else {
            Core.logger.log(this, "Detected non-IPv4 address!: "+addr+
                    " for "+this, Logger.NORMAL);
            return null;
        }
    }
    
    Inet4Address detectedAddress = null;
    Object detectedAddressSync = new Object();
    
    public void setDetectedAddress(Inet4Address ip4addr) {
        synchronized(detectedAddressSync) {
            if(ip4addr != null) {
                this.detectedAddress = ip4addr;
                ocm.detected(ip4addr);
            }
        }
    }

    public Inet4Address getDetectedAddress() {
        return detectedAddress;
    }

    public boolean inFlightMessagesWithMRI() {
        PeerPacket packet = sendingPacket;
        if(packet == null) return false;
        return packet.messagesWithMRI();
    }
}
