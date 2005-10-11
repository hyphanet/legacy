package freenet.support.io;

import java.io.*;

/** Chop an OutputStream into chunks and send it on via the abstract 
 * sendChunk()
 */
public abstract class DataChunkOutputStream extends OutputStream {
    
    protected long length, pos = 0;
    protected byte[] buffer;

    protected DataChunkOutputStream(long length, long chunkSize) {
	super();
	this.length = length;
	buffer      = new byte[(int) chunkSize];
    }
    
    public void write(int b) throws IOException {            
	// we'll allow the padding bytes to be thrown at us
	// .. just do nothing once pos >= length
	if (pos < length) {
	    buffer[(int) (pos++ % buffer.length)] = (byte) (b & 0xFF);
	    if (pos % buffer.length == 0)
		sendChunk(buffer.length);
	    else if (pos == length)
		sendChunk((int) (pos % buffer.length));
	}
    }
    
    public void write(byte[] buf, int off, int len) throws IOException {
	while (len > 0) {
	    // we'll allow the padding bytes to be thrown at us
	    // .. just do nothing once pos >= length
	    if (pos == length) return;
	    int n = (int) Math.min(buffer.length - pos % buffer.length, length - pos);
	    if (n > len) n = len;
	    System.arraycopy(buf, off, buffer, (int) (pos % buffer.length), n);
	    pos += n;
	    off += n;
	    len -= n;
	    if (pos % buffer.length == 0)
		sendChunk(buffer.length);
	    else if (pos == length)
		sendChunk((int) (pos % buffer.length));
	}
    }
    
    protected abstract void sendChunk(int chunkSize) throws IOException;
    
}
