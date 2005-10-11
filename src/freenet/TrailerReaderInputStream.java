package freenet;

import freenet.support.Irreversible;
import freenet.support.Logger;
import freenet.support.io.DiscontinueInputStream;

/**
 * Emulate a blocking input stream using a MuxTrailerReader.
 * @author amphibian
 */
public class TrailerReaderInputStream
	extends DiscontinueInputStream
	implements TrailerReadCallback {

	private static final int MAX_TRAILERCHUNK_WAIT_MILLISECONDS = 5 * 60 * 1000; //Five minutes
	private MuxTrailerReader tr;
	private volatile byte[] buffer = null;
	private volatile int bufferOffset =0;
	private volatile int bufferLength =0;
	private Irreversible closed= new Irreversible(false);
	private boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	
	/**
	 * Create a TrailerReaderInputStream
	 * @param tr the MuxTrailerReader to ask for chunks. CAN BE NULL, in which case
	 * the caller MUST call setTrailer before using us.
	 */
	TrailerReaderInputStream() {
		super(null);
		if(logDEBUG) Core.logger.log(this, "Created "+this+": "+tr,
					Logger.DEBUG);
	}

	public void setTrailer(MuxTrailerReader tr) {
		this.tr = tr;
		if (tr == null)
			Core.logger.log(this, "Set trailerreader to null!", new Exception("debug"), Logger.ERROR);
		if (logDEBUG)
			Core.logger.log(this, "Set trailer: " + tr + " on " + this, Logger.DEBUG);
	}
	
	public synchronized int read(byte[] buf, int offset, int length) {
		if(logDEBUG)
			Core.logger.log(this, "read(buf," + offset + "," + length + ") on " + this, Logger.DEBUG);
		if(closed.state()) {
			if(logDEBUG)
				Core.logger.log(this, "Already closed: "+this+".read(buf,offset,"+
						length+")", Logger.DEBUG);
			return -1;
		}
		if(buffer == null || bufferLength <= 0) {
			if(buffer != null && bufferLength <= 0)
				buffer = null;
			long end = System.currentTimeMillis() + MAX_TRAILERCHUNK_WAIT_MILLISECONDS;
			if (tr != null) {
				tr.requestChunk();
			} else {
				Core.logger.log(this, "tr is null in tr.requestChunk():"+this+
					".read(buf,offset,"+length+")", Logger.ERROR);
				//FIXME what to do?
			}
			while(buffer == null || bufferLength <= 0) {
				if(closed.state()) {
					if(logDEBUG)
						Core.logger.log(this, "Already closed (late): "+this+
							".read(buf,offset,"+length+")", Logger.DEBUG);
					return -1;
				}
				if(buffer != null && bufferLength <= 0)
					buffer = null;
				long maxWait = end - System.currentTimeMillis();
				try {
					if(maxWait > 0)
						this.wait(maxWait);
					else break;
				} catch (InterruptedException e) {
				    // Don't care
				}
			}
			if(buffer == null) {
				if(closed.state()){
					Core.logger.log(this, "Another chunk arrived after closure in " + this, Logger.NORMAL);
				}else{
					Core.logger.log(this, "Timed out waiting for chunk in " + this, 
					        new Exception("debug"), Logger.NORMAL);
				close(true);
				}
				return -1;
			}
		}
		// We have some data!
		int len = Math.min(length, bufferLength);
		System.arraycopy(buffer, bufferOffset, buf, offset, len);
		bufferOffset += len;
		bufferLength -= len;
		if(logDEBUG)
			Core.logger.log(this, "Got data, length "+len+" on "+this,
					Logger.DEBUG);
		return len;
	}
	
	public int read(byte[] buf) {
		return read(buf, 0, buf.length);
	}
	
	final byte[] tmpBuf = new byte[1];
	public synchronized int read() {
		int i = read(tmpBuf, 0, 1);
		if(i <= 0) return -1;
		return (tmpBuf[0]) & 0xff;
	}
	
	public void discontinue() {
		close(false);
	}

	public synchronized void receive(byte[] buf, int offset, int length) {
		if(buffer != null && bufferLength > 0) {
			Core.logger.log(this, "Received "+length+" bytes but already queued "+
					bufferLength+" bytes!", new Exception("debug"), Logger.ERROR);
			return;
		} else {
			buffer = buf;
			bufferOffset = offset;
			bufferLength = length;
			if(logDEBUG)
				Core.logger.log(this, "Received "+length+" bytes on "+this, Logger.DEBUG);
			notifyAll();
		}
	}

	public void closed() {
		close();
	}
	
	public void close() {
	    close(false);
	}
	
	public void close(boolean kill) {
		if(logDEBUG)
			Core.logger.log(this, "Close() on " + this, new Exception("debug"), Logger.DEBUG);
		if(!closed.tryChange()) return;
		synchronized(this) {
			notifyAll();
		}
		tr.close(kill);
	}

	public String toString() {
		return TrailerReaderInputStream.class.getName()+": tr ="+tr+", bufferOffset="+bufferOffset+", bufferLength="+bufferLength+", closed="+closed.state()+", super: "+super.toString();
	}
	
	protected void finalize() {
	    if(closed.state()) return;
	    Core.logger.log(this, "Finalized before close: "+this+" for "+tr, Logger.NORMAL);
	    close();
	}
}
