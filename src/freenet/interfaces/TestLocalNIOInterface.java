/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.interfaces;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;
import java.util.Vector;

import freenet.Connection;
import freenet.Core;
import freenet.ListenException;
import freenet.ListeningAddress;
import freenet.support.Logger;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.ThrottledAsyncTCPWriteManager;
import freenet.transport.NIOReader;
import freenet.transport.NIOWriter;
import freenet.transport.tcpConnection;

public class TestLocalNIOInterface extends BaseLocalNIOInterface {
    
    public final ThrottledAsyncTCPReadManager rsl;
    public final ThrottledAsyncTCPWriteManager wsl;
    
    public TestLocalNIOInterface(ListeningAddress listenAddr, 
    			ThrottledAsyncTCPReadManager rsl, 
				 ThrottledAsyncTCPWriteManager wsl,
				 String allowedHosts, 
				 int lowRunningConnections,
				 int highRunningConnections) 
	throws ListenException {
	super(listenAddr, allowedHosts, lowRunningConnections,
	      highRunningConnections, "Test interface");
	this.rsl = rsl;
	this.wsl = wsl;
    }
    
    protected void handleConnection(Connection conn) {
	Core.logger.log(this, "Received connection", Logger.DEBUG);
	if(!(conn instanceof tcpConnection))
	    throw new IllegalArgumentException();
	tcpConnection tc = (tcpConnection)conn;
	Core.logger.log(this, "tcpConnection", Logger.DEBUG);
	Socket sock;
	try {
		sock = tc.getSocket();
	} catch (IOException e) { return; }
	SocketChannel sc = sock.getChannel();
	Core.logger.log(this, "Got channel: "+sc, Logger.DEBUG);
	try {
	    sc.configureBlocking(false);
	} catch (IOException e) {
	    Core.logger.log(this, "Cannot configure nonblocking mode on SocketChannel!", Logger.ERROR);
	}
	rsl.register(sc, new TestNIOCallback(sc, tc));
	Core.logger.log(this, "Registered channel", Logger.DEBUG);
    }
    
    long connectionCounter = 0;
    Object ccSync = new Object();
    
    public class TestNIOCallback implements NIOReader,NIOWriter {
	long connectionNumber;
	SelectableChannel sc;
	ByteBuffer buf;
	tcpConnection conn;
	
	Vector sending = new Vector();
	
	public TestNIOCallback(SelectableChannel sc, tcpConnection conn) {
	    synchronized(ccSync) {
		connectionCounter++;
		connectionNumber = connectionCounter;
	    }
	    this.sc = sc;
	    this.conn = conn;
	    buf = ByteBuffer.allocate(16384);
	}
	
	public int process(ByteBuffer b) {
	    Core.logger.log(TestLocalNIOInterface.this,
			    "Got buffer", Logger.DEBUG);
	    if(b == null) return 1;
	    int len = b.remaining();
	    if(len <= 0) return 1;
	    byte[] buf = new byte[len];
	    b.get(buf);
	    String s = new String(buf);
	    Core.logger.log(TestLocalNIOInterface.this, 
			    "Connection "+connectionNumber+": "+
			    s.length()+": " + s, Logger.DEBUG);
	    sendPacket(buf);
	    sendPacket(buf);
	    sendPacket(buf);
	    return 1;
	}
	
	public void sendPacket(byte[] buf) {
	    Core.logger.log(this, "Sending packet of length "+buf.length,
			    Logger.DEBUG);
	    try {
		if(!wsl.send(buf, sc, this, ThrottledAsyncTCPWriteManager.MESSAGE)) {
		    Core.logger.log(this, "Failed send", Logger.DEBUG);
		    sending.add(buf);
		}
	    } catch (IOException e) {
		Core.logger.log(this, "Got IOException sending data",
				e, Logger.ERROR);
	    }
	}
	
	public void closed() {}
	public void jobDone(int size, boolean status) {
	    Core.logger.log(this, "Send complete: "+size+", "+status,
			    Logger.DEBUG);
	    if(status) {
		if(sending.size() > 0) {
		    byte[] buf = (byte[])(sending.remove(0));
		    try {
			if(!wsl.send(buf, sc, this, ThrottledAsyncTCPWriteManager.MESSAGE)) {
			    Core.logger.log(this, "Could not send data in jobDone handler!", Logger.ERROR);
			} else {
			    String s = "Hahahahahahaha\n";
			    sending.add(s.getBytes());
			}
			Core.logger.log(this, "Sent some data in jobDone",
					Logger.DEBUG);
		    } catch (IOException e) {
			Core.logger.log(this, "Sending data failed: "+e, e,
					Logger.ERROR);
		    }
		}
	    }
	}

	/* (non-Javadoc)
	 * @see freenet.transport.NIOReader#getBuf()
	 */
	public ByteBuffer getBuf() {
		return buf;
	}

	/* (non-Javadoc)
	 * @see freenet.transport.NIOCallback#queuedClose()
	 */
	public void queuedClose() {
	}

	/* (non-Javadoc)
	 * @see freenet.transport.NIOCallback#registered()
	 */
	public void registered() {
	}

	/* (non-Javadoc)
	 * @see freenet.transport.NIOCallback#unregistered()
	 */
	public void unregistered() {
	}

	/* (non-Javadoc)
	 * @see freenet.transport.NIOCallback#shouldThrottle()
	 */
	public boolean shouldThrottle() {
		return conn.shouldThrottle();
	}

	/* (non-Javadoc)
	 * @see freenet.transport.NIOCallback#countAsThrottled()
	 */
	public boolean countAsThrottled() {
		return conn.countAsThrottled();
	}

	/* (non-Javadoc)
	 * @see freenet.transport.NIOWriter#jobPartDone(int)
	 */
	public void jobPartDone(int size) {
	}
    }

	/* (non-Javadoc)
	 * @see freenet.interfaces.NIOInterface#starting()
	 */
	protected void starting() {
	}
}
