/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.Socket;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;

import freenet.crypt.EntropySource;
import freenet.message.HTLMessage;
import freenet.message.Identify;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.session.FnpLink;
import freenet.session.Link;
import freenet.session.LinkManager;
import freenet.session.PlainLink;
import freenet.support.Irreversible;
import freenet.support.IrreversibleException;
import freenet.support.Logger;
import freenet.support.io.DiscontinueInputStream;
import freenet.support.io.NullInputStream;
import freenet.transport.NIOReader;
import freenet.transport.NIOWriter;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.ThrottledAsyncTCPWriteManager;
import freenet.transport.tcpConnection;

/*
 *  This code is part of the Java Adaptive Network Client by Ian Clarke.
 *  It is distributed under the GNU General Public Licence (GPL)
 *  version 2.  See http://www.gnu.org/ for further details of the GPL.
 */

/**
 *  Handles both sending and receiving messages on a connection.
 *
 *@author     oskar (90%)
 *@author     <A HREF="mailto:I.Clarke@strs.co.uk">Ian Clarke</A>
 *@author     <a href="mailto:blanu@uts.cc.utexas.edu">Brandon Wiley</a>
 */
public final class ConnectionHandler
	implements BaseConnectionHandler, NIOReader, NIOWriter {

    private final Node node;
    private final     OpenConnectionManager  ocm;
    private           Link                   link;
    final     Presentation           p;
    private final     Ticker                 t;

    private           long                   startTime;
    private           int                    maxInvalid;
    private 	      int 		invalid =0;
    private           int                    messagesReceived         = 0;
    private int messagesSent = 0;
	public final boolean isFNP;
	// obsolete if we don't remove from OCM until the last minute	
// 	public static long openButNotOnOCM = 0;

    /**  Description of the Field */
    public final boolean outbound;

    // state variables

    /**  Last time of noted activity */
    private volatile long lastActiveTime;
    // clock time of last activity

    /**  If no more messages will be read */
    private final Irreversible receiveClosed = new Irreversible(false);

    /**  If no more message can be sent */
    private final Irreversible sendClosed = new Irreversible(false);

    /**  If we should never timeout the connection */
    private final Irreversible persist = new Irreversible(false);

    /**  terminate() reached */
    private Irreversible finalized = new Irreversible(false);

    /**  Object to lock on when receiving */
    private final Object receiveLock = new Object();
    /**  Count of number of number of reads going on (should be 0 or 1) */
    //private final Count receiving=new Count(0, receiveLock);
    // AFM (another fucking monitor)
    private volatile int receivingCount = 0;

    /**  Object to lock on when sending */
    private final Object sendLock = new Object();
    /**  Count objects for number of sends in progress of pending */
    //private final Count sending=new Count(0, sendLock);
    // AFM (another fucking monitor)

	private ConnectionDataTransferAccounter transferAccounter =
		new ConnectionDataTransferAccounter();
// 	private volatile SendOutputStream currentSOS = null;
    
    private static class TrailerSendState {
		private volatile int trailerSendID = -1;
		//The ID of the trailer-send currently in progress
		private volatile long trailerSendLength = 0;
		//The complete length of the trailer currently in progress (how many trailerbytes will we send)
		private volatile int trailerSentBytes = 0;
		//How many bytes of the trailer have we currently sent
		private volatile boolean isSendingTrailerChunk = false;
		//Are we currently sending a chunk of the trailer //TODO: Replace with trailerChunkBytes>0 ?
		private int trailerChunkBytes = 0;
		//The number of bytes in the currently sending trailer chunk
		private TrailerWriteCallback twcb;
		//Who should we notify when the current chunk has been sent
		private ConnectionHandler ch;
		//TODO: For logging purposes only, remove when appropriate
		private boolean chClosed = false;
		private static TrailerSendIDSource trailerSendIDSource =
			new TrailerSendIDSource();
		//Used to deliver unique ID:s to each new trailersend
		
		private static class TrailerSendIDSource {
			private int nextID = 0;
			synchronized int getNext(){
				nextID++;
				if(nextID <0) //Handle possible wraparound
					nextID = 0;
				return nextID;
			}
		}
		
		public class PartialChunkSentException extends Exception {
			PartialChunkSentException(String s){
				super(s);
			}
		}
		//TODO: Ugly hack, remove as soon as appropriate
		void setCH(ConnectionHandler ch) {
			this.ch = ch;
		}
		
		//returns true if we currently is sending a trailer (assuming that everyone has notified this context correctly)
		public boolean isSendingTrailer() {
			return trailerSendID != -1;
		}
		public boolean isSendingTrailerChunk(){
			return isSendingTrailerChunk;
		}
		//returns the number of trailer bytes ready to send reasonably quickly
		public synchronized long trailerLengthAvailable() {
			if (isSendingTrailer()) {
				if (twcb == null)
					//TODO: Can this happen, maybe a IllegalStateException should be thrown?
					return trailerSendLength - trailerSentBytes; 
				else
					return twcb.bytesAvailable();
			} else //TODO: should this happen, maybe a IllegalStateException should be thrown?
				return 0;
		}
		
		//returns true if not all of the trailer is sent yet
		public boolean hasTrailerDataRemaining() {
			if (!isSendingTrailer())
				throw new IllegalStateException("Not sending a trailer");
			return trailerSentBytes < trailerSendLength;
		}
		
		public void reset() {
			TrailerWriteCallback temp;
			synchronized (this) {
				isSendingTrailerChunk = false;
				trailerChunkBytes = 0;
				trailerSendLength = 0;
				trailerSentBytes = 0;
				trailerSendID = -1;
				temp = twcb;
				twcb = null;
			}
			if (temp != null)
				temp.closed(); //Should not be called synchronized on this?!  
		}
		//Initializes this context for sending a new trailer of length 'length'
		//returns the SendID
		public synchronized int startNew(long length) {
			if (isSendingTrailer())
				throw new IllegalStateException("Already sending a trailer");
			if (isSendingTrailerChunk())
				throw new IllegalStateException("Already sending a trailer chunk");
			//TODO: This check might be unnecesary
			if(twcb != null)
				throw new IllegalStateException("Has a twcb");
			//TODO: This check might be unnecesary
			reset(); //This might also be an unnecesary call..
			trailerSendID = trailerSendIDSource.getNext();
			trailerSendLength = length;
			return trailerSendID;
		}
		//Called internally whenever a chunk has been completely written, should never be called by 'NOT this'
		//Notifies the registered callback that it has happened
		private void registerChunkSendCompleted() {
			if (ch.logDEBUG)
				ch.logDEBUG("Complete trailer chunk sent");
			synchronized (this) {
				trailerSentBytes += trailerChunkBytes;
				isSendingTrailerChunk = false;
				trailerChunkBytes = 0;
			}
			if (twcb != null) {
				if (ch.logDEBUG)
					ch.logDEBUG("Calling " + twcb + ".written()");
				twcb.written(); //Should not be called synchronized on this?!
				//TODO: Forget the twcb here maybe?
			}
		}
		
		public synchronized void closedCH() {
			chClosed = true;
		}
		
		//Registers a close of the current trailersend with this context 
		//The parameter 'expectedTrailerSendID' is used only for a sanity check.. it ought to match our internal id unless someone has done something bad
		public synchronized void closeTrailerSend(int expectedTrailerSendID) {
			if (ch.logDEBUG)
				ch.logDEBUG(
					"closeTrailer(" + expectedTrailerSendID + "): " + twcb,
					true);
			if (chClosed)
				return;
			if (!isSendingTrailer())
				throw new IllegalStateException("Not sending a trailer");
			if (isSendingTrailerChunk())
				Core.logger.log(
					this,
					"Closing trailer "
						+ expectedTrailerSendID
						+ " on "
						+ this
						+ " while still sending trailer chunk!",
					new Exception("debug"),
					Logger.ERROR);
			if (trailerSendID == expectedTrailerSendID) {
				if (hasTrailerDataRemaining())
					Core.logger.log(
						this,
						"Called closeTrailer, when only sent "
							+ trailerSentBytes
							+ " of "
							+ trailerSendLength
							+ " ("
							+ this
							+ ")",
						new Exception("hrrm"),
						Logger.MINOR);
				reset();
			}else
				throw new IllegalStateException(
					"Asked to close trailersend #"
						+ expectedTrailerSendID
						+ " when actually sending #"
						+ trailerSendID);
			// else
			//	if (isSendingTrailer())
			//		Core.logger.log(this, "Closing trailer " + expectedTrailerSendID + " when handling " + trailerSendID + "! (" + this +")", new Exception("hrrm"), Logger.ERROR);
		}
		//registers a start of send of a new chunk of the trailer
		//The parameter 'expectedTrailerSendID' is used only for a sanity check.. it ought to match our internal id unless someone has done something bad		
		public void registerStartChunkWrite(
			int expectedTrailerSendID,
			int offset,
			int length,
			TrailerWriteCallback cb)
			throws
				UnknownTrailerSendIDException,
				AlreadySendingTrailerChunkException,
				TrailerSendFinishedException {
			if (ch.logDEBUG)
				Core.logger.log(
					this,
					"writeTrailing("
						+ expectedTrailerSendID
						+ ",byte[],"
						+ offset
						+ ","
						+ length
						+ ","
						+ cb
						+ " on "
						+ this
						+ " (id = "
						+ trailerSendID
						+ ")",
					new Exception("debug"),
					Logger.DEBUG);
			if(!isSendingTrailer())
				throw new IllegalStateException("Trying to write chunk with no trailer send in progress");
			if (trailerSendID != expectedTrailerSendID)
				throw new UnknownTrailerSendIDException();
			if (trailerSentBytes >= trailerSendLength) {
				trailerSendID = -1;
				//TODO: a complete reset somewhere around here? /Iakin
				throw new TrailerSendFinishedException();
			}
			if (isSendingTrailerChunk())
				throw new AlreadySendingTrailerChunkException();
			if (length <= 0)
				throw new IllegalArgumentException();
			if (twcb != null && twcb != cb)
				//TODO: Should this really throw.. introduced during Iakins 2003-12-04 refactoring
				throw new IllegalStateException("Got new cb");
			twcb = cb;
			trailerChunkBytes = length;
			isSendingTrailerChunk = true;
			
		}
		//Should be called whenever a chunk of the trailer has been sent. Throws if the send wasn't all of the current chunk
		public synchronized void registerChunkWritten(long size)
			throws PartialChunkSentException {
			if (ch.logDEBUG)
				ch.logDEBUG("At least a part of a trailer chunk sent");
			if (size == trailerChunkBytes) {
				registerChunkSendCompleted();
			}else
				throw new PartialChunkSentException("Partial chunk sent");
		}
		public String toString() {
			return "trailerID="+String.valueOf(trailerSendID);
		}
		protected void finalize() throws Throwable {
			if(isSendingTrailer())
				Core.logger.log(
					this,
					"Finalized while sending trailer",
					Logger.ERROR);
			super.finalize();
		}

	}
	private final TrailerSendState trailerSendState = new TrailerSendState();
	
	ReceiveInputStream currentInputStream = null;
	private PeerHandler peerHandler = null; 
	// NOT final - see end of registerOCM
	
	private boolean sendingCloseMessage = false;
	private SocketChannel chan;
	private Socket sock;
	
	public long trailerLengthAvailable() {
		if (sendClosed.state())
			return 0;
		return trailerSendState.trailerLengthAvailable();
	}
	
    //private Thread exec_instance; // execution thread
	private static EntropySource recvTimer = new EntropySource();

	private volatile boolean alreadyClosedLink = false;
	private final int maxPacketLength;

    /** methods related to nio */
    
    //a buffer size; the maximum message size is 64K
	// Store two messages worth. The buffer for tcpConn is half this currently;
	// we want CH's buffer to be substantially bigger than tcpConn's buffer.
    private final static int BUF_SIZE=67*1024;
    
    //the buffer itself
    private ByteBuffer accumulator;
    //ensure there's a backing byte[] 
    //contrary to common sense, we need to do so.
    private byte[] _accumulator;
	//private ByteBuffer rawAccumulator;
	private tcpConnection conn;
    private int decryptLen;
	private Identity identity;
    
    /***try to fight the OOMS***/
    //this should be the same as the Connection buffer which should be exported
    private static final int DECRYPT_SIZE=16*1024; 
    private static final byte[] ciphertext = new byte[DECRYPT_SIZE];
    
    private boolean movingTrailingFields = false;
    private boolean doneMovingTrailingFields = false;
    private boolean disabledInSelector = false;
	private boolean initRegisteredInOCM;
	private boolean reregistering = false;
	private final ThrottledAsyncTCPReadManager rsl;
	private final ThrottledAsyncTCPWriteManager wsl;
	private Peer peer;
    
    //profiling
    //WARNING:remove before release
	public static class ProfilingHelper {
		public volatile int instances=0;
		public volatile long terminatedInstances=0;
    	public volatile int CHOSinstances=0;
    	public volatile int CHISinstances=0;
    	public volatile int SOSinstances=0;
    	public volatile int RISinstances=0;
    	private final Object profLock = new Object();
    	private final Object profLockCHIS = new Object();
    	private final Object profLockRIS = new Object();
		private void decRISCount() {
			synchronized(profLockRIS){
				RISinstances--;
			}
		}
		private void decInstanceCount() {
			synchronized(profLock) {
				instances--;
			}
		}
		private void incCHISCount() {
			synchronized(profLockCHIS){
				CHISinstances++;
			}
		}
		private void incRISCount() {
			synchronized(profLockRIS) {
				RISinstances++;
			}
		}
		private void incInstanceCount() {
			synchronized(profLock) {
				instances++;
			}
		}
		private void decCHISCount() {
			synchronized(profLockCHIS) {
				CHISinstances--;
			}
		} 
	}
	public static final ProfilingHelper profilingHelperTool =
		new ProfilingHelper();
	
	//CHIOS notification flags
	protected volatile boolean CHISwaitingForNotification=false;
	protected volatile boolean CHISalreadyNotified=false;
	protected volatile boolean CHOSwaitingForNotification=false;
	protected volatile boolean CHOSalreadyNotified=false;
	protected volatile Thread currentCHOSThread=null;
	
	private boolean logDEBUG;

	/**
	 * this is where all the messages are queued; the moment we get a trailer
	 * we set the flag
	 */	
//     private List sendingQueue = Collections.synchronizedList(new LinkedList());
	private PeerPacket sentPacket = null; // should NOT be volatile - see uses
	private final Object sentPacketLock = new Object();
	private final static LinkedList bufferPool = new LinkedList();
	
	public static int bufferPoolSize() {
		synchronized(bufferPool) {
			return bufferPool.size();
		}
	}
	
    // Constructors
    /**
     *  The ConnectionHandler provides the interface between the session,
     *  presentation, and application layers. Messages received on l, are parsed
     *  using p, and then turned into messageobjects using the MessageFactory which
     *  are scheduled for immediate execution on the ticker. Message objects given
     *  to the sendmessage are serialized using the p and sent on l. <p>
     *
     *  The outbound argument is a hint used by diagnostics to differentiate
     *  inbound from outbound connections. <p>
     *
     *
     *
     *@param  ocm         The cache of open connections to register with
     *@param  p           A presentation for parsing messages
     *@param  l           A live, initialized link, to read messages off
     *@param  t           A ticker to schedule the messages with.
     *@param  maxInvalid  The maximum number of invalid messages to swallow.
     *@param  outbound    Set true for outbound connections, false otherwise.
     */
	public ConnectionHandler(
		OpenConnectionManager ocm,
		Node n,
		Presentation p,
		Link l,
		Ticker t,
		int maxInvalid,
		boolean outbound,
		ThrottledAsyncTCPReadManager rsl,
		ThrottledAsyncTCPWriteManager wsl)
		throws IOException {
		try {
		    this.node = n;
			this.ocm = ocm;
			this.p = p;
			this.link = l;
			this.t = t;
			this.wsl = wsl;
			this.rsl = rsl;
			this.maxPacketLength = n.getMaxPacketLength();
			lastActiveTime = System.currentTimeMillis();
			this.maxInvalid = maxInvalid;
			this.outbound = outbound;
			this.identity = link.getPeerIdentity();
			this.trailerSendState.setCH(this);
			//TODO: Ugly hack, remove when appropriate
			
			peer =
				new Peer(identity, link.getPeerAddress(), link.getManager(), p);
			this.logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
			if (logDEBUG)
				logDEBUG("New connectionhandler with " + peer, true);
			
			synchronized(bufferPool) {
				if(bufferPool.isEmpty()) {
					_accumulator = new byte[BUF_SIZE];
					accumulator = ByteBuffer.wrap(_accumulator);
				} else {
					accumulator = (ByteBuffer)(bufferPool.removeFirst());
					_accumulator = accumulator.array();
				}
			}
			conn = (tcpConnection)(link.getConnection());
			sock = conn.getSocket();
			if(sock == null)
				throw new IOException("Already closed!");
			chan = sock.getChannel();
			if (logDEBUG)
				logDEBUG("Connection: " + conn);
			if (conn == null)
				throw new IOException("Already closed!");
			if (conn.isClosed())
				throw new IOException("Already closed!");
			if (logDEBUG)
				logDEBUG("Starting");
			//rawAccumulator = conn.getInputBuffer();
			accumulator.limit(0);
			accumulator.position(0);
			startTime = System.currentTimeMillis();
			link.setTimeout(300*1000); // 5 mins
			if (!sock.getKeepAlive()) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Keepalive was disabled on " + conn,
						Logger.DEBUG);
				sock.setKeepAlive(true);
			}
			initRegisteredInOCM = false;
			isFNP = link instanceof FnpLink ? true : false;
			//there may be more elegant way
			peerHandler = registerPeerHandler();
		} catch (IOException e) {
			Core.logger.log(
				this,
				"IOException constructing "
					+ this
					+ ": "
					+ e
					+ "("
					+ link
					+ ") for "
					+ this
					+ ", terminating",
				e,
				Logger.MINOR);
			// Probably closed
			terminate();
			throw e;
		} catch (Error e) {
			terminate();
			Core.logger.log(
				this,
				"Got " + e + " in CH.<init> for " + this,
				Logger.ERROR);
			throw e;
		} catch (RuntimeException e) {
			terminate();
			Core.logger.log(
				this,
				"Got " + e + " in CH.<init> for " + this,
				Logger.ERROR);
			throw e;
		} finally {
			// Finalizer is called even if constructor throws
			profilingHelperTool.incInstanceCount();
		}
    }

    private PeerHandler registerPeerHandler() {
		if(identity != null) {
			Core.logger.log(
				this,
				"ConnectionHandler does not support FNP traffic! "
					+ "To make it do so please add the necessary code to add the node to the RT",
				Logger.ERROR);
			return node.connections.makePeerHandler(identity, null, p);
		} else {
			if(!(link instanceof PlainLink)) {
				Core.logger.log(
					this,
					"Link: "
						+ link
						+ " not a PlainLink in "
						+ this
						+ ".registerOCM()",
					Logger.ERROR);
				return null;
			} else {
				// Even when there is only one conn, a PeerHandler must be created because it is the message queue object
				PeerHandler ph =
					new PeerHandler(
						null,
						null,
						node,
						Node.muxTrailerBufferLength
				/* it might become muxing later */
				, maxPacketLength, p);
				return ph;
				// Not registered anywhere - it will be GC'd when it is finished with
			}
		}
	}
	
	private void logDEBUG(String s) {
		logDEBUG(s, false);
	}
	
	private void logDEBUG(String s, boolean trace) {
		String out = logString(s);
		if (!Core.logger.shouldLog(Logger.DEBUG, this))
			return;
		if (trace)
			Core.logger.log(this, out, new Exception("debug"), Logger.DEBUG);
		else
			Core.logger.log(this, out, Logger.DEBUG);
	}
	
	private String logString(String s) {
		return s
			+ " ("
			+ this
			+ ","
			+ accumStatus()
			+ ") "
			+ (movingTrailingFields ? "(trailing)" : "(not trailing)")
			+ (disabledInSelector ? "(disabled in selector)" : "(reading)");
	}
	
	public boolean isSendingPacket() {
		return sentPacket != null;
	}
	
	public void registerOCM() {
		if (finalized.state())
			return;
		if (logDEBUG)
			logDEBUG("registerOCM");
		if(!initRegisteredInOCM) {
			if (logDEBUG)
				logDEBUG("registeringOCM");
			if (identity != null)
				ocm.put(this);
			initRegisteredInOCM = true;
		}
		if (logDEBUG)
			logDEBUG("registered in OCM");
		Identify i = null;
		if(link instanceof FnpLink) {
			// Find some way to do this that is less hackish! FIXME!
			// Now SEND THE IDENTIFY
			i =
				new Identify(
					Core.getRandSource().nextLong(),
					node.getNodeReference());
		}
		if (receiveClosed.state()
			&& receivingCount <= 0
			&& sendClosed.state()
			&& !trailerSendState.isSendingTrailer()) {
			logDEBUG("terminating at beginning of registerOCM");
			terminate();
			return;
		}
		try {
			synchronized(sentPacketLock) {
				// 10 minute timeout - if we haven't set it up in 10 minutes we're probably not going to!
				sentPacket =
					peerHandler.getPacket(
						p,
						identity,
						i,
						600 * 1000,
						false);
				// It does not matter when it gets the Identify so timeout 0
				if(logDEBUG)
					Core.logger.log(
						this,
						"Sending "
							+ sentPacket
							+ " on "
							+ this
							+ "in registerOCM",
						Logger.DEBUG);
			}
		} catch (IOException e) {
			logDEBUG("Caught "+e+" in getPacket in registerOCM");
		}
		if(sentPacket != null)
			innerSendPacket(sentPacket.priority());
		if (receiveClosed.state()
			&& receivingCount <= 0
			&& sendClosed.state()
			&& !trailerSendState.isSendingTrailer()) {
			logDEBUG("terminating at end of registerOCM");
			terminate();
		} else {
			if (identity != null)
				Core.logger.log(
					this,
					"ConnectionHandler does not support FNP traffic! "
						+ "To make it do so please add the necessary code to add the node to the RT",
					Logger.ERROR);
			try {
				peerHandler.registerConnectionHandler(this);
			} catch (RemovingPeerHandlerException e) {
				logDEBUG(
					"Waiting for PeerHandler to finish removing: "
						+ peerHandler
						+ ": "
						+ e);
				peerHandler.waitForRemovedFromOCM();
				node.reference(null, identity, null, null);
				peerHandler = ocm.getPeerHandler(identity);
			}
		}
	}
	
	public boolean shouldThrottle() {
		tcpConnection c = conn;
		if (c == null)
			return false;
 		//return movingTrailingFields;
		return c.shouldThrottle();
	}
	
	public boolean countAsThrottled() { 
		tcpConnection c = conn;
		if (c == null)
			return false;
		//return !movingTrailingFields;
 		return c.countAsThrottled();
	}
	
	public void setPeerHandler(PeerHandler ph) 
		throws RemovingPeerHandlerException {
		if(peerHandler == null) {
			peerHandler = ph;
			ph.registerConnectionHandler(this);
		} else {
			if (peerHandler == ph)
				return;
			else
				Core.logger.log(
					this,
					"Trying to set peerHandler on "
						+ this
						+ " to "
						+ ph
						+ " but already set",
					new Exception("debug"),
					Logger.NORMAL);
		}
	}
	
	public int getLocalPort() {
		if (finalized.state())
			return CHANNEL_CLOSED_PORTNUMBER;
		if (!chan.isOpen())
			return CHANNEL_CLOSED_PORTNUMBER;
		return sock.getLocalPort();
	}

	private String bufStatus(String name, ByteBuffer b) {
		if (b == null)
			return name + ":(null)";
		else
			return name
				+ ":"
				+ b.position()
				+ "/"
				+ b.limit()
				+ "/"
				+ b.capacity()
				+ "/"
				+ toString();
	}
	
	private String accumStatus() {
		return bufStatus("accumulator", accumulator);
	}
	
	private void tryReregister() {
		if (logDEBUG)
			logDEBUG("tryReregister");
		ByteBuffer a = accumulator;
		if(a == null) {
			if (logDEBUG)
				logDEBUG("Not reregistering because accumulator null");
			return;
		}
		if (logDEBUG)
			logDEBUG("Still here");
		if (disabledInSelector
			&& ((a.capacity() - a.limit()) > (a.capacity() / 4))) {
			if (logDEBUG)
				logDEBUG("Reregistering");
			reregister(); // FIXME: hardcoded
		}
		if (logDEBUG)
			logDEBUG("Left tryReregister");
	}
	
	/**
	 * Must be called synchronized(accumulator)
	 */
	private void reregister() {
		if (logDEBUG)
			logDEBUG("Reregistering");
		disabledInSelector = false;
		reregistering = true;
		try {
			rsl.scheduleMaintenance(chan, this);
		} catch (Throwable e) {
			Core.logger.log(
				this,
				"Cannot reregister " + this +", due to " + e + ", terminating",
				e,
				Logger.ERROR);
			terminate();
		}
		Core.logger.log(
			this,
			"Reregistered "
				+ this
				+ "("
				+ accumStatus()
				+ ") with RSL, apparently successful",
			Logger.MINOR);
	}

	boolean sentHeader = false;
	
	public void registered(){
	    // Do nothing
	}
	public void unregistered(){
		if (logDEBUG)
			logDEBUG("Unregistered", true);
	}
	public ByteBuffer getBuf() {
		tcpConnection c = conn;
		if(c == null) return null;
		else return conn.getInputBuffer();
	}
    /**
     * the accumulating method
     * ONLY called by ReadSelectorLoop
     */
    public int process(ByteBuffer b) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		synchronized(receiveClosed) {
			if(receiveClosed.state()) {
				logDEBUG("receiveClosed, not processing any more bytes");
				return -1; // close
			}
			// receiveClosed gets toggled if something drastic happens e.g.
			// corrupt trailing fields
		}
		//if(b == null) b = rawAccumulator; Why should _we_ care.. it is someone else that has made the mistake
		if(b.limit() > 0) {
			int initialLimit = b.limit();
			if (initialLimit > DECRYPT_SIZE)
				throw new Error(
					"you probably changed the size of the tcpConnection's buffer "
						+ "and forgot to change the size of the static ciphertext buffer");
			if (b != conn.getInputBuffer())
				//Just a check.. it should always be so
				throw new IllegalStateException(
					"b NOT EQUAL TO rawAccumulator! - b = "
						+ b
						+ ", rawAccumulator="
						+ conn.getInputBuffer());
			if (logDEBUG)
				logDEBUG(
					"process("
						+ bufStatus("rawAccumulator", conn.getInputBuffer())
						+ ")");
			InputStream decrypted=null;
			
			// do not need synchronized(rawAccumulator) since we are run in the RSL thread and only process() uses rawAccumulator - FIXME if we ever change this
			//copy the information from rawAccumulator into ciphertext
			
			//assume the ciphertext is cleared
			if (logDEBUG)
				logDEBUG("Created ciphertext[], size " + initialLimit);
			b.get(ciphertext, 0, initialLimit);
			
			//put rawAccumulator in ready to append state
			b.flip();
			if (logDEBUG)
				logDEBUG(
					"Flipped rawAccumulator: "
						+ bufStatus("rawAccumulator", conn.getInputBuffer()));
			ByteArrayInputStream is = 
				new ByteArrayInputStream(ciphertext,0,initialLimit);
			
			if(is == null)
				throw new IllegalStateException("null ByteArrayInputStream!");
			
			Link l = this.link;
			if (l == null)
				return -1;
			
			if (logDEBUG)
				logDEBUG("Getting stream from " + l);
			
			decrypted = l.makeInputStream(is);
			
			if(decrypted == null)
				return -1; // already closed
			
			if ((!sentHeader) && is.available() < l.headerBytes())
				return 1; // Need more bytes for IV
			
			// we have enough data. 
			// RSL wants it in position = 0, limit = end of bytes to read by RSL
			b.limit(0); // we have eaten all data

			// FIXME: optimize!
			
			// number of bytes in may != number of bytes out - IV
			if (logDEBUG)
				logDEBUG("Decrypting " + initialLimit + " bytes ");
			
			//lets see if we'll get away with this...
			decryptLen = 0;
			try {
				try {
					ByteBuffer a = accumulator;
					if (a == null)
						return -1; // closed already
					synchronized(a) {
						if (accumulator == null)
							return -1;
						int accumulatorCurLimit = a.limit();
						if (accumulatorCurLimit + initialLimit > BUF_SIZE) {
							if(reregistering) {
								Core.logger.log(
									this,
									"Buffer still full "
										+ "while reregistering!: "
										+ conn.getInputBuffer()
										+ " in process ("
										+ this
										+ ") - terminating",
									Logger.ERROR);
								terminate();
								return -1;
							}
							Core.logger.log(
								this,
								"Disabling in selector: "
									+ this
									+ " because: "
									+ accumStatus()
									+ " ("
									+ initialLimit
									+ " bytes ciphertext)",
											Logger.MINOR);
							disabledInSelector = true;
							// See above comments - don't need to lock rawAccumulator because we are on the RSL thread
							b.limit(initialLimit);
							return 0;
						}
						
						if(logDEBUG)
							logDEBUG(
								"Reading into _accumulator from "
									+ accumulatorCurLimit
									+ " for "
									+ initialLimit
									+ " bytes");
						try {
							decryptLen =
								decrypted.read(
									_accumulator,
														accumulatorCurLimit,
														initialLimit);
						} catch (IndexOutOfBoundsException e) {
							// FIXME: this is to catch hirvox's bug
							// Can probably be removed later
							Core.logger.log(
								this,
								"Caught "
									+ e
									+ " in CH.process(), doing "
									+ "decrypted.read("
									+ accumStatus()
									+ ","
									+ accumulatorCurLimit
									+ ","
									+ initialLimit
									+ "): "
									+ e,
								e,
								Logger.ERROR);
							throw e;
						}
						if(decryptLen > 0) {
							a.limit(a.limit() + decryptLen);
						}
					}
				} catch (IOException e) {
					decryptLen = 0;
				}
				if (logDEBUG)
					logDEBUG("Decrypted " + decryptLen + " bytes");
				/********/
				
				if (decryptLen <= 0)
					return 1;
				// If we just have the IV, it will return -1
				
				if (logDEBUG)
					logDEBUG(
						accumStatus()
							+ ", reading from: "
							+ bufStatus("raw", b));
				
			} catch (BufferOverflowException e) {
				//TODO: log that we buffered more than 64K
				//and perhaps create another buffer?
				Core.logger.log(
					this,
					"Buffer overflowed receiving message in "
						+ "ConnectionHandler! tried to cache "
						+ (BUF_SIZE - accumulator.remaining() + b.remaining())
						+ " for "
						+ this,
					e,
					Logger.ERROR);
				synchronized(receiveLock) {
					receiveClosed.change(true);
					receiveLock.notify();
				}
				return -1;
			}
		}
		if (logDEBUG)
			logDEBUG("outer process(" + bufStatus("b", b) + ")");
		if(movingTrailingFields && doneMovingTrailingFields) {
			movingTrailingFields = false;
			doneMovingTrailingFields = false;
		}
		if(reregistering) {
			logDEBUG("Completing reregistration");
			try {
				rsl.register(chan, this);
			} catch (Throwable thrown) {
				Core.logger.log(this, "Thrown " + thrown + 
				        " reregistering " + this, Logger.ERROR);
				terminate();
				return -1;
			} finally {
				reregistering = false;
			}
		}
		int x = innerProcess();
		return x;
	}
    
	boolean closeNow = false;
	
    private int innerProcess() {
		int msgsThisTime = 0;
		ByteBuffer x = accumulator;
		//accumulator might be set to null otherplace, create another reference and test *that one* to avoid having to sync
		if (x == null)
			return -1;
		int origDecryptLen = x.limit();
		while (true) {
			RawMessage m=null;
			Message msg = null;
			int processLen = -1;
			if(!movingTrailingFields) {
				try {
					if (x == null)
						return -1;
					synchronized(x) {
						if (accumulator == null)
							return -1;
						if (logDEBUG)
							logDEBUG("innerProcess()", true);
						decryptLen = accumulator.limit();
						if(decryptLen > 0) {
							if (logDEBUG)
								logDEBUG(
									"Trying to readMessage from "
										+ decryptLen
										+ " bytes");
							// Read any padding
							int i=0;
							for(i=0;i<decryptLen;i++) {
								// FIXME: trying to catch an annoying crash (for hirvox)
								byte[] b = _accumulator;
								if(b == null) {
									Core.logger.log(
										this,
										"Null _accumulator in innerProcess()!! for "
											+ this,
										Logger.ERROR);
									break;
								} else if(b.length <= i) {
									Core.logger.log(
										this,
										"buffer too short: buffer length is "
											+ b.length
											+ ", index is "
											+ i,
										Logger.ERROR);
									break;
								} else {
									if(_accumulator[i] != 0)
										break;
								}
								// No max padding because we wouldn't do anything different
							}
							if (logDEBUG)
								logDEBUG(i + " bytes of padding");
							if(i < (decryptLen - 1)) { // at least one byte...
								try {
									m =
										p.readMessage(
											_accumulator,
											i,
											decryptLen - i);
								} finally {
									processLen = p.readBytes() + i;
									// ASSUMPTION: 1 byte -> 1 byte
									if (logDEBUG)
										logDEBUG(
											"Tried to readMessage from "
												+ decryptLen
												+ " bytes, used "
												+ processLen);
								}
							}
							//use directly the backing byte[]
							if (m!=null) {
								if(processLen == -1)
									throw new IllegalStateException("Read -1 bytes but still got a message!");
								if (logDEBUG)
									logDEBUG(
										"Got Message: "
											+ m.toString()
											+ ", clearing buffer after read. Message was:\n"
											+ new String(
												_accumulator,
												0,
												processLen));
								
								accumulator.position(processLen);
								accumulator.compact().flip();
								// compact() is designed for write mode
								if (logDEBUG)
									logDEBUG(
										"Cleared buffer after read: "
											+ accumStatus());
							} else {
								if(i > 0) {
									accumulator.position(i);
									accumulator.compact().flip();
									if(accumulator.limit() == 0) {
										if (logDEBUG)
											logDEBUG("Packet was all padding");
										// 								return 1;
									}
								}
								if (processLen == -1)
									processLen = 0;
								if(logDEBUG) {
									try {
										Core.logger.log(
											this,
											"Didn't get message for "
												+ this
												+ " from "
												+ decryptLen
												+ " bytes: \n"
												+ new String(
													_accumulator,
													0,
													decryptLen,
																   "ISO-8859-1"),
														Logger.MINOR);
									} catch (UnsupportedEncodingException e) {
										Core.logger.log(
											this,
											"Unsupported Encoding ISO-8859-1!",
											e,
											Logger.ERROR);
									}
								}
							}
						} else {
							if (logDEBUG)
								logDEBUG("Returning to RSL because decrypt buffer empty");
							// 					return 1; // buffer empty
						}
						if (logDEBUG)
							logDEBUG("Leaving synchronized(x)");
					}
					if (logDEBUG)
						logDEBUG("Left synchronized(x)");
				} catch (InvalidMessageException e){ //almost copypasted from below
					Core.logger.log(
						this,
						"Invalid message: " + e.toString() + " for " + this,
						e,
						Logger.MINOR);
					invalid++;
					if (invalid >= maxInvalid) {
						Core.logger.log(
							this,
							invalid
								+ " consecutive bad messages - closing "
								+ this
								+ ".",
							Logger.MINOR);
						synchronized(receiveLock) {
							receiveClosed.change(true);
							receiveLock.notify();
						}
						return -1;
					} else {
						if(processLen > 0) {
							synchronized(accumulator) {
								accumulator.position(processLen);
								accumulator.compact().flip();
								if (logDEBUG)
									logDEBUG("Invalid message for " + this);
							}
						}
						continue; // Don't drop the next message
					}
				}
				if (logDEBUG)
					logDEBUG("Left try{}");
				//at this point we have returned succesfully from tryPrase
				//if m is null, we need more data
				if (m==null) {
					if (logDEBUG)
						logDEBUG("Did not get complete RawMessage");
				} else {
				
					//if not, get on with processing the message
				
					if (logDEBUG)
						logDEBUG("Receiving RawMessage: " + m);
				
					//received a close message
					if (m.close) {
						closeNow = true;
						if (logDEBUG)
							logDEBUG(
								"Will close connection because message ("
									+ m
									+ ") said so");
						// FIXME: transfer trailing field first
					}
				
					//this is copy/pasted from below
					if (m.sustain && !closeNow)
						persist.change(true);
				
					//if we have trailing field, tell transport to unregister us
					//TODO:move the creation of ReadInputStream for after the ticker
					if (m.trailingFieldLength > 0) {
						Core.diagnostics.occurrenceCounting(
							"readLockedConnections",
							1);
						movingTrailingFields = true;
						++receivingCount;
						//incReceiveQueue(m.trailingFieldLength); //Handled in ReceiveInputStream
						if (logDEBUG)
							logDEBUG("Starting to transfer trailing fields");
						
						CHInputStream is = new CHInputStream();
						m.trailingFieldStream =
							currentInputStream =
								new ReceiveInputStream(
									is,
									m.trailingFieldLength,
												   m.messageType);
					}
				
					try {
						msg = t.getMessageHandler().getMessageFor(this, m);
					} catch (InvalidMessageException e) {
						Core.logger.log(
							this,
							"Invalid message: " + e.toString(),
										Logger.MINOR);
						invalid++;
						if (invalid >= maxInvalid) {
							if(logDEBUG) 
								logDEBUG(
									invalid
										+ " consecutive bad messages - closing");
							synchronized(receiveLock) {
								receiveClosed.change(true);
								receiveLock.notify();
							}
							return -1;
						}
					}
				
					if(msg == null && m.trailingFieldLength > 0) {
						try {
							m.trailingFieldStream.close();
						} catch (IOException e) {
							if(logDEBUG) 
								Core.logger.log(
									this,
									"IOException closing trailing "
										+ "field stream for "
										+ this
										+ ": "
										+ e,
									e,
												Logger.DEBUG);
						}
						if (logDEBUG)
							logDEBUG(
								"Invalid message but started to transfer "
									+ "trailing fields - closing");
						synchronized(receiveLock) {
							receiveClosed.change(true);
							receiveLock.notify();
						}
						return -1;
					}
				
					//do not copy/paste the watchme and debug stuff
					//it can be done later on
				
					if(msg != null) {
						msg.setReceivedTime(System.currentTimeMillis());
						//We now have a complete message.. pipe it on to whatever desitny awaits
						handleReceivedMessage(msg);
						invalid = 0;
						Core.getRandSource().acceptTimerEntropy(recvTimer);
						msgsThisTime++;
					}
				}
				if(closeNow && !movingTrailingFields) {
					if (logDEBUG)
						logDEBUG("Closing, finished moving trailing fields");
					synchronized(receiveLock) {
						receiveClosed.change(true);
						receiveLock.notify();
					}
					if (!sendClosed.state())
						Core.diagnostics.occurrenceCounting("peerClosed", 1);
					//simply return -1; the RSL will put us on the close queue
					ByteBuffer b = accumulator;
					if (b!=null)
						synchronized(b) {
							b.clear();
						}
					if(origDecryptLen > 0) {
						Core.diagnostics.occurrenceContinuous(
							"messagesInPacketReceived",
												 msgsThisTime);
						if(logDEBUG) 
							logDEBUG(
								"messagesInPacketReceived: " + msgsThisTime);
					}
					synchronized(sendLock) {
						if(sendClosed.state()) {
							if (logDEBUG)
								logDEBUG("Forcing close RIGHT NOW");
							if(!trailerSendState.isSendingTrailer()) {
								Core.logger.log(
									this,
									"Terminating "
										+ this
										+ " in innerProcess(), other side asked and no trailers sending",
										Logger.DEBUG);
								terminate();
							}
							return -1;
						} else {
							if (logDEBUG)
								logDEBUG(
									"Taking off RSL, waiting for writes to finish",
									true);
							return 0;
						}
					}
				}
				
				// Prevent problems with simultaneous closure
				ByteBuffer a = accumulator;
				if (a == null)
					return -1; // already closed
				if(a.remaining() == 0 || movingTrailingFields) {
					if (logDEBUG)
						logDEBUG(
							"Returning to RSL because no remaining "
								+ "or moving trailing fields");
					if(origDecryptLen > 0) {
						Core.diagnostics.occurrenceContinuous(
							"messagesInPacketReceived",
							msgsThisTime);
						if(logDEBUG) 
							logDEBUG(
								"messagesInPacketReceived: " + msgsThisTime);
					}
					if (a.capacity() - a.limit() < 2048 /* FIXME: hardcoded */
						) {
						if(logDEBUG) 
							logDEBUG("Disabling in selector at end of message processing");
						disabledInSelector = true;
						return 0;
					} else {
						return 1;
					}
				} else {
					if (logDEBUG)
						logDEBUG("Trying to process next message");
					//if (explained) return 1; //perhaps this is what you meant?
					if(m == null) {
						if (logDEBUG)
							logDEBUG("Did not get a message, awaiting more data");
						if(origDecryptLen > 0) {
							Core.diagnostics.occurrenceContinuous(
								"messagesInPacketReceived",
								msgsThisTime);
							if (logDEBUG)
								logDEBUG(
									"messagesInPacketReceived: "
										+ msgsThisTime);
						}
						return 1;
					} else {
						if (logDEBUG)
							logDEBUG("Got a message, looping");
						continue;
					}
				}
			} else {
				// Transferring trailing fields - data is on buffer
				invalid = 0;
				ByteBuffer acc = accumulator;
				if(acc == null) 
					throw new IllegalStateException("process() after closure!");
				synchronized(acc) {
					if(accumulator == null) 
						throw new IllegalStateException("process() after closure!");
					if (logDEBUG)
						logDEBUG("Transferring trailing fields in innerProcess()");
					/*if (!CHISwaitingForNotification){
					  Core.logger.log(this,"scheduler notified CH.innerProcess before CHIS.wait()!!!",Logger.MINOR);
					  CHISalreadyNotified=true;
					  }*/
					//notify it anyways...
					accumulator.notifyAll();
					if (accumulator.capacity() - accumulator.limit() < 2048
						/* FIXME: hardcoded */
						) {
						if (logDEBUG)
							logDEBUG("Disabling in selector");
						disabledInSelector = true;
						return 0;
					} else {
						return 1;
					}
				}
			}
		}
    }
    //Should be called whenever a new message is rceived over the connection.
    //"Consumes" the supplied message (usually by dispatching it to the ticker) 
	private void handleReceivedMessage(Message msg) {
		messagesReceived++;
		if(peerHandler == null)
			Core.logger.log(
				this,
				"peerHandler NULL when trying to notify peerHandler of new message arrived on "
					+ this,
				new Exception("debug"),
				Logger.ERROR);
		else
			peerHandler.registerMessageReceived(msg);
		//TODO: We need to count the trailing field length a some point too
		if (msg instanceof Identify) {
			///TODO: Somewhat hackish handling of a received Identify message
			// Tells us the identity of the other end
			Identify id = (Identify)msg;
			handleReceivedIdentifyMessage(id);
		}
		// Identify gets to set the ref on-thread, and get processed
		// properly so it can be verified and added to the RT if necessary,
		// off-thread
		t.add(0, msg);
		if (logDEBUG)
			logDEBUG("Scheduled " + msg + " on ticker");
	}
	//Should be called whenever an new 'Identify' message is received over the connection 
	//Updates the peerHandler and this connection with a new NodeReference for the peer
	private void handleReceivedIdentifyMessage(Identify id) {
		if(peerHandler == null)
			Core.logger.log(
				this,
				"peerHandler NULL on " + this,
				new Exception("debug"),
				Logger.ERROR);
		else
			peerHandler.updateReference(id.getRef());
		if(Core.logger.shouldLog(Logger.MINOR,this)) 
			Core.logger.log(
				this,
				"Got Identify: other end is " + id.getRef() + " for " + this,
				Logger.MINOR);
	}
	
	int CHOSsent;
	//this will be the callback from the WSL
	
	int lastSizeDone = 0;
	
	public void jobPartDone(int size) {
		if (logDEBUG)
			Core.logger.log(
				this,
				"Partial notification: "
					+ size
					+ " bytes written successfully on "
					+ this,
				Logger.DEBUG);
		lastSizeDone = size;
		if(!trailerSendState.isSendingTrailer()) {
			if(sentPacket == null) {
				if(!finalized.state())
					Core.logger.log(
						this,
						"sentPacket NULL in jobPartDone! for " + this,
						Logger.ERROR);
			} else
				sentPacket.jobDone(false, size, peer, null, null);
		}
	}
	
	boolean checkingSending = false;
	Object checkingSendingLock = new Object();
	
	public void jobDone(int size, boolean jobSucceeded) {
		lastActiveTime = System.currentTimeMillis();
		boolean logMINOR = Core.logger.shouldLog(Logger.MINOR, this);
		if (logDEBUG)
			logDEBUG(
				"CH.jobDone(" + size + "," + jobSucceeded + ") for " + this);
		
		//we should check the status if terminate() is called from elsewhere 
		if (!jobSucceeded) {
			if(logDEBUG)
				Core.logger.log(
					this,
					"jobDone failed for " + this,
					new Exception("debug"),
					Logger.DEBUG);
			//tell everybody they failed 
			//this is where the PeerHandler will really help
			// Locking!
			if ((!trailerSendState.isSendingTrailerChunk())
				&& (!finalized.state())) {
				if(sentPacket == null)
					Core.logger.log(
						this,
						"sentPacket NULL! for " + this,
						new Exception("debug"),
						Logger.ERROR);
				else {
					sentPacket.jobDone(true, size, peer, null, null);
				}
				sentPacket = null;
				return;
			}
		}
		registerDataSent(size);
		
		if (trailerSendState.isSendingTrailerChunk()) {
			try {
				synchronized (trailerSendState) {
					trailerSendState.registerChunkWritten(size);
					if (trailerSendState.hasTrailerDataRemaining())
						return;
					// wait for next write (registerChunkWritten will have notified the sender that more data is needed)
					// else we can send another packet
				}
			} catch (TrailerSendState.PartialChunkSentException e) {
				if (logMINOR)
					Core.logger.log(
						this,
						"Trailer chunk send failed for " + this +", closing",
						Logger.MINOR);
				synchronized (sendLock) {
					sendClosed.change(true);
					sendLock.notifyAll();
				}
				if (this.identity != null)
				ocm.markClosed(this);
				trailerSendState.reset();

				return; // closed conn, don't need another packet
			}
		} else {
			if (logDEBUG)
				logDEBUG("still here - not sending trailer chunk");
			TrailerWriter tw = null;
			PeerPacket packet = null;
			synchronized(sentPacketLock) {
				if(logDEBUG)
					logDEBUG("synchronized");
				if(sentPacket != null) {
					packet = sentPacket;
					if(logDEBUG)
						logDEBUG("sentPacket != null");
					sentPacket = null;
					if(logDEBUG)
						logDEBUG("Set sentPacket to null");
					// If we have a trailer, must deal with it immediately
					// If we unlock then deal with it, another thread could
					// start sending a regular packet...
					if(packet.hasTrailer()) {
						if(logDEBUG)
							logDEBUG("packet has trailer");
						
						tw =
							new MyTrailerWriter(
								trailerSendState.startNew(
									packet.trailerLength()));
						
						if(logDEBUG)
							logDEBUG("Creating "+tw);
					}
				}
			}
			if(packet != null) {
				if(logDEBUG)
					logDEBUG("Packet "+packet+" not null");
				packet.jobDone(true, size, peer, tw, null);
				if(packet.hasCloseMessage()) {
					synchronized(sendLock) {
						sendClosed.change(true);
						sendLock.notifyAll();
					}
				}
			}
		}
		boolean needTerminate = false; // don't terminate while holding locks!
		if(sendClosed.state() && !sendingCloseMessage) {
			if (receiveClosed.state()
				&& receivingCount == 0
				&& sendClosed.state()
				&& !trailerSendState.isSendingTrailer()) {
				if (logDEBUG)
					logDEBUG("Terminating in jobDone");
				terminate();
			}
			return;
		}
		if (trailerSendState.isSendingTrailer())
			return;
		if(logDEBUG)
			logDEBUG("Trying to send a packet...");
		// This is nasty...
		// Only way to START a trailer send is through this function
		// sentPacket -> null
		// sentPacketLock unlocked
		// other thread starts sending a message with a trailer
		// other thread hits jobDone
		// other thread locks sentPacketLock, clears sentPacket
		// other thread sets trailerSendID, unlocks sentPacketLock
		// But if we check trailerSendID right after relocking it, we're ok
		PeerPacket mySentPacket = null;
		synchronized(sentPacketLock) {
			if (trailerSendState.isSendingTrailer())
				return;
			if(sentPacket == null) {
				if (logDEBUG)
					logDEBUG("synchronized...");
				if(sendingCloseMessage) {
					Message  cm  = p.getCloseMessage();
					if(cm != null) {
						try {
							sentPacket =
								mySentPacket =
									peerHandler.getPacket(
										p,
													  identity,
										cm,
										600 * 1000,
										true);
							// It does not matter when they get the close msg
							if(logDEBUG)
								Core.logger.log(
									this,
									"Sending "
										+ sentPacket
										+ " ("
										+ cm
										+ ") on "
										+ this
										+ " in jobDone",
									Logger.DEBUG);
						} catch (IOException e) {
							mySentPacket = sentPacket = null;
							if (logDEBUG)
								logDEBUG(
									"Caught "
										+ e
										+ " in getPacket (close message) in jobDone");
						}
					}
					sendingCloseMessage = false;
				} else {
					sentPacket =
                    	mySentPacket = peerHandler.getPacket(p);
						if(logDEBUG)
                    	Core.logger.log(
                    		this,
                    		"Sending "
                    			+ sentPacket
                    			+ " on "
                    			+ this
                    			+ " in jobDone(B)",
                    		Logger.DEBUG);
				}
			}
		}
		
		if (mySentPacket != null) {
			if(mySentPacket != sentPacket) {
				if(!finalized.state())
					Core.logger.log(
						this,
						"mySentPacket = "
							+ mySentPacket
							+ ", but sentPacket = "
							+ sentPacket
							+ " ("
							+ this
							+ ")",
						Logger.ERROR);
			} else {
				innerSendPacket(sentPacket.priority());
				int sentPacketLength = mySentPacket.getLength();
				int sentPacketMessages = mySentPacket.countMessages();
				if(!sendClosed.state()) {
					// We have sent a packet. Yay.
					if(logMINOR) 
						Core.logger.log(
							this,
							"Sent packet of size "
								+ sentPacketLength
								+ " containing "
								+ sentPacketMessages
								+ " messages on "
								+ this,
							Logger.MINOR);
					Core.diagnostics.occurrenceContinuous(
						"messagePacketSizeSent",
														  sentPacketLength);
					Core.diagnostics.occurrenceContinuous(
						"messagesInPacketSent",
														  sentPacketMessages);
				}
			}
			needTerminate = 
				receiveClosed.state()
					&& receivingCount == 0
					&& sendClosed.state()
					&& !trailerSendState.isSendingTrailer();
		}
		if(needTerminate) {
			if (logDEBUG)
				logDEBUG("Terminating in jobDone (B)");
			terminate();
		}
		if (logDEBUG)
			Core.logger.log(this, "exiting CH.jobDone()", Logger.DEBUG);
	}
	
	//Called for accouting purposes only
    private void registerDataSent(int size) {
    	transferAccounter.registerSentData(size);
	}

	/**
     * uh, what do we do here?
     */
    public void closed() {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if (logDEBUG)
			logDEBUG("ConnectionHandler closed() called", true);
		//this shouldn't be here but I want to test it
		terminate();
		if (logDEBUG)
			logDEBUG("ConnectionHandler closed() completed");
    }
    
	public void queuedClose() {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if (logDEBUG)
			logDEBUG("Queued close", true);
		boolean wasTerminated = finalized.state();
		terminate();
		if (logDEBUG)
			logDEBUG("Terminated in queuedClose()");
		// FIXME: if messages == null, need to tell ConnOpener to backoff
		if(messagesReceived == 0 && !wasTerminated) {
			Identity id = peerIdentity();
			if (id != null) {
				// Revoke connection success and backoff
				// Then reschedule
				peerHandler.revokeConnectionSuccess();
			}
		}
		if((!wasTerminated) && peerIdentity() != null)
			node.rescheduleConnectionOpener();
	}
	
	protected class MyTrailerWriter implements TrailerWriter {
		int id;
		boolean closed = false;
		
		MyTrailerWriter(int id) {
			this.id = id;
		}
		
		public void writeTrailing(
			byte[] block,
			int offset,
			int length,
								  TrailerWriteCallback cb)
			throws
				UnknownTrailerSendIDException,
				TrailerSendFinishedException,
				AlreadySendingTrailerChunkException,
				IOException {
			ConnectionHandler.this.writeTrailing(id, block, offset, length, cb);
		}
		
		public void close() {
		    closed = true;
			closeTrailer(id, false);
		}

        public boolean isClosed() {
            return closed;
        }

        public boolean wasTerminated() {
            return false;
        }

        public boolean wasClientTimeout() {
            return false;
        }

        public boolean isExternal() {
            return identity != null;
        }
	}
	
	public void writeTrailing(
		int id,
		byte[] block,
		int offset,
		int length,
							  TrailerWriteCallback cb) 
		throws
			UnknownTrailerSendIDException,
			TrailerSendFinishedException,
			AlreadySendingTrailerChunkException,
			IOException {
		lastActiveTime = System.currentTimeMillis();
		if(finalized.state())
			throw new IOException("Closed: "+finalized.state()+":"+this);
		synchronized(trailerSendState) {
			trailerSendState.registerStartChunkWrite(id, offset,length,cb);
			Link l = link;
			if(l == null) {
				IOException e = 
					new IOException("Connection closed in trailer send!");
				Core.logger.log(
					this,
					"Oops: " + e + " (" + this +")",
					e,
					Logger.NORMAL);
				closeTrailer(id, true);
			}
			
			// Send the data
			Core.diagnostics.occurrenceCounting(
				"outputBytesTrailingAttempted",
				length);
			sendBytes(
				block,
				offset,
				length,
				ThrottledAsyncTCPWriteManager.TRAILER);
		}
	}
	
	public void closeTrailer(int id, boolean connDied) {
		if(connDied)
			trailerSendState.closedCH();
		trailerSendState.closeTrailerSend(id);

		// FIXME: different criteria here? In other places there's another term...
		if (receiveClosed.state()
			&& receivingCount == 0
			&& sendClosed.state()) {
			if (logDEBUG)
				logDEBUG("terminating in closeTrailer");
			terminate();
		}
	}
	
    final class CHInputStream extends DiscontinueInputStream {
		// We are AKO DiscontinueInputStream so that RIS can call us
	
		protected boolean dead = false;

		private long waitTime=0;

		public CHInputStream(InputStream s) {
			super(s);
			profilingHelperTool.incCHISCount(); 
		} 
		
		private void logDEBUG(String s, boolean trace) {
			String out = logString(s)+" ("+this+")";
			if(trace)
				Core.logger.log(
					this,
					out,
					new Exception("debug"),
					Logger.DEBUG);
			else
				Core.logger.log(this, out, Logger.DEBUG);
		} 
		
		private void logDEBUG(String s) {
			logDEBUG(s, false);
		}
		
		public CHInputStream() {
			this(new NullInputStream());
		}
		
		public int available() {
			// Might be called after closure?
			ByteBuffer a = accumulator;
			if (a == null)
				return -1;
			synchronized(a) {
				if (accumulator == null)
					return -1;
				return accumulator.remaining();
			} 
		} 
	
		public long skip(long n) throws IOException {
			if(dead || doneMovingTrailingFields || !movingTrailingFields)
				throw new IOException("Trying to read finished CHInputStream");
			ByteBuffer a = accumulator;
			if (a == null)
				return -1;
			synchronized(a) {
				if (logDEBUG)
					logDEBUG("Trying to skip " + n + " bytes");
				while(true) {
					if (accumulator == null)
						return -1;
					if(accumulator.remaining() >= 1) {
						int got = accumulator.remaining();
						if (n < got)
							got = (int) n;
						accumulator.position(got);
						accumulator.compact();
						accumulator.limit(accumulator.position());
						accumulator.position(0);
						if (logDEBUG)
							logDEBUG("Skipped " + got + "/" + n + " bytes");
						tryReregister();
						return got;
					} else {
						if (alreadyClosedLink)
							throw new IOException("Closed");
						// Uh oh...
						try {
							if (logDEBUG)
								logDEBUG("Waiting to skip " + n + " bytes");
							/*if (!CHISalreadyNotified) {
							  CHISwaitingForNotification = true;*/
							if (dead)
								return -1;
							waitTime=System.currentTimeMillis();
							accumulator.wait(5*60*1000);
							if (dead)
								return -1;
							//CHISwaitingForNotification = false;
							if (System.currentTimeMillis() - waitTime
								>= 5 * 60 * 1000) {
								Core.logger.log(
									ConnectionHandler.this,
									"waited more than 5 minutes in CHIS.skip() for "
										+ conn
										+ ":"
										+ ConnectionHandler.this,
									Logger.MINOR);
								close();
								throw new IOException("waited more than 5 minutes in CHIS.skip()");
							}
							//}
							/*if (accumulator.remaining() < 1)
							  Core.logger.log(this,"CHIS.skip() sync screwed up! " 
							  + ConnectionHandler.this 
							  + "alreadyNotified : " + CHISalreadyNotified 
							  + "waiting for notification " +CHISwaitingForNotification,Logger.ERROR);
							  CHISalreadyNotified=false;*/
						} catch (InterruptedException e) {
							if (logDEBUG)
								logDEBUG(
									"Interrupted skip wait for "
										+ n
										+ " bytes");
							throw new IOException("interrupted in CHIS.skip()");
						} finally {
							CHISwaitingForNotification=false;
							CHISalreadyNotified=false;
						} 
						
					}
				} 
			} 
		} 
		 
		public int read(byte[] b) throws IOException {
			if(dead || doneMovingTrailingFields || !movingTrailingFields)
				throw new IOException("Trying to read finished CHInputStream");
			ByteBuffer a = accumulator;
			if (a == null)
				return -1;
//                long startedRead=0;
			synchronized(a) {
				if (logDEBUG)
					logDEBUG("Trying to skip " + b.length + " bytes");
				  
				while(true) {
					if (accumulator == null)
						return -1;
					if (alreadyClosedLink)
						throw new IOException("Closed");
					if (accumulator.remaining() < 1)
						// read returns what is available up to the length
						// it DOES NOT necessarily read the whole buffer!
						try {
							if (logDEBUG)
								logDEBUG(
									"Waiting to skip " + b.length + " bytes");
							//if (!CHISalreadyNotified) {
							//	CHISwaitingForNotification = true;
							if (dead)
								throw new IOException("Trying to skip finished CHInputStream");
							waitTime=System.currentTimeMillis();
							accumulator.wait(5*60*1000);
							if (dead)
								throw new IOException("Trying to skip finished CHInputStream");
							//	CHISwaitingForNotification=false;
							if (System.currentTimeMillis() - waitTime
								>= 5 * 60 * 1000) {
								Core.logger.log(
									ConnectionHandler.this,
									"waited more than 5 minutes in CHIS.read(byte[]): "
										+ conn
										+ ":"
										+ ConnectionHandler.this,
									Logger.MINOR);
								close();
								throw new IOException("Trying to skip finished CHInputStream");
							}
							//}
							/*if (accumulator.remaining() < 1)
							  Core.logger.log(this,"CHIS.read(byte []) sync screwed up! " 
							  + ConnectionHandler.this 
							  + "alreadyNotified : " + CHISalreadyNotified 
							  + "waiting for notification " +CHISwaitingForNotification,Logger.ERROR);
							  CHISalreadyNotified=false;*/
						} catch (InterruptedException e) {
							if (logDEBUG)
								logDEBUG(
									"Interrupted read wait for "
										+ b.length
										+ " bytes");
							throw new IOException("Trying to skip finished CHInputStream"); 
						} finally {
							CHISwaitingForNotification=false;
							CHISalreadyNotified=false;
						} else {
						int get = accumulator.limit();
						int got = accumulator.position();
						get -= got;
						if (b.length < get)
							get = b.length;
						accumulator.get(b, 0, get);
						got = accumulator.position() - got;
						accumulator.compact().flip();
						tryReregister();
						if (logDEBUG)
							logDEBUG("Read " + got + "/" + b.length + " bytes");
						return got;
					}
				} 
			} 
		} 
		 
		public int read (byte[] b, int off, int len) throws IOException {
			if(dead || doneMovingTrailingFields || !movingTrailingFields)
				throw new IOException("Trying to read finished CHInputStream");
			ByteBuffer a = accumulator;
			if (a == null)
				return -1;
			synchronized(a) {
				if (logDEBUG)
					logDEBUG(
						"Trying to CHIS.read(byte[]," + off + "," + len + ")");
				while(true) {
					//long currentWait=0;
					if (accumulator == null)
						return -1;
					if (alreadyClosedLink)
						throw new IOException("Closed");
					if (accumulator.remaining() < 1)
						try {
							if (logDEBUG)
								logDEBUG("Waiting to read " + len + " bytes");
							//if (!CHISalreadyNotified) {
							//	CHISwaitingForNotification = true;
							waitTime=System.currentTimeMillis();
							if (dead)
								return -1;
							accumulator.wait(5*60*1000);
							//if (dead) return -1;
							//	CHISwaitingForNotification=false;
							if (System.currentTimeMillis() - waitTime
								>= 5 * 60 * 1000) {
								Core.logger.log(
									ConnectionHandler.this,
									"waited more than 5 minutes in CHIS.read(byte[],int,int): "
										+ conn
										+ ":"
										+ ConnectionHandler.this,
									Logger.MINOR);
								close();
								return -1;
							}
							//}
							/*if (accumulator.remaining() < 1)
							  Core.logger.log(this,"CHIS.read(byte[],int,int) sync screwed up! " 
							  + ConnectionHandler.this 
							  + "alreadyNotified : " + CHISalreadyNotified 
							  + "waiting for notification " +CHISwaitingForNotification,Logger.ERROR);
							  CHISalreadyNotified=false;*/
							//continue; //WARNING: does this belong here?
						} catch (InterruptedException e) {
							if (logDEBUG)
								logDEBUG(
									"Interrupted read wait for "
										+ len
										+ " bytes");
							return -1; 
						} finally {
							CHISwaitingForNotification=false;
							CHISalreadyNotified=false;
						} else {
						int get = accumulator.limit();
						int got = accumulator.position();
						get -= got;
						if (len < get)
							get = len;
						accumulator.get(b, off, get);
						got = accumulator.position() - got;
						accumulator.compact().flip();
						tryReregister();
						if (logDEBUG)
							logDEBUG("Read " + got + "/" + len + " bytes");
						return got;
					}
				} 
			} 
		} 
		 
		public int read() throws IOException {
			if(dead || doneMovingTrailingFields || !movingTrailingFields)
				throw new IOException("Trying to read finished CHInputStream");
			ByteBuffer a = accumulator;
			if (a == null)
				return -1;
			long startedRead=0;
			synchronized(a) {
				if (logDEBUG)
					logDEBUG("Trying to read 1 byte");
				while(true) {
					if (accumulator == null)
						return -1;
					if(accumulator.remaining() >= 1) {
						int x = accumulator.get();
						accumulator.compact().flip();
						tryReregister();
						if (logDEBUG)
							logDEBUG("Read 1 byte");
						return (x & 0xff);
					} else {
						// Uh oh...
						if (alreadyClosedLink)
							throw new IOException("Closed");
						try {
							if (logDEBUG)
								logDEBUG("Waiting to read() 1 byte");
							//			if (!CHISalreadyNotified) {
							//CHISwaitingForNotification=true;
							if (dead)
								return -1;
							startedRead=System.currentTimeMillis();
							accumulator.wait(5*60*1000);
							if (dead)
								return -1;
							//CHISwaitingForNotification=false;
							if (System.currentTimeMillis() - startedRead
								>= 5 * 60 * 1000) {
								Core.logger.log(
									this,
									"waited more than 5 minutes on CHIS.read() "
										+ conn
										+ ":"
										+ ConnectionHandler.this,
									Logger.MINOR);
								close();
								return -1;
							}
							//			}
							/*if (accumulator.remaining() < 1)
							  Core.logger.log(this,"CHIS.read() sync screwed up! " 
							  + ConnectionHandler.this 
							  + "alreadyNotified : " + CHISalreadyNotified 
							  + "waiting for notification " +CHISwaitingForNotification,Logger.ERROR);
							  CHISalreadyNotified=false;*/
						} catch (InterruptedException e) {
							if (logDEBUG)
								logDEBUG("Interrupted wait: " + e);
							return -1; 
						} finally {
							CHISwaitingForNotification=false;
							CHISalreadyNotified=false;
						} 
					}
					if (logDEBUG)
						logDEBUG("Waited to read() 1 byte", true);
				} 
			} 
		} 
		 
		public void discontinue() {
			if (logDEBUG)
				logDEBUG("Discontinuing read stream");
			innerClose();
			//we should not close this stream when called with discontinue, because it will in turn
			//close the entire connection; discontinue() is called only when we finish reading a trailing
			//field, but we should leave the connection open.
		} 
		 
		/**
		 * sets up outside visible flags and closes the connection
		 * as it should.  In the old code this stream was the same stream
		 * comming out of the connection's socket, so calling close() was expected
		 * to close the socket itself.
		 */
		public void close() {
			if (dead)
				return;
			innerClose();
			rsl.queueClose(chan, ConnectionHandler.this);
		} 
		 
		/**
		 * only sets up outside visible flags
		 * for some reason this still ends up closing the connection
		 * TODO: find out why
		 */
		private void innerClose() {
			logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
			if (logDEBUG)
				logDEBUG("Closing CHIS", true);
	    	dead = true;
	    	doneMovingTrailingFields = true;
			// Why was this commented out?
			ByteBuffer a = accumulator;
			if(a != null) {
				synchronized(a) {
					//if(accumulator != null)
					a.notifyAll();
				}
			}
			tryReregister();
			if (rsl != null
				&& a != null
				&& (closeNow || (a.remaining() > 0))) {
				logDEBUG("Scheduling maintenance");
				rsl.scheduleMaintenance(chan, ConnectionHandler.this);
				logDEBUG("Scheduled maintenance");
			}
			if(rsl == null) {
				throw new IllegalStateException(
					"Do not know my ReadSelectorLoop ! ("
						+ this
						+ ","
						+ ConnectionHandler.this
						+ ")");
			} 
			logDEBUG("Closed CHIS");
		} 
	
		//profiling
		//WARNING:remove before release
		protected void finalize() {
			profilingHelperTool.decCHISCount(); 
		}

    }
    
	protected Object sendBytesLock = new Object();
	// force serialization to keep cipher consistent - probably unnecessary... FIXME
	
	/**
	 * Send some bytes
	 * Note that the toSend will be encrypted!
	 */
	private void sendBytes(byte[] toSend, int off, int len, int priority)
		throws IOException {
		if (logDEBUG)
			Core.logger.log(
				this,
				"Sending " + len + " bytes on " + this,
				new Exception("debug"),
				Logger.DEBUG);
		if(conn == null)
			throw new IOException("Connection closed: "+this);
		if(!chan.isOpen())
			throw new IOException("Channel closed: "+this);
		if(wsl == null) 
			throw new IllegalStateException(
				"wsl null in " + ConnectionHandler.this);
		synchronized(sendBytesLock) {
			link.encryptBytes(toSend, off, len);
			if (!wsl
				.send(
					toSend,
					off,
					len,
					chan,
					ConnectionHandler.this,
					priority)) {
				throw new IOException("Can't write");
			}
		}
		if (logDEBUG)
			Core.logger.log(
				this,
				"Started send of " + len + " bytes on " + this,
				new Exception("debug"),
				Logger.DEBUG);
	}
	
	/** 
	 * Force the ConnectionHandler to start sending a packet
	 * @return true if we sent a packet, false if we were already sending
	 * one.
	 * Throws nothing. Must log an error and return false if it was going to
	 * throw.
	 * Called by PeerHandler.innerSendMessageAsync
	 */
	public final boolean forceSendPacket(PeerPacketMessage ppm) {
		if(sendClosed.state()) {
			Core.logger.log(
				this,
				"forceSendPacket(" + ppm + ") when already closed: " + this,
				Logger.MINOR);
			return false;
		}
		try {
			synchronized(sentPacketLock) {
				if (sentPacket != null)
					return false;
				if(trailerSendState.isSendingTrailer()) {
					if(logDEBUG) 
						logDEBUG(
							"forceSendPacket("
								+ ppm
								+ ") called but sending a trailer!");
					return false;
				}
				sentPacket =
					peerHandler.getPacket(p, identity, ppm, null, false, false);
				if(logDEBUG)
					Core.logger.log(
						this,
						"Sending "
							+ sentPacket
							+ " on "
							+ this
							+ ".forceSendPacket()",
						Logger.DEBUG);
			}
			if(sentPacket != null)
				return innerSendPacket(sentPacket.priority());
			else
				return false;
		} catch (Throwable thrown) {
			Core.logger.log(
				this,
				"Caught " + thrown + " in forceSendPacket",
				thrown,
				Logger.ERROR);
			return false;
		}
	}
	
	/**
	 * Send a packet on the ConnectionHandler. Called by PeerHandler.
	 * @return true if we sent the packet, false if a locking conflict
	 * prevented us sending it, or the connection is closed.
	 */
	public final boolean sendPacket(PeerPacket packet, int prio) {
		if(sendClosed.state()) {
			Core.logger.log(
				this,
				"sendPacket("
					+ packet
					+ ","
					+ prio
					+ ") when already closed: "
					+ this,
				Logger.MINOR);
			packet.jobDone(true, 0, peer, null, null);
			return false;
		}
		synchronized(sentPacketLock) {
			if(sentPacket != null) {
				return false;
			}
			if(trailerSendState.isSendingTrailer()) {
				if(logDEBUG) 
					logDEBUG(
						"sendPacket("
							+ packet
							+ ","
							+ prio
							+ " called but sending a trailer!");
				return false;
			}
			this.sentPacket = packet;
			if(logDEBUG)
				Core.logger.log(
					this,
					"sendPacket sending " + sentPacket + " on " + this,
					Logger.DEBUG);
		}
		innerSendPacket(prio);
		return true;
	}
	
	protected Object innerSendPacketLock = new Object();
	
	/**
	 * Send a packet
	 * Do NOT call while synchronized on sentPacketLock
	 */
	protected boolean innerSendPacket(int prio) {
		lastActiveTime = System.currentTimeMillis();
		PeerPacket sp = sentPacket;
		if(sp == null) {
			if (finalized.state())
				return false; // race with terminate
			Core.logger.log(
				this,
				"innerSendPacket() called but sentPacket is NULL!",
				new Exception("debug"),
				Logger.ERROR);
		}
		byte[] toSend = sp.getBytes();
		// extra paranoia
		if(trailerSendState.isSendingTrailer()) {
			if(logDEBUG) 
				logDEBUG(
					"innerSendPacket("
						+ prio
						+ ","
						+ sp
						+ " called but sending a trailer!");
			return false;
		}
		try {
			Core.logger.log(
				this,
				"Sending packet: \n" + new String(toSend),
				Logger.DEBUG);
			messagesSent += sp.countMessages();
			sendBytes(toSend, 0, toSend.length, prio);
		} catch (IOException e) {
			Core.logger.log(
				this,
				"Got IOException trying to send packet " + sp + ": " + e,
				Logger.MINOR);
			synchronized(sendLock) {
				sendClosed.change(true);
				sendLock.notifyAll();
			}
			sentPacket = null;
			sp.jobDone(true, 0, peer, null, null);
			return false;
		} catch (Throwable thrown) {
			Core.logger.log(this, "Caught " + thrown + 
			        " trying to send packet " + sp, thrown,
				Logger.ERROR);
			synchronized(sendLock) {
				sendClosed.change(true);
				sendLock.notifyAll();
			}
			sentPacket = null;
			sp.jobDone(true, 0, peer, null, null);
			return false;
		}
		return true;
	}
	
// 	boolean needToRemoveFromOCM = false;
	
	// Stay in the OCM until the end, even if we cannot send.
	// We will be terminated eventually. The object is to prevent conn leaks.
	
	void logWatchme(Message m, RawMessage raw) {
		int  htl;
		if (m instanceof HTLMessage)
			htl = ((HTLMessage) m).getHopsToLive();
		else
			htl = -1;
		
		Node.watchme.logSendMessage(
			raw.messageType,
									Long.toHexString(m.id()),
									peer.getAddress().toString(),
									htl);
	}
	
    // IMPORTANT: Don't call this while holding sendLock
    //            or receiveLock or you will cause a
    //            deadlock.
    //
    /**  Removes this CH from the OCM. */
    private final void removeFromOCM() {
        if (identity != null)
            ocm.remove(this);
		if(initRegisteredInOCM) {
// 			incOpenButNotOnOCM();
		}
    }
	
    /**
     *  Initiates a close dialog on this connection by sending a closeMessage() as
     *  specified by the Presentation object if one can be created.
     */
    public void close() {
		if(logDEBUG)
			logDEBUG("close() called");
		if(sendingCloseMessage || sendClosed.state())
			return;
		sendingCloseMessage = true;
		synchronized(sentPacketLock) {
			if(sentPacket != null) {
				Message cm = p.getCloseMessage();
				if(cm != null) {
					try {
						// 10 minute timeout
						sentPacket =
							peerHandler.getPacket(
								p,
								identity,
								cm,
								600 * 1000,
								true);
						// Does not matter when they get the close message
						if(logDEBUG)
							Core.logger.log(
								this,
								"Sending "
									+ sentPacket
									+ " ("
									+ cm
									+ ") on "
									+ this
									+ " (close())",
											Logger.DEBUG);
					} catch (IOException e) {
						logDEBUG("Caught "+e+" in getPacket in close()");
						sentPacket = null;
					}
				}
				sendingCloseMessage = false;
			}
		}
		innerSendPacket(sentPacket.priority());
		if(logDEBUG)
			logDEBUG("Sent close packet");
    }
	
    /**  Closes the connection utterly and finally. */
    public void terminate() {
		// 		if(unregisteredFromOCM) decOpenButNotOnOCM();
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if (logDEBUG)
			logDEBUG("Terminating", true);
		// Unconditionally remove the connection handler
		// from the OCM
		// This should be the ONLY place we call it from
		// To avoid leaking conns, we keep everything open in the OCM
		removeFromOCM();
		if (logDEBUG)
			logDEBUG("Removed from OCM in terminate()");
		//        synchronized(temp) {
		//               temp.println();
		//               temp.println("--------------------------------------------------");
		//              (new Exception("Entered ConnectionHandler.teminate()")).printStackTrace(temp);
		//        }
		boolean wasClosed = alreadyClosedLink;
		ByteBuffer a = accumulator;
		if(a != null) {
			synchronized(a) {
				alreadyClosedLink = true;
				a.notifyAll();
				accumulator = null;
				_accumulator = null;
			}
			synchronized(bufferPool) {
				bufferPool.addLast(a);
			}
		}
		try {
			finalized.change();
		} catch (IrreversibleException e) {
			if (logDEBUG)
				logDEBUG("Already started terminating");
			return;
			// terminate called twice
		}
		// Try to open another conn
		// It is IMPORTANT that this gets called AFTER the finalized.change() (and not at all if we have already finalized)
		// The finalized.change() will serialize terminate()'s
		if(identity != null) {
			if (logDEBUG)
				logDEBUG("Scheduling connection opener");
			node.rescheduleConnectionOpener();
			if (logDEBUG)
				logDEBUG("Scheduled connection opener");
		} else {
			if (logDEBUG)
				logDEBUG("Null identity");
		}
		// Log stats
		if(initRegisteredInOCM) {
			// If it's actually started
			long now = System.currentTimeMillis();
			long connectionLifetime = now - startTime;
			if(logDEBUG)
				logDEBUG(
					"Logging stats: connectionLifeTime="
						+ connectionLifetime
						+ ", messages="
						+ messagesReceived);
			Core.diagnostics.occurrenceContinuous(
				"connectionLifeTime",
				connectionLifetime);
			Core.diagnostics.occurrenceContinuous(
				"connectionMessages",
				messagesReceived);
		}
		// Need to release sendLock first...
		if (logDEBUG)
			logDEBUG("notified CHOS in terminate()");
			
		if (logDEBUG)
			logDEBUG("notified current sender in terminate()");
		//terminatedInstances++;
		if (!sendClosed.state())
			// ???  Is there a codepath that leaves sendClose.state()
			// ???  true without removing the connection?
			//removeFromOCM();
			synchronized (sendLock) {
				sendClosed.change(true);
				sendLock.notifyAll();
			}
		if (logDEBUG)
			logDEBUG("changed and notified sendClosed/sendLock in terminate()");
		if (!receiveClosed.state())
			synchronized (receiveLock) {
				receiveClosed.change(true);
				receiveLock.notify();
			}
		if (logDEBUG)
			logDEBUG("changed and notified receiveClosed/receiveLock in terminate()");
		jobDone(0, false); //call jobDone for the entire CH --zab
		if(!wasClosed) {
			try {
				Link l = link;
				if (l != null)
					l.close();
			} catch (IOException e) {
				Core.logger.log(
					this,
					"Exception closing link for " + this +": " + e,
					e,
					Logger.NORMAL);
			}
		}
		if (logDEBUG)
			logDEBUG("ConnectionHandler closed link", true);
		
		if(currentInputStream != null) {
			try {
				currentInputStream.close();
			} catch (IOException e) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"IOException caught closing stream: " + e,
						e,
						Logger.DEBUG);
			}
		}
		if (logDEBUG)
			logDEBUG("Closed CHIS in terminate()");
		link = null;
		conn = null;
		trailerSendState.closedCH();
		trailerSendState.reset();
		if(peerHandler != null) {
			peerHandler.unregisterConnectionHandler(this);
		} else {
			Core.logger.log(
				this,
				"Terminating " + this +" but not registered on a PeerHandler",
				Logger.MINOR);
			// could be just never got initted
		}
		if (logDEBUG)
			logDEBUG("terminate()d");
	}
	
    /**
     *  Checks whether the connection is alive and can send messages.
     *
     *@return    The open value
     */
    public final boolean isOpen() {
        return !sendClosed.state();
    }
	
	public ConnectionDataTransferAccounter getTransferAccounter() {
		return transferAccounter;
	}

    /**
     *  Returns the number milliseconds since this connection was active.
     *
     *@return    Description of the Return Value
     */
    public final long idleTime() {
        //return (sending.count() > 0 || receiving.count() > 0 ?
		return (
			trailerSendState.isSendingTrailer()
				|| receivingCount > 0
					? 0
					: System.currentTimeMillis() - lastActiveTime);
    }
	
	public final NodeReference targetReference() {
		if (peerHandler == null)
			return null;
		return peerHandler.getReference();
	}
	
	public final void setTargetReference(NodeReference ref) {
		peerHandler.updateReference(ref);
	}
	
    /**
     *@return    whether the connection is currently sending something
     */
	public final boolean blockedSendingTrailer() {
		return trailerSendState.isSendingTrailer();
    }
	
    /**
     *@return    whether the connection is current receiving something
     */
    public final boolean receiving() {
        return currentInputStream != null;
    }
	
	public final Peer getPeer() {
		return peer;
	}
	
	public final PeerHandler getPeerHandler() {
		return peerHandler;
	}
	
    /**
     *@return    identity of the Peer on the other end of the connection
     */
    public final Identity peerIdentity() {
        return identity;
    }

    /**
     *@return    the Transport used for this connection
     */
    public final Transport transport() {
		return peer.getAddress().getTransport();
    }

    public final LinkManager sessionType() {
        return peer.getLinkManager();
    }
	
	public final Link getLink() {
		return link;
	}
	
    public final Address peerAddress() {
        return peer.getAddress();
    }

    public final Presentation presentationType() {
        return p;
    }

    /**
     *  The time since this was started.
     */
    public final long runTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     *  The number of messages sent and received over this connection.
     */
    public final long messages() {
        return messagesReceived;
    }

	protected void finalize() throws Throwable {
	    
        if (!finalized.state()) {
			Core.logger.log(
				this,
				"I (" + this +") wasn't terminated properly! Doing it now..",
                            identity == null ? Logger.MINOR : Logger.ERROR);
            terminate();
		}
		
		profilingHelperTool.decInstanceCount();
    }
	
    //=========================================================================
    // the rest is inner classes for streams that handle the trailing
    // fields when sending/receiving messages
    //=========================================================================

	/**
     *  An InputStream that allows reading of a limited number of bytes, and
     *  unlocks the receiving when that many bytes are read or it is closed.
     *
     */
    private final class ReceiveInputStream extends DiscontinueInputStream {

        private final  long     toRead;
        private        long     read    = 0;
        private        boolean  done    = false;
        private        String  messageType;

        private long messageId = -1;
		
        /**
         *  Constructor for the ReceiveInputStream object
         *
         *@param  in      Description of the Parameter
         *@param  toRead  Description of the Parameter
         */
		public ReceiveInputStream(
			InputStream in,
			long toRead,
                                  String messageType) {
            super(in);
            this.toRead = toRead;
            transferAccounter.incReceiveQueue(toRead);
            this.messageType = messageType;

			profilingHelperTool.incRISCount();
        }

		public int read() throws IOException {
			if (logDEBUG)
				logDEBUG("trying RIS.read()");
            try {
                if (read >= toRead)
                    return -1;
                int  i  = in.read();
				if (logDEBUG)
					logDEBUG("read from RIS.read(): " + i);
                if (i > -1)
                    read++;
                if (read == toRead)
                    done();
				transferAccounter.decReceiveQueue(i);
                return i;
			} catch (IOException e) {
                close();
                throw (IOException) e.fillInStackTrace();
            }
        }
        
		public int read(byte[] b, int off, int length) throws IOException {
			if (logDEBUG)
				logDEBUG(
					"trying RIS.read(byte[]," + off + "," + length + ")",
					true);
            try {
                if (read >= toRead) {
					Core.logger.log(
						this,
						"returning -1 from RIS.read(byte[],int,int) because read >=toRead (for "
							+ ConnectionHandler.this
							+ " read is "
							+ read
							+ " toRead is "
							+ toRead
							+ ")",
						Logger.NORMAL);
                    return -1;
				} else if (read + length > toRead)
                    length = (int) (toRead - read);
				
				// System.err.println("CONN STREAM READING SUPER IN: " + in.toString());
                int  i  = in.read(b, off, length);
				if (logDEBUG)
					logDEBUG(
						"read in RIS.read(byte[],int,int) " + i + " from " + in,
						true);
                if (i > -1)
                    read += i;
                if (read == toRead)
                    done();
				if(logDEBUG) 
					logDEBUG(
						"read(byte[],"
							+ off
							+ ","
							+ length
							+ ") - i = "
							+ i
							+ ", read="
							+ read
							+ ",toRead="
							+ toRead,
						true);
				transferAccounter.decReceiveQueue(i);
                return i;
            } catch (IOException e) {
				Core.logger.log(
					this,
					"Got IOException: "
						+ e
						+ " in RIS.read(,"
						+ off
						+ ","
						+ length
						+ ") for "
						+ this
						+ " ("
						+ ConnectionHandler.this
						+ ")",
					e,
					Logger.NORMAL);
                close();
				throw new IOException("Got IOException: "+e);
            }
        }
		
		public void close() throws IOException {
            //if (read < toRead) {
            //    // not good, bad data present on Connection
            //    Core.logger.log(ConnectionHandler.this, "Close after " + read
            //                    + " of " + toRead + " bytes", Logger.MINOR);
            //}
			if (logDEBUG)
				logDEBUG("Closing", true);
            done();
            //discontinue();
            //super.close();
	    
        }

		public final void discontinue() throws IOException {
			if (logDEBUG)
				logDEBUG("Discontinuing");
			//Core.logger.log(this,"discontinuing RIS",Logger.ERROR);
            read = toRead;
            done();
        }

        private void done() throws IOException {
			if (logDEBUG)
				logDEBUG("in RIS.done()", true);
			currentInputStream = null;
            if (!done) {
                synchronized (receiveLock) {
                    done = true;
                    lastActiveTime = System.currentTimeMillis();
                    //receiving.decCount();
                    if (read < toRead) {
						Core.logger.log(
							this,
							"Closing because of premature done(): " + this,
							Logger.MINOR);
                        // can't read any more
                        receiveClosed.change(true);
						closeNow = true;
                    }
                    // messages on this conn.
                    --receivingCount;
                    receiveLock.notify();
                    // wake read thread
                }
                if (read < toRead)
					Core.logger.log(
						ConnectionHandler.this,
						"Close after "
							+ read
							+ " of "
							+ toRead
							+ " bytes: "
							+ this
							+ " for "
							+ ConnectionHandler.this,
									Logger.MINOR);
				else {
					if (logDEBUG)
						logDEBUG(
							"Trailing field fully received: "
								+ read
								+ " of "
								+ toRead);
					//note to toad: this is assuming we only use it on CHIS/NIOIS
					//where close == discontinue
					//in.close();
					if(in instanceof DiscontinueInputStream)
						((DiscontinueInputStream)in).discontinue();
				}
            }
        }
		
        public String toString() {
            return "ConnectionHandler$ReceiveInputStream allocated for " 
				+ messageType
				+ ", "
				+ read
				+ " of "
				+ toRead
				+ " bytes done ("
				+ ConnectionHandler.this
				+ ")";
        }
		
		protected void finalize() throws Throwable {
			if (logDEBUG)
				logDEBUG("finalizing");
            if (!done) {
				Core.logger.log(
					ConnectionHandler.this,
					"I was finalized without being properly deallocated: "
						+ this
						+ " ("
						+ ConnectionHandler.this
						+ ")",
					Logger.ERROR);

				if (messageId != -1) {
					// yes I know it can be -1, but this is debug
					System.err.println(
						"This query failed to deallocate " + this);
					t.getMessageHandler().printChainInfo(messageId, System.err);
                }
                
                done();
            }

			profilingHelperTool.decRISCount();
            super.finalize();
        }
    }
	
    /**
     *  An OutputStream that only allows writing of so many bytes, and that
     *  releases the sendLock that many bytes are written or it closed.
     */
//     private final class SendOutputStream extends FilterOutputStream {

//         private        long     written  = 0;
//         private final  long     toWrite;
//         private        boolean  done     = false;
	
//         public SendOutputStream(OutputStream out, long toWrite) {
//             super(out);
//             this.toWrite = toWrite;
// 			//profiling
// 			//WARNING:remove before release
// 			synchronized(profLockSOS) {
// 				SOSinstances++;
// 			}
//         }

//         public void write(int i)
//             throws IOException {
//             try {
//                 if (written >= toWrite)
//                     throw new IOException("Overwriting: " + written +
//                                           " + 1 > " + toWrite);
//                 out.write(i);
//                 written++;
// 				decSendQueue(1);
//                 if (toWrite == written)
//                     done();
//             }
//             catch (IOException e) {
//                 close();
//                 throw (IOException) e.fillInStackTrace();
//             }
//         }

//         public void write(byte[] b, int off, int length)
//             throws IOException {
//             try {
//                 if (written + length > toWrite)
//                     throw new IOException("Overwriting: " + written + " + " + length
//                                           + " > " + toWrite);
//                 out.write(b, off, length);
//                 written += length;
// 				decSendQueue(length);
//                 if (written == toWrite)
//                     done();
//             }
//             catch (IOException e) {
//                 close();
//                 throw (IOException) e.fillInStackTrace();
//             }
//         }

//         public void close()
//             throws IOException {
//             if (!done && written < toWrite)
//                 Core.logger.log(ConnectionHandler.this,
//                                 "Close after " + written + " of " + toWrite + " bytes on: " + this,
//                                 Logger.MINOR);

//             decSendQueue(toWrite - written);
            
//             written = toWrite;
//             done();
//         }

//         public void done() {
//             if (!done) {
//                 done = true;
//                 synchronized (sendLock) {
                    
//                     --sendingCount;
					
//                     //reset the trailing flag, since we can have only one trailer at a time
// 					if(!trailingPresent) {
// 						Exception e = new Exception("duh");
// 						Core.logger.log(this, "Called SOS.done() with "+
// 										"trailingPresent == false!!", e,
// 										Logger.ERROR);
// 					}
// 					trailingPresent=false;
// 					currentSOS = null;
					
//                     if (receiveClosed.state() && receivingCount == 0
//                         && sendClosed.state() && sendingCount == 0) {
//                         terminate();
//                     }

// 					logDEBUG("Message and trailing field sent.");

//                     Core.randSource.acceptTimerEntropy(sendTimer);

//                     lastActiveTime = System.currentTimeMillis();
//                     sendLock.notify();
//                     // wake a possible waiting thread.
//                 }
//             }
//         }

//         /*
//          *  Removed because it prevents early GCing of these objects. - someone
//          *  Restored because it prevents an eternal lock on sending, and I'm
//          *  pretty sure we were seeing that. - oskar
//          */
//         protected void finalize() throws Throwable {
//             if (!done) {
//                 Core.logger.log( ConnectionHandler.this,
//                                  "I was finalized without being " + 
//                                  "properly deallocated: "+this,
//                                  Logger.ERROR);
//                 done();
//             }
// 			synchronized(profLockSOS) {
// 				SOSinstances--;
// 			}
//             super.finalize();
//         }
//     }
	
	public String toString() {
		return super.toString()
			+ " for "
			+ conn
			+ ","
			+ link
			+ ", sending "
			+ sentPacket
			+ ":"
			+ trailerSendState.toString()
			+ " for "
			+ peerHandler;
	}
	
	/* (non-Javadoc)
	 * @see freenet.BaseConnectionHandler#getPresentation()
	 */
	public Presentation getPresentation() {
		return p;
	}
	
	/* (non-Javadoc)
	 * @see freenet.BaseConnectionHandler#isOutbound()
	 */
	public boolean isOutbound() {
		return outbound;
	}
	
	/* (non-Javadoc)
	 * @see freenet.BaseConnectionHandler#messagesReceived()
	 */
	public int messagesReceived() {
		return messagesReceived;
	}
	
	/* (non-Javadoc)
	 * @see freenet.BaseConnectionHandler#messagesSent()
	 */
	public int messagesSent() {
		return messagesSent;
	}

    public AddressDetectedPacketMessage getDetectMessage() {
        return null;
    }

    Inet4Address detectedAddress = null;
    
    public void setDetectedAddress(Inet4Address ip4addr) {
        if(ip4addr != null)
            this.detectedAddress = ip4addr;
    }

    public Inet4Address getDetectedAddress() {
        return detectedAddress;
    }

    public boolean inFlightMessagesWithMRI() {
        PeerPacket packet = sentPacket;
        if(packet == null) return false;
        return packet.messagesWithMRI();
    }
}
