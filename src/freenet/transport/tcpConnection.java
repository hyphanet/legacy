package freenet.transport;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Hashtable;
import java.util.LinkedList;

import freenet.Address;
import freenet.BadAddressException;
import freenet.ConnectFailedException;
import freenet.Connection;
import freenet.Core;
import freenet.ListeningAddress;
import freenet.diagnostics.Diagnostics;
import freenet.diagnostics.ExternalCounting;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.rt.ValueConsumer;
import freenet.support.BooleanCallback;
import freenet.support.IntervalledSum;
import freenet.support.Logger;
import freenet.support.io.Bandwidth;
import freenet.support.io.NIOInputStream;
import freenet.support.io.NIOOutputStream;
import freenet.support.io.NullInputStream;
import freenet.support.io.SocketExceptionOutputStream;

// Main imported for Main.timerGranularity... fixme?

public final class tcpConnection extends Connection {

    static final MaximumMinuteSumTracker maxSeenBytesPerMinuteSeenTracker = new MaximumMinuteSumTracker(); 
    
    public static long maxSeenIncomingBytesPerMinute() {
    	return maxSeenBytesPerMinuteSeenTracker.max;
    }
    
    /**
     * Tracks maximum 1 minute value seen
     * @author Iakin
     */
    private static class MaximumMinuteSumTracker implements ValueConsumer
	{
    	private final IntervalledSum currentMinuteBytes = new IntervalledSum(60000);
    	private int max = 0;
		public void report(double d) {
			currentMinuteBytes.report(d);
			synchronized(this){
				max = Math.max(max,(int)currentMinuteBytes.currentSum());
			}
		}
		public void report(long d) {
			currentMinuteBytes.report(d);
			synchronized(this){
				max = Math.max(max,(int)currentMinuteBytes.currentSum());
			}
		}
	}
    
	private final Socket sock;
	private final InetAddress peerAddr; //The address of the peer that we are connected against 
	private InputStream in;
	private OutputStream out;
	private final ByteBuffer accumulator;
	private NIOInputStream nioin;
	private NIOOutputStream nioout;
	private final Object closeLock= new Object();
	private boolean closed = false;
	//public Exception whenClosed;
	private boolean reallyClosed = false;
	private boolean shouldThrottle = false;
	private boolean shouldThrottleNow = false;
	private boolean instanceCounted = false;
	// if the constructor throws, WE STILL GET FINALIZED!
	private static boolean logDEBUG;
	// We do not throttle in FnpLink because it slows down negotiations
	// drastically
	// We do not directly throttle, ever, because of nio.
	// Hence throttling is implemented in *SelectorLoop, not here
	// We should have a Bandwidth associated with the connection to support
	// multiple independant throttles

	//profiling
	//WARNING:remove before release
	public static volatile int instances = 0;
	public static volatile int openInstances = 0;
	public static volatile long createdInstances = 0;
	public static volatile long closedInstances = 0;
	public static volatile long finalizedInstances = 0;
	private static final Object profLock = new Object();
	private static ThrottledAsyncTCPReadManager rsl;
	private static ThrottledAsyncTCPWriteManager wsl;
	private static Bandwidth ibw = null;
	private static Bandwidth obw = null;
	
	//Set to false for now since the code doesn't safely guarantee that everyone forgets the accumulator
	//and we _dont_ want multiple connections sharing a buffer 
	private static final boolean POOL_BUFFERS = false; 
	private static final LinkedList bufferPool = new LinkedList();
	private static boolean USE_DIRECT_BUFFERS = true;
	public static int BUFFER_SIZE = 16384;

	private static final int streamBufferSize() {
		return 2048; // this is only used for negotiations and fproxy! 
	}

	public static int bufferPoolSize() {
		synchronized (bufferPool) {
			return bufferPool.size();
		}
	}

	private static final Hashtable socketConnectionMap = new Hashtable();

	static synchronized public void setInputBandwidth(Bandwidth bw) {
		ibw = bw;
		if (rsl != null)
			rsl.setBandwidth(ibw);
	}

	static synchronized public void setOutputBandwidth(Bandwidth bw) {
		obw = bw;
		if (wsl != null)
			wsl.setBandwidth(obw);
	}

	static synchronized public void startSelectorLoops(Logger logger,
	        Diagnostics d, BooleanCallback cbLoad, boolean logInputBytes, boolean logOutputBytes) {
		// Start NIO loops
		try {
			if (rsl == null) {
			    ExternalCounting ec = d.getExternalCountingVariable("inputBytes");
			    ec.relayReportsTo(maxSeenBytesPerMinuteSeenTracker,Diagnostics.COUNT_CHANGE);
			    ReadSelectorLoop rsl;
			    if (ibw != null)
					rsl = new ReadSelectorLoop(logger, 
					        d.getExternalContinuousVariable("closePairLifetime"),
					        ec,
					        d.getExternalCountingVariable("readinessSelectionScrewed"),
					        d.getExternalCountingVariable("connectionResetByPeer"),
					        cbLoad,
					        logInputBytes,
					        ibw, Main.getTimerGranularity(),
					        Core.getRandSource());
				else
					rsl = new ReadSelectorLoop(logger, 
					        d.getExternalContinuousVariable("closePairLifetime"),
					        ec,
					        d.getExternalCountingVariable("readinessSelectionScrewed"),
					        d.getExternalCountingVariable("connectionResetByPeer"),
					        cbLoad,
					        logInputBytes,
					        Core.getRandSource());
				tcpConnection.rsl = rsl;	        
				Thread rslThread = new Thread(rsl, "Network reading thread");
				rslThread.setDaemon(true);
				//rslThread.setPriority(Thread.MAX_PRIORITY);
				rslThread.start(); // inactive until given registrations
			}
			if (wsl == null) {
				WriteSelectorLoop wsl;
				if (obw != null)
					wsl = new WriteSelectorLoop(logger,
					        d.getExternalContinuousVariable("closePairLifetime"),
					        d.getExternalCountingVariable("outputBytes"),
					        d.getExternalCountingVariable("outputBytesVeryHigh"),
					        d.getExternalCountingVariable("outputBytesHigh"),
					        d.getExternalCountingVariable("outputBytesNormal"),
					        d.getExternalCountingVariable("outputBytesLow"),
					        logOutputBytes,
					        obw, Main.getTimerGranularity(),
					        Core.getRandSource());
				else
					wsl = new WriteSelectorLoop(logger,
					        d.getExternalContinuousVariable("closePairLifetime"),
					        d.getExternalCountingVariable("outputBytes"),
					        d.getExternalCountingVariable("outputBytesVeryHigh"),
					        d.getExternalCountingVariable("outputBytesHigh"),
					        d.getExternalCountingVariable("outputBytesNormal"),
					        d.getExternalCountingVariable("outputBytesLow"),
					        logOutputBytes,
					        Core.getRandSource());
				tcpConnection.wsl = wsl;
				Thread wslThread = new Thread(wsl, "Network writing thread");
				wslThread.setDaemon(true);
				//wslThread.setPriority(Thread.MAX_PRIORITY);
				wslThread.start(); // inactive until given jobs
			}
		} catch (Throwable t) {
			System.err.println(
				"Could not initialize network I/O system! Exiting");
			t.printStackTrace(System.err);
			System.exit(1);
		}
	}

	static public ThrottledAsyncTCPReadManager getRSL() {
		return rsl;
	}

	static public ThrottledAsyncTCPWriteManager getWSL() {
		return wsl;
	}

	public boolean shouldThrottle() {
		return shouldThrottleNow;
	}

	public boolean countAsThrottled() {
		return shouldThrottle;
	}

	public void enableThrottle() {
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"Enabling throttle for " + this,
				new Exception("debug"),
				Logger.DEBUG);
		shouldThrottleNow = shouldThrottle;
	}

	public Socket getSocket() throws IOException {
		if (closed)
			throw new IOException("already closed " + this);
		else
			return sock;
	}

	/**
	 * @return whether close() has been called. IMPORTANT NOTE: This does not
	 *         necessarily mean we have completed a full blocking close()!
	 */
	public boolean isClosed() {
		return closed;
	}

	public boolean isInClosed() {
		NIOInputStream is = nioin;
		return (is == null) || (is.isClosed());
	}

	public boolean isOutClosed() {
		NIOOutputStream os = nioout;
		return (os == null) || os.isClosed();
	}

	static public tcpConnection getConnectionForSocket(Socket s) {
		return (tcpConnection) socketConnectionMap.get(s);
	}
	static public int getSocketToConnectionMapSize() {
		return socketConnectionMap.size();
	}

	public static boolean logBytes = false;
	
	private final ByteBuffer getAccumulator() {
		if (POOL_BUFFERS) {
			synchronized (bufferPool) {
				if (!bufferPool.isEmpty()) {
					ByteBuffer retval = (ByteBuffer) bufferPool.removeFirst();
					if (logDEBUG)
						Core.logger.log(this, "Reused buffer from pool: " + retval, Logger.DEBUG);
					return retval;
				}
			}
		}
		ByteBuffer retval = USE_DIRECT_BUFFERS ? ByteBuffer.allocateDirect(BUFFER_SIZE) : ByteBuffer.allocate(BUFFER_SIZE);
		if (logDEBUG)
			Core.logger.log(this, "Allocated new buffer: " + retval, Logger.DEBUG);
		return retval;
	}

	/**
	 * Used to create an outbound connection.
	 */	
	public static tcpConnection connect(tcpTransport t,
		tcpAddress addr,
		boolean dontThrottle,
		boolean throttleAll) throws ConnectFailedException
	{
		Socket sock=null;
		long time = System.currentTimeMillis();
		try {
			sock = t.getSocketFactory().createSocket(
					addr.getHost(),
					addr.getPort());
			if (wsl != null)
				wsl.putBandwidth(80);
			if (rsl != null)
				rsl.putBandwidth(80);
			// FIXME: how much does a TCP handshake really cost?
			if (sock == null)
				throw new IOException("could not create socket");
			//peerAddr = sock.getInetAddress();

			Core.diagnostics.occurrenceContinuous(
				"socketTime",
				System.currentTimeMillis() - time);
		} catch (IOException e) {
			try {
				if(sock != null)
					sock.close();
			} catch (IOException ex) {Core.logger.log(tcpConnection.class,"Caught IOException while closing socket",e,Logger.MINOR);}
			String desc = e.getMessage();
			if (desc == null)
				desc = "(null)";
			throw new ConnectFailedException(addr, desc);
		}
		tcpConnection conn;
		try{
			conn = tcpConnection.wrap(t,sock,dontThrottle,throttleAll);
		}catch(IOException e){
			String desc = e.getMessage();
			if (desc == null)
				desc = "(null)";
			throw new ConnectFailedException(addr, desc);
		}	 
		
		return conn;
	}
/*
	private tcpConnection(
		tcpTransport t,
		tcpAddress addr,
		boolean dontThrottle,
		boolean throttleAll)
		throws IOException, ConnectFailedException {
		this(t);
		
		if (logDEBUG)
			Core.logger.log(
				this,
				"tcpConnection (outbound)",
				new Exception("debug"),
				Logger.DEBUG);
		long time = System.currentTimeMillis();
		try {
			this.sock = t.getSocketFactory().createSocket(
					addr.getHost(),
					addr.getPort());
			if (wsl != null)
				wsl.putBandwidth(80);
			if (rsl != null)
				rsl.putBandwidth(80);
			// FIXME: how much does a TCP handshake really cost?
			if (sock == null)
				throw new IOException("could not create socket");
			//peerAddr = sock.getInetAddress();

			Core.diagnostics.occurrenceContinuous(
				"socketTime",
				System.currentTimeMillis() - time);
		} catch (IOException e) {
			closed = true;
			try {
				if(sock != null)
					sock.close();
			} catch (IOException ex) {Core.logger.log(this,"Caught IOException while closing socket",e,Logger.MINOR);};
			String desc = e.getMessage();
			if (desc == null)
				desc = "(null)";
			throw new ConnectFailedException(addr, desc);
		} catch (RuntimeException e) {
			closed = true;
			try {
				if (sock != null)
					sock.close();
			} catch (IOException ex) {Core.logger.log(this,"Caught IOException while closing socket",e,Logger.MINOR);};
			throw e;
		}
		disableNaglesAlgorithm(this.sock);
		// NIO related stuff**
		setupNIOStuffAndIOStreams();
		shouldThrottle = resolveShouldThrottle(sock,dontThrottle,throttleAll);
		
		socketConnectionMap.put(sock, this);

		//profiling
		//WARNING:remove before release
		synchronized (profLock) {
			openInstances++;
			createdInstances++;
			instanceCounted = true;
			logInstances("outbound");
		}
		
		if (logDEBUG)
			Core.logger.log(this, "Created outbound tcpConnection " + this + " (" + t + "," + addr + "," + dontThrottle + "," + throttleAll + ")", new Exception("debug"), Logger.DEBUG);
		
		try {
			rsl.register(sock.getChannel(), nioin);// Register AFTER on connection map
		} catch (Throwable e) {
			Core.logger.log(
				this,
				"WTF? Failed final construction: " + this +": " + e,
				e,
				Logger.ERROR);
		} // because of below
	}
	*/

	private static void disableNaglesAlgorithm(Socket sock) throws SocketException {
		if (!sock.getTcpNoDelay()) {
			Core.logger.log(tcpConnection.class, "Disabling Nagle's Algorithm!", Logger.DEBUG);
			sock.setTcpNoDelay(true);
		}
	}

	protected final void logInstances(String s) {
		synchronized (profLock) {
			if (Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(
					this,
					s
						+ " ("
						+ this
						+ ") instances: "
						+ instances
						+ ", openInstances: "
						+ openInstances
						+ ", created: "
						+ createdInstances
						+ ", closed: "
						+ closedInstances
						+ ", finalized: "
						+ finalizedInstances
						+ ", table: "
						+ socketConnectionMap.size(),
					openInstances > instances ? Logger.NORMAL : Logger.DEBUG);
		}
	}

	/**
	 * Used to start wrapping an open socket.
	 */	
	public static tcpConnection wrap(
		tcpTransport t,
		Socket sock,
		boolean dontThrottle,
		boolean throttleAll)
		throws IOException {
		if (sock == null)
			throw new IllegalArgumentException("sock null");
		return new tcpConnection(t,sock,dontThrottle,throttleAll);
	}

	private tcpConnection(
		tcpTransport t,
		Socket sock,
		boolean dontThrottle,
		boolean throttleAll)
		throws IOException {
		super(t);
		this.sock = sock;
		this.peerAddr = sock.getInetAddress();
		
		//Instance-counting for debugging purposes, TODO:remove before release
		synchronized (profLock) {
			instances++;
		}
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, tcpConnection.class);
		
		//Retrieve and accumulator for out usage
		accumulator = getAccumulator();
		accumulator.limit(0).position(0);
		
		if (logDEBUG)
			Core.logger.log(this, "Creating tcpConnection from socket", Logger.DEBUG);
			
		// We do our own buffering, no need to slow it down further
		disableNaglesAlgorithm(sock);

		/** NIO related stuff** */
		sock.getChannel().configureBlocking(false);
		nioout = new NIOOutputStream(sock.getChannel(), this,wsl);
		in = nioin = new NIOInputStream(accumulator, sock.getChannel(), this,rsl);
		//TODO: Clarify comment below?
		// Buffering InputStreams on nio is a major issue due to
		// handover issues. Leave it alone for now, maybe it's fast
		// enough already.
		// Main reason for buffering is to deal with overhead
		// of 1 byte writes
		// With multiplexing this won't happen anything like so much
		// So in theory we could get rid of this... FIXME
		out = new BufferedOutputStream(nioout, streamBufferSize());
		
		//Are we supposed to throttle given the supplied params
		shouldThrottle = resolveShouldThrottle(sock,dontThrottle,throttleAll);

		//Allow owners of a socket some mechanism to retrieve a matching 'this'
		socketConnectionMap.put(sock, this);
		
		//More profiling, TODO:remove before release
		synchronized (profLock) {
			openInstances++;
			createdInstances++;
			instanceCounted = true;
			logInstances("inbound");
		}

		if (logDEBUG)
			Core.logger.log(this, "Accepted tcpConnection " + this + " (" + t + "," + sock + /*"," + designator +*/ "," + dontThrottle + "," + throttleAll + ") - throttle = " + shouldThrottle, new Exception("debug"), Logger.DEBUG);
		
		//Register ourself with the read selector..
		//After this inbound data will start trickling in
		try {
			rsl.register(sock.getChannel(), nioin);// Register AFTER on connection map
		} catch (Throwable e) {
			Core.logger.log(this, "WTF? Failed construction: " + this + ": " + e, e, Logger.ERROR);
		}
		setSoTimeout(600 * 1000); // 10 minutes initially
	}

	//Returns wheter or not, given the supplied parameters, the connection should be throttled
	private static boolean resolveShouldThrottle(Socket sock, boolean dontThrottle, boolean throttleAll) {
		if (dontThrottle) {
			if (logDEBUG)
				Core.logger.log(tcpConnection.class, "Not throttling connection", Logger.DEBUG);
			return false;
		}
		InetAddress addr = sock.getInetAddress();
		if (throttleAll || tcpTransport.checkAddress(addr)) {
			if (logDEBUG)
				Core.logger.log(tcpConnection.class, "Throttling connection", Logger.DEBUG);
			return true;
		}
		return false;
	}

	/**
	 * @return the input buffer, this will be set up for reading i.e. position = 0,
	 *         limit = end of bytes available ("flipped")
	 */
	public ByteBuffer getInputBuffer() {
		//Return the buffer even if we are marked as 'closed'.
		//As of now 'closed' doesn't neccesarily mean that
		//more data won't arrive over the network since we are really only
		//queueClosed.. however.. when we are 'reallyClosed' we definitely are
		//off the selector loops.. after that we wont deliver a buffer to anyone.
		
		//Temporary disabled close-check.. seems like
		//many CH:s are recieving data even after they are
		//reallyClosed and thereby encountering NPEs
		//TODO: re-enable
		//if (reallyClosed)
		//	return null;
		//else
			return accumulator;
	}


	public final void close() {
		close(false);
	}

	public final void close(boolean fromCloseThread) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);

	
		if (logDEBUG) {
			Exception closeException = new Exception("debug");
			Core.logger.log(this, "Closing(" + fromCloseThread + ") tcpConnection " + this, closeException, Logger.DEBUG);
		}
		if (fromCloseThread) {
			closed = true;
			//whenClosed = new Exception(); 
			synchronized (closeLock) {
				if (!reallyClosed) {
					reallyClosed = true;
					if (logDEBUG)
						Core.logger.log(
							this,
							"reallyClosing " + this,
							Logger.DEBUG);
					try {

						if (sock != null) {
							sock.getChannel().close();
							sock.close();
							wsl.onClosed(sock.getChannel());
						}
						// It will not change to null, but if we had an
						// incomplete initialization and are being called from
						// finalize(), it might BE null.
					} catch (IOException e) {
						// It may have been closed remotely in which case
						// sock.close will throw an exception. We really don't
						// care though.
						if (logDEBUG)
							Core.logger.log(
								this,
								"Caught IOException " + e + " closing " + this,
								e,
								Logger.DEBUG);
					} catch (Throwable t) {
						System.err.println(t);
						t.printStackTrace();
						Core.logger.log(
							this,
							"Caught " + t + " closing " + this,
							t,
							Logger.ERROR);
						t = t.getCause();
						if (t != null) {
							Core.logger.log(
								this,
								"Cause: " + t,
								t,
								Logger.ERROR);
							t.printStackTrace();
						}
					} finally {
						if (instanceCounted) {
							synchronized (profLock) {
								openInstances--;
								closedInstances++;
								logInstances("closing");
							}
						}
					}
				}
				// Remove from map after really closing it
				if (sock != null) {
					socketConnectionMap.remove(sock);
				}
			}
			NIOInputStream ni = nioin;
			if (ni != null)
				ni.closed();
			nioin = null;
			NIOOutputStream no = nioout;
			if (no != null)
				no.closed();
			nioout = null;
		} else {
			if (!closed)
				rsl.queueClose(sock.getChannel(),null);
			closed = true;
			//whenClosed = new Exception(); 
		}
		try {
			InputStream i = in;
			if (i != null)
				i.close();
		} catch (Throwable e) {
		}
		in = new NullInputStream();

		try {
			OutputStream o = out;
			if (o != null)
				o.close();
		} catch (Throwable e) {
		}
		out =
			new SocketExceptionOutputStream(
				"Connection already closed " + this);
	}

	private boolean finalized = false;

	protected void finalize() {
		if (finalized)
			return;
		finalized = true;
		logInstances("Finalizing");
		if (!closed)
			Core.logger.log(
				this,
				"finalized without being closed!" + this,
				Logger.NORMAL);
		// Accumulator will not be reused after closure
		if (POOL_BUFFERS) {
			if (accumulator != null) {
				synchronized (bufferPool) {
					bufferPool.addLast(accumulator);
				}
			} else {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Not repooling accumulator because is null: " + this,
						Logger.DEBUG);
			}
		}
		try {
			close(true);
		} catch (Throwable t) {
			Core.logger.log(
				this,
				"Caught " + t + " closing " + this +" in finalize()",
				t,
				Logger.NORMAL);
		}
		if (!reallyClosed)
			Core.logger.log(
				this,
				"finalized without being reallyClosed!: " + this,
				Logger.NORMAL);
		//profiling
		//WARNING:remove before release
		synchronized (profLock) {
			instances--;
			finalizedInstances++;
			logInstances("finalized");
		}
	}

	public final InputStream getIn() {
		if (closed)
			return null;
		else
			return in;
	}

	public final NIOInputStream getUnderlyingIn() {
		if (closed)
			return null;
		else
			return nioin;
	}

	public final OutputStream getOut() {
		return out;
	}

	public final void setSoTimeout(int timeout) throws IOException {
		if (!closed) {
			sock.setSoTimeout(timeout);
			nioin.setTimeout(timeout);
		} else
			throw new IOException("Already closed " + this);
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"Set SO_TIMEOUT to " + timeout + " for " + this,
				Logger.DEBUG);
	}

	public final int getSoTimeout() throws IOException {
		if (!closed)
			return sock.getSoTimeout();
		else
			throw new IOException("Lost socket " + this);
	}

	public final Address getMyAddress(ListeningAddress lstaddr) {
		try {
			return new tcpAddress(
				(tcpTransport)t,
				sock.getLocalAddress(),
				((tcpListeningAddress) lstaddr).getPort());
		} catch (BadAddressException e) { // shouldn't really be possible
			return null;
		}
	}

	public final Address getMyAddress() {
		try {
			return new tcpAddress(
				(tcpTransport)t,
				sock.getLocalAddress(),
				sock.getLocalPort());
		} catch (BadAddressException e) { // shouldn't really be possible
			return null;
		}
	}

	public final Address getPeerAddress(ListeningAddress lstaddr) {
		try {
			return new tcpAddress((tcpTransport)t, peerAddr, ((tcpListeningAddress) lstaddr).getPort());
		} catch (BadAddressException e) { // shouldn't really be possible
			return null;
		}
	}

	public final Address getPeerAddress() {
		try {
			return new tcpAddress((tcpTransport)t, peerAddr, sock.getPort());
		} catch (BadAddressException e) { // shouldn't really be possible
			return null;
		}
	}

	public final String toString() {
		// Socket.toString() does a bunch of stuff like reverse lookups.
		// no good.
		//return getTransport().getName()+" connection: " + sock;
		StringBuffer sb = new StringBuffer(getTransport().getName());
		sb.ensureCapacity(75);
		sb.append("/connection: ");
		Socket sock = this.sock;
		if (closed) {
			sb.append("CLOSED"); //you won't believe it till you see it with
		} else if (sock != null)
			try {
				InetAddress addr = sock.getInetAddress();
				int localPort = sock.getLocalPort();
				if (localPort != Node.listenPort) {
					sb.append(localPort).append('>');
				}
				if (addr != null) {
					//this is becoming increasingly common --zab
					sb.append(addr.getHostAddress());
				}
				sb.append(':').append(sock.getPort());
				if (localPort == Node.listenPort) {
					sb.append(">local");
				}
			} catch (Throwable t) {
				sb.append(t);
			} else {
			sb.append("null");
		}
		sb.append(',').append(super.toString());
		return sb.toString();
        
	}

}
