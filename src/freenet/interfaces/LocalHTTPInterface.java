/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.interfaces;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Vector;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletResponse;

import freenet.Connection;
import freenet.Core;
import freenet.ListenException;
import freenet.ListeningAddress;
import freenet.interfaces.servlet.MultipleHttpServletContainer;
import freenet.support.Logger;
import freenet.support.io.NIOInputStream;
import freenet.support.servlet.http.HttpServletRequestImpl;
import freenet.support.servlet.http.SessionHolder;
import freenet.support.servlet.http.SessionHolderImpl;
import freenet.transport.ThrottledAsyncTCPWriteManager;
import freenet.transport.NIOReader;
import freenet.transport.NIOWriter;
import freenet.transport.tcpConnection;

public class LocalHTTPInterface extends BaseLocalNIOInterface {
    
	public final MultipleHttpServletContainer container;
    public final SessionHolder sh = new SessionHolderImpl();
	
    public LocalHTTPInterface(ListeningAddress listenAddr, 
							  MultipleHttpServletContainer container,
							  String allowedHosts, 
							  int lowRunningConnections,
							  int highRunningConnections,
							  String interfaceName) 
		throws ListenException {
		super(listenAddr, allowedHosts, lowRunningConnections,
			  highRunningConnections,interfaceName);
		this.container = container;
    }
    
    protected void handleConnection(Connection conn) {
		Core.logger.log(this, "Received connection", Logger.DEBUG);
		if(!(conn instanceof tcpConnection))
			throw new IllegalArgumentException();
		Core.logger.log(this, "tcpConnection", Logger.DEBUG);
		Socket sock;
		try {
			sock = ((tcpConnection)conn).getSocket();
		} catch (IOException e) {
			return;
		}
	    NIOInputStream niois = ((tcpConnection)conn).getUnderlyingIn();
	    if(niois.alreadyClosedLink()) {
			Core.logger.log(this, "Already closed link, not registering: " + this, Logger.MINOR);
			conn.close();
			return;
	    }
		SocketChannel sc = sock.getChannel();
		Core.logger.log(this, "Got channel: "+sc, Logger.DEBUG);
		HTTPCallback hc = new HTTPCallback(sc, conn);
		try {
			niois.switchReader(hc);
		} catch (IOException e1) {
			conn.close();
			Core.logger.log(this, "Failed switching reader, should not happen",e1, Logger.ERROR);
			return;
		}
		Core.logger.log(this, "Almost registered channel", Logger.DEBUG);
    }
    
    long connectionCounter = 0;
    Object ccSync = new Object();
    
    public class HTTPCallback implements NIOReader,NIOWriter {
		long connectionNumber;
		SocketChannel sc;
		Connection conn;
		
		Vector sending = new Vector();
		boolean closeAfterSent = false;
		
		ByteBuffer rawAccumulator;
		byte[] requestData;
		int requestDataLen;
		
		public boolean shouldThrottle() {
			return false;
		}
		
		public boolean countAsThrottled() {
			return false;
		}
		
		public HTTPCallback(SocketChannel sc, Connection conn) {
			synchronized(ccSync) {
				connectionCounter++;
				connectionNumber = connectionCounter;
			}
			this.sc = sc;
			this.conn = conn;
			requestData = new byte[2048];
			requestDataLen = 0;
			rawAccumulator = ByteBuffer.allocateDirect(65536);
			rawAccumulator.position(0).limit(0);
		}
		
		public int process(ByteBuffer buf) {
			Core.logger.log(LocalHTTPInterface.this,
							"Got buffer", Logger.DEBUG);
			if(buf == null) return 1;
			int len = buf.remaining();
			if(len <= 0) return 1;
			int rlen = len;
			if(rlen > (requestData.length - requestDataLen))
				rlen = requestData.length - requestDataLen;
			buf.get(requestData, requestDataLen, rlen);
			requestDataLen += rlen;
			Core.logger.log(this, "Got "+rlen+" bytes", Logger.DEBUG);
			// Try to tokenize it
			
			byte prevByte = 0;
			byte pprevByte = 0;
			byte ppprevByte = 0;
			byte b = 0;
			byte newline = (byte)'\n';
			byte rewind = (byte)'\r';
			int headersLength = -1;
			for(int x=0;x<requestDataLen;x++) {
				ppprevByte = pprevByte;
				pprevByte = prevByte;
				prevByte = b;
				b = requestData[x];
				if(b == newline && prevByte == newline || 
				   (b == newline && prevByte == rewind && 
					pprevByte == newline && ppprevByte == rewind)) {
					headersLength = x;
					Core.logger.log(this, "Got headers at "+headersLength,
									Logger.DEBUG);
					break;
				}
			}
			if(headersLength == -1) {
				Core.logger.log(this, "No double-newline detected",
								Logger.DEBUG);
				if(requestDataLen == requestData.length) {
					Core.logger.log(this, "HTTP connection "+
									requestData.length+
									" bytes long but headers did not end: "+
									new String(requestData),
									Logger.NORMAL);
					return -1; // Close connection, failure
				} else return 1; // Need more bytes
			} else {
				String headers;
				try {
					headers = new String(requestData, 0, headersLength, "ISO-8859-1" /* hardcode, should be correct as HTTP specifies it everywhere else */);
				} catch (java.io.UnsupportedEncodingException e) {
					Core.logger.log(this, "Java does not support ISO-8859-1 encoding? WTF?", Logger.ERROR);
					return -1;
				}
				Core.logger.log(this, "Got HTTP headers:\n"+headers+"-------------------------", Logger.DEBUG);
				
				HttpServletRequestImpl req;
				try {
					req = new HttpServletRequestImpl(container, conn, 0
													 /* no caching at that level */, sh, 
													 "BASIC", requestData, 0, 
													 headersLength);
				} catch (IOException e) {
					Core.logger.log(this, "IOException parsing headers: "+e+" for "+this,
									e, Logger.NORMAL);
					req = null;
				}
				if(req != null)
					Core.logger.log(this, "Got HttpServletRequest: "+req, Logger.DEBUG);
				
				HttpServletResponse resp = null;
				try {
					resp = (HttpServletResponse)(container.getResponseFor(req));
				} catch (IOException e) {
					Core.logger.log(this, "IOException: "+e, e, Logger.DEBUG);
					resp = null;
				}
				
				Core.logger.log(this, "Got HttpServletResponse: "+resp, Logger.DEBUG);
				
				Servlet servlet = null;
				try {
					servlet = container.getServletFor(req);
				} catch (UnavailableException e) {
					Core.logger.log(this, "UnavailableException: "+e, e, Logger.DEBUG);
					servlet = null;
				} catch (ServletException e) {
					Core.logger.log(this, "ServletException: "+e, e, Logger.DEBUG);
					servlet = null;
				}
				String servletName = (servlet == null) ? "(null)" : servlet.toString();
				String response = "HTTP/1.0 200 OK\nContent-Type: text/html\nConnection: close\n\n<html><head><title>LocalHTTPInterface</title></head><body><h1>LocalHTTPInterface</h1>Your headers were:<pre>"+headers+"</pre>"+servletName+"</body></html>";
				closeAfterSent = true;
				try {
					sendPacket(response.getBytes("ISO-8859-1"));
				} catch (java.io.UnsupportedEncodingException e) {
					Core.logger.log(this, "Java does not support ISO-8859-1 encoding? WTF?", Logger.ERROR);
					return -1;
				}
				return 1;
			}
		}
		
		public void sendPacket(byte[] buf) {
			Core.logger.log(this, "Sending packet of length "+buf.length,
							Logger.DEBUG);
			try {
				ThrottledAsyncTCPWriteManager wsl = tcpConnection.getWSL();
				if(!wsl.send(buf, sc, this,ThrottledAsyncTCPWriteManager.MESSAGE)) {
					Core.logger.log(this, "Failed send", Logger.DEBUG);
					sending.add(buf);
				}
			} catch (IOException e) {
				Core.logger.log(this, "Got IOException sending data",
								e, Logger.ERROR);
			}
		}
		
		public void queuedClose() {}
		public void jobPartDone(int size) {}
		public void jobDone(int size, boolean status) {
			Core.logger.log(this, "Send complete: "+size+", "+status,
							Logger.DEBUG);
			if(status) {
				if(sending.size() > 0) {
					byte[] buf = (byte[])(sending.remove(0));
					try {
						ThrottledAsyncTCPWriteManager wsl = tcpConnection.getWSL();
						if(!wsl.send(buf, sc, this,ThrottledAsyncTCPWriteManager.MESSAGE)) {
							Core.logger.log(this, "Could not send data in jobDone handler!", Logger.ERROR);
						}						Core.logger.log(this, "Sent some data in jobDone",
										Logger.DEBUG);
					} catch (IOException e) {
						Core.logger.log(this, "Sending data failed: "+e, e,
										Logger.ERROR);
					}
				} else if (closeAfterSent) {
					tcpConnection.getRSL().queueClose(sc,null);
					// WriteSelectorLoop doesn't know about it unless we are actually sending data
				}
			}
		}
		
		public void closed() {
			// anything to do?
		}
		
		public void registered() {}
		public void unregistered() {}
		
		public ByteBuffer getBuf() {
			return rawAccumulator;
		}
    }
	
	protected void starting() {
	}
}
