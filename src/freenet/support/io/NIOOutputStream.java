/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.support.io;

import java.io.*;
import freenet.transport.*;

import java.nio.channels.*;
import java.net.SocketException;
import freenet.support.*;
import freenet.*;

public final class NIOOutputStream extends OutputStream implements NIOWriter {
	protected volatile boolean dead = false;
	
	protected byte[] currentJob;
	protected SelectableChannel chan;
	private int CHOSsent;
	private final ThrottledAsyncTCPWriteManager wsl;
	private tcpConnection conn;
	private boolean logDEBUG;
	
	public void registered(){}
	public void unregistered(){}
	public synchronized void queuedClose() {
		if(logDEBUG)
			Core.logger.log(this, "queuedClose "+this, Logger.DEBUG);
		dead = true;
		this.notifyAll();
	}
	
	public boolean isClosed() {
		return dead;
	}
	
	public boolean shouldThrottle() {
		tcpConnection c = conn;
		if (!dead && c != null)
			return c.shouldThrottle();
		else return false;
	}
	
	public boolean countAsThrottled() {
		if (!dead && conn != null)
			return conn.countAsThrottled();
		else return false;
	}
	
	//profiling
	//WARNING:remove before release
	public volatile static int instances=0;
	public NIOOutputStream(SelectableChannel chan, 
						   tcpConnection conn,ThrottledAsyncTCPWriteManager wsl) {
		if(chan == null) throw new IllegalArgumentException("null channel!");
		this.chan = chan;
		this.wsl = wsl;
		CHOSsent=0;
		this.conn = conn;
		//profiling
		//WARNING:remove before release
		instances++;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if(logDEBUG)
			Core.logger.log(this, "Created "+this+" for "+conn+" on "+chan,
							Logger.DEBUG);
	}
	
	public synchronized void write(byte[] b) throws IOException{
		if(b.length == 0) return;
		if (dead) {
			throw new SocketException("Connection already closed: "+this);
		}
		if(!wsl.send(b,chan,this,WriteSelectorLoop.NEGOTIATION)) // trailers are CH.CHOS
			throw new SocketException("Couldn't send data: WSL.send returned false");
		if(logDEBUG)
			Core.logger.log(this, "Sent write of "+b.length+
							" bytes, waiting: "+this, Logger.DEBUG);
		try {
			long now = System.currentTimeMillis();
			if(logDEBUG)
				Core.logger.log(this, "Trying to write "+b.length+
								" bytes at "+this, Logger.DEBUG);
			this.wait(5*60*1000);
			if (dead) {
				if(logDEBUG)
					Core.logger.log(this, "Stream "+this+
									" closed while writing", Logger.DEBUG);
				throw new SocketException("stream closed while writing");
			}
			if (System.currentTimeMillis() - now >= 5*60*1000) {
				Core.logger.log(this,"NIOOS.write(byte[]) timed out. "+conn+
								", closing",Logger.ERROR);
				close();
				throw new SocketException("stream closed while writing");
			}
		} catch (InterruptedException e) {
			if(logDEBUG)
				Core.logger.log(this, "Wait interrupted: "+this,
								Logger.DEBUG);
		}
		
		if(logDEBUG)
			Core.logger.log(this, "CHOSsent now "+CHOSsent+", b.length="+
							b.length+" for "+this, Logger.DEBUG);
		if (CHOSsent < b.length) throw new SocketException("Could not send all data - send "+CHOSsent+" of "+b.length);
		currentJob=null;
		CHOSsent=0;
		
	}
	
	public synchronized void write(byte[] b, int off, int len) throws IOException{
		
		if (dead) throw new SocketException("Connection already closed: "+this);
		if(len == 0) return;
		if(logDEBUG)
			Core.logger.log(this, "Trying to write "+len+" bytes to "+chan+
							" ("+this+")", new Exception("debug"), 
							Logger.DEBUG);
		if(!wsl.send(b,off,len,chan,this,WriteSelectorLoop.NEGOTIATION))
			throw new SocketException("Could not send data: WSL.send returned false");
		try {
			long now = System.currentTimeMillis();
			this.wait(5*60*1000);
			if (dead) {
				Core.logger.log(this, "Socket "+this+
								" closed while writing", Logger.MINOR);
				throw new SocketException("stream closed while writing");
			}
			if (System.currentTimeMillis() - now >= 5*60*1000) {
				Core.logger.log(this,"NIOOS.write(byte[],int,int) timed out."+conn+" closing "+this,Logger.ERROR);
				close();
				throw new SocketException("stream closed while writing");
			}
		} catch (InterruptedException e) {}
		
		if (CHOSsent < len) {
			if (!dead)
				throw new SocketException("Could not send all data - send "+CHOSsent+" of "+b.length);
			else
				throw new SocketException("Could not send all data, stream DEAD "+CHOSsent+" of "+b.length);
		}
		if(logDEBUG)
			Core.logger.log(this, "Sent "+CHOSsent+" of "+len+" bytes on "+this, Logger.DEBUG);
		currentJob=null;
		CHOSsent=0;
		
	}
	
	public synchronized void write(int b) throws IOException{
		currentJob = new byte[1];
		currentJob[0]=(byte)b;
		write(currentJob);
	}
	
	/**
	 * this doesn't really flush.  it just throws if we didn't finish writing
	 */
	public synchronized void flush() throws IOException{
		CHOSsent=0;
		this.notifyAll();
		if (dead) throw new SocketException("Already closed!");
		if (currentJob!=null) throw new SocketException("Still writing!");
	}
		
	public void close(){
		if(logDEBUG)
			Core.logger.log(this, "Closing "+this, Logger.DEBUG);
		//flush();
		//if(dead || (chan == null)) return;
		synchronized(this) {
			if(dead || (chan == null)) {
				this.notifyAll();
				return;
			}
			if(logDEBUG)
				Core.logger.log(this, "Still closing "+this, Logger.DEBUG);
			dead=true;
			this.notifyAll();
			if(conn.isClosed()) return; 
			// don't block if already closed or if called from tcpConn
			wsl.queueClose((SocketChannel)chan,this);
			//why the hell do we need to wait here?
			/*try{
			  if(logDEBUG)
			  Core.logger.log(this, "Waiting for close to finish on "+this,Logger.DEBUG);
			  this.wait(30000);
			  if(dead || chan == null) {
			  if(logDEBUG)
			  Core.logger.log(this, "Close apparently finished on "+this, Logger.DEBUG);
			  } else {
			  if(logDEBUG)
			  Core.logger.log(this, "Gave up on close after 10 "+
			  "seconds for "+this, Logger.ERROR);
			  }
			  } catch (InterruptedException e) {}	*/
		}
	}
		
	public synchronized void closed() {
		if(logDEBUG)
			Core.logger.log(this, "closed() on "+this+" ("+conn+")",
							new Exception("debug"), Logger.DEBUG);
		dead=true;
		chan=null;
		conn=null;
		this.notifyAll();
	}
	    
	public synchronized void jobDone(int size, boolean status) {
		CHOSsent=size; //TODO: don't we care about the status here?
		this.notifyAll();
	}
	
	public void jobPartDone(int size) {}
	
	//profiling
	//WARNING:remove before release
	protected void finalize() {
		instances--;
	}
}
