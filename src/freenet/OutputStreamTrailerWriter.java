package freenet;

import java.io.*;

import freenet.support.Logger;

public class OutputStreamTrailerWriter implements TrailerWriter {
    OutputStream os;
    boolean closed = false;
    
    public OutputStreamTrailerWriter(OutputStream os) {
	this.os = os;
	if(os == null) throw new NullPointerException();
    }
    
    public void writeTrailing(byte[] block, int offset, int length,
			      TrailerWriteCallback cb) throws IOException {
	try {
	    os.write(block, offset, length);
	    try {
	        cb.written();
	    } catch (Throwable t) {
	        Core.logger.log(this, "AAAARGH!: caught "+t+
	                " calling "+cb+".written()", t, Logger.ERROR);
	    }
	} catch (IOException e) {
	    close();
	    throw e;
	}
    }
    
    public void close() {
        closed = true;
        try {
            os.close();
        } catch (IOException e) {
            // Ugh. Hopefully it's still closed.
            Core.logger.log(this, "close() on "+os+" threw!: "+e,
                    e, Logger.NORMAL);
        }
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
        return false;
    }
}
