/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.SocketChannel;

import freenet.Core;
import freenet.support.Logger;
import freenet.transport.NIOReader;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.tcpConnection;

public final class NIOInputStream extends InputStream implements NIOReader {
	private volatile boolean dead = false;
	private volatile boolean alreadyClosedLink = false;
	private boolean disabledInSelector = false;
	private volatile boolean registered = false;
	private ByteBuffer accumulator;
	private SocketChannel chan;
	private final ThrottledAsyncTCPReadManager rsl;
	private NIOReader nextReader = null;
	private boolean logDEBUG;
	private tcpConnection conn;
	private static final int DEFAULT_TIMEOUT = 5*60*1000;
	private int timeout = DEFAULT_TIMEOUT;
	
	//profiling
	//WARNING:remove before release
	public volatile static int instances=0;
	private static final Object profLock = new Object();
	private final Object regLock = new Object();
	private final Object unregLock = new Object();
	
	public NIOInputStream (ByteBuffer buf, SocketChannel chan, 
						   tcpConnection conn,ThrottledAsyncTCPReadManager rsl) {
		this.accumulator = buf;
		this.chan = chan;
		this.rsl = rsl;
		this.conn = conn;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if(logDEBUG)
			Core.logger.log(this, "Created "+this+" for "+conn+" on "+chan+
							" with "+buf, Logger.DEBUG);
		//profiling
		//WARNING:remove before release
		synchronized(profLock){
			instances++;
		}
	}
	
	public boolean isRegistered() {
		return registered;
	}
	
	public int getTimeout() {
	    return timeout;
	}
	
	public void setTimeout(int timeout) {
	    this.timeout = timeout;
	}
	
	public final boolean markSupported(){return false;}
	public void mark(int r){}
	public void reset() {}
	/**
	 * this constructor will create internal buffer
	 */
	
	public void registered(){
		synchronized(regLock) {
			registered = true;
		}
		if(logDEBUG) Core.logger.log(this, "Registered "+this, 
									 new Exception("debug"), Logger.DEBUG);
	}
	
	public void unregistered(){
		synchronized(regLock) {
			registered = false;
		}
		if(logDEBUG) Core.logger.log(this, "Unregistered "+this, 
									 new Exception("debug"), Logger.DEBUG);
		if(nextReader != null) {
			if (logDEBUG)
				Core.logger.log(this, "nextReader not null: " + this + ":" + nextReader + ":" + alreadyClosedLink, Logger.DEBUG);
			if(!alreadyClosedLink) {
				rsl.register(chan, nextReader);
				rsl.scheduleMaintenance(chan, nextReader);
			} else {
				nextReader.closed();
			}
			nextReader = null; // we won't get any traffic from RSL now
		} else {
			if(logDEBUG)
				Core.logger.log(this, "nextReader == null", Logger.MINOR);
		}
		synchronized(unregLock) {
			unregLock.notifyAll();
		}
	}
	//Will detatch this InputStream from the selector subsystem and register
	//'newReader' as the new destination for any further data arriving over the
	//associated channel
	public void switchReader(NIOReader newReader) throws IOException{
		nextReader = newReader;
        tcpConnection.getRSL().unregister(this);
        while (isRegistered() && !alreadyClosedLink()) {
            synchronized (unregLock) {
                if (isRegistered())
                    break;
                try {
                    unregLock.wait(200);
                } catch (InterruptedException ex) {
                    Core.logger.log(this, "couldn't complete unregistration of NIOIS", Logger.ERROR);
                    throw new IOException("couldn't complete unregistration of NIOIS");
                }
            }
        }
		
	}
	
	public int process(ByteBuffer b) {
		if(logDEBUG) Core.logger.log(this, "process("+b+") ("+this+")",
									 Logger.DEBUG);
		if(b == null) return 1;
		else 
			synchronized(accumulator) {
				accumulator.notifyAll();
				// FIXME: hardcoded, but should be ok for inet
				if(b.capacity() - b.limit() > 2048)
					return 1;
				else {
					disabledInSelector = true;
					return 0;
				}
			}
	}
	
	
	
	public ByteBuffer getBuf() {return accumulator;}
	
	public int available() {
		synchronized(accumulator) {
			return accumulator.remaining();
		}
	}
	private String accumStatus() {
		return "accumulator:"+accumulator.position()+"/"+accumulator.limit()+
			"/"+accumulator.capacity()+"/"+toString();
	}
	public long skip(long n) {
		synchronized(accumulator) {
		if(logDEBUG)
			Core.logger.log(this, "Trying to skip "+n+" bytes on "+
							this+": "+
							accumStatus(), Logger.DEBUG);
		while(true) {
		    if(accumulator.remaining() >= 1) {
			int got = accumulator.remaining();
			if(n < got) got = (int)n;
			accumulator.position(got);
			accumulator.compact();
			accumulator.limit(accumulator.position());
			accumulator.position(0);
			if(disabledInSelector && 
			   ((accumulator.capacity() - accumulator.limit()) > 4096))
				reregister(); // FIXME: hardcoded
			if(logDEBUG)
				Core.logger.log(this, "Skipped "+got+"/"+n+" bytes, "+
								accumStatus()+": "+this, Logger.DEBUG);
			return got;
		    } else {
				if(alreadyClosedLink) {
				    if(logDEBUG)
				        Core.logger.log(this, "Link closed: "+this,
				                Logger.DEBUG);
				    return -1;
				}
			// Uh oh...
			try {
				if (alreadyClosedLink) return -1;
			    long now = System.currentTimeMillis();
			    accumulator.wait(timeout);
			    if (timeout > 0 && System.currentTimeMillis() - now >= timeout) {
			    	Core.logger.log(this, "waited more than 5 minutes in NIOIS.skip() "+conn+":"+this+" - closing",Logger.MINOR);
					close();
					return -1;
			    }
			} catch (InterruptedException e) {}
		    }
		}
		}
	}
	
	
	public int read(byte[] b) {
	    synchronized(accumulator) {
		if(logDEBUG)
			Core.logger.log(this, "Trying to read "+b.length+" bytes on "+
							this+": "+
							accumStatus(), Logger.DEBUG);
		while(true) {
		    if(accumulator.remaining() >= 1) {
			int got = accumulator.remaining();
			if(b.length < got) got = b.length;
			accumulator.get(b);
			accumulator.compact();
			accumulator.limit(accumulator.position());
			accumulator.position(0);
			if(disabledInSelector && 
			   ((accumulator.capacity() - accumulator.limit()) > 4096))
				reregister(); // FIXME: hardcoded
			if(logDEBUG)
				Core.logger.log(this, "Read "+got+"/"+b.length+" bytes, "+
								accumStatus()+": "+this,
								Logger.DEBUG);
			return got;
		    } else {
				// Uh oh...
				if(alreadyClosedLink) return -1;
				try {
					long now = System.currentTimeMillis();
					if (alreadyClosedLink) return -1;
					accumulator.wait(timeout);
					if (timeout > 0 && System.currentTimeMillis() - now >= timeout) {
						Core.logger.log(this, "waited more than "+timeout+
						        " in NIOIS.read(byte[]) " + conn +":"+this+"- closing",Logger.MINOR);
						close();
						return -1;
					}
				} catch (InterruptedException e) {}
		    }
		}
	    }
	}
	
	public int read (byte[] b, int off, int len) {
	    synchronized(accumulator) {
		if(logDEBUG)
			Core.logger.log(this, "Trying to read "+len+" bytes on "+
							this+": "+
							accumStatus(), Logger.DEBUG);
		while(true) {
		    if(accumulator.remaining() >= 1) {
			int got = accumulator.remaining();
			if(len < got) got = len;
			accumulator.get(b, off, got);
			accumulator.compact();
			accumulator.limit(accumulator.position());
			accumulator.position(0);
			if(disabledInSelector && 
			   ((accumulator.capacity() - accumulator.limit()) > 4096))
				reregister(); // FIXME: hardcoded
			if(logDEBUG)
				Core.logger.log(this, "Read "+got+"/"+len+" bytes, "+
								accumStatus()+": "+this,
								Logger.DEBUG);
			return got;
		    } else {
			// Uh oh...
			if(alreadyClosedLink) return -1;
			try {
			    long now = System.currentTimeMillis();
				if (alreadyClosedLink) return -1;
			    accumulator.wait(timeout);
			    if (timeout > 0 && System.currentTimeMillis() - now >= timeout) {
			    	Core.logger.log(this, "waited more than "+timeout+
			    	        "ms in NIOIS.read(byte[],int,int) "+conn+":"+this+" - closing",Logger.MINOR);
				close();
				return -1;
			    }
			} catch (InterruptedException e) {}
		    }
		}
	    }
	}
	
	public int read() {
	    synchronized(accumulator) {
		if(logDEBUG)
			Core.logger.log(this, "Trying to read 1 bytes on "+
							this+": "+accumStatus(),
							new Exception("debug"), Logger.DEBUG);
		while(true) {
		    if(accumulator.remaining() >= 1) {
			int x = accumulator.get();
			accumulator.compact();
			accumulator.limit(accumulator.position());
			accumulator.position(0);
			if(disabledInSelector && 
			   ((accumulator.capacity() - accumulator.limit()) > 4096))
				reregister(); // FIXME: hardcoded
			if(logDEBUG)
				Core.logger.log(this, "Read 1 bytes, "+
								accumStatus()+": "+this,
								Logger.DEBUG);
			return (x & 0xff);
		    } else {
				// Uh oh...
				if(alreadyClosedLink) {
					if(logDEBUG)
						Core.logger.log(this, "Link already closed ("+this+")",
										Logger.DEBUG);
					return -1;
				} else {
					try {
						if(logDEBUG)
							Core.logger.log(this, "Waiting for more data ("+
											this+")", Logger.DEBUG);
						long now = System.currentTimeMillis();
						if (alreadyClosedLink) return -1;
						accumulator.wait(timeout);
						if (timeout > 0 && System.currentTimeMillis() - now >= timeout) {
							Core.logger.log(this, "waited more than "+timeout+"ms in NIOIS.read() "+conn+":"+this+"- closing", new Exception("debug"), Logger.NORMAL);
							close();
							return -1;
						}
					} catch (InterruptedException e) {}
				}
			}
	    }
		}
	}
	/*
	public void discontinue() {
		Core.logger.log(this, "Discontinuing read input stream from "+
						this+": "+accumStatus(),
						Logger.DEBUG);
	    close();
	}*/
	
	public void close() {
		if(logDEBUG)
			Core.logger.log(this, "Closing read input stream from "+
							this+": "+accumStatus(),
							Logger.DEBUG);
		if(alreadyClosedLink) return;
		if(dead) return;
		synchronized(accumulator) {
			dead = true;
			accumulator.notifyAll(); //notify if other threads are waiting
			if(conn.isClosed()) return; 
			// don't block if already closed or if called from tcpConn
			rsl.queueClose(chan, this);
			//this was a thinko - queueClose used to set alreadyClosedLink
			while(!alreadyClosedLink) {
				try {
					long now = System.currentTimeMillis();
					accumulator.wait(timeout);
					if (timeout > 0 && System.currentTimeMillis() -now >=timeout)
						Core.logger.log(this,"waited "+timeout+"ms in NIOIS.close()???",Logger.ERROR);
						//and maybe throw?
				} catch (InterruptedException e) {}
			}
		}
	    //doneMovingTrailingFields = true;
// 		if(disabledInSelector && 
// 		   ((accumulator.capacity() - accumulator.limit()) > 4096))
// 	    	reregister(); // FIXME: hardcoded
// 	    if(rsl != null) {
// 		rsl.scheduleMaintenance(this);
// 	    } else {
// 		throw new IllegalStateException("Do not know my ReadSelectorLoop !");
// 	    }
	}
	
	private void reregister() {
		if(logDEBUG)
			Core.logger.log(this, "Reregistering "+this, Logger.DEBUG);
		disabledInSelector = false;
		try {
			rsl.register(chan, this);
		} catch (IllegalBlockingModeException e) {
			Core.logger.log(this, "Cannot reregister "+this+
							", due to exception", e, Logger.ERROR);
		}
	}
	
	public void queuedClose() {
		//set this again in case called from elsewhere
		dead=true;
		nextReader = null;
	}
	
	public void closed() {
		synchronized(accumulator) {
			alreadyClosedLink = true;
			accumulator.notifyAll();
		}
		if(nextReader != null) nextReader.closed();
	}
	
	public boolean alreadyClosedLink() {
		return alreadyClosedLink;
	}
	
	public boolean isClosed() {
		return alreadyClosedLink || dead;
	}
	
	public boolean shouldThrottle() {
		return conn.shouldThrottle();
	}
	
	public boolean countAsThrottled() {
		return conn.countAsThrottled();
	}
	
	//profiling
	//WARNING:remove before release
	protected void finalize() {
		nextReader = null;
		accumulator = null;
		chan = null;
		conn = null;
		synchronized(profLock) {
			instances--;
		}
	}
}
