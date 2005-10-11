package freenet.support.io;
import java.io.InputStream;
import java.io.IOException;
import java.io.FilterInputStream;

public class LimitedInputStream extends FilterInputStream {
    long read;
    final long limit;
    final boolean shouldThrow;
    public LimitedInputStream(InputStream i, long limit, boolean shouldThrow) {
	super(i);
	this.limit = limit;
	this.read = 0;
	this.shouldThrow = shouldThrow;
    }
    
    public int read() throws IOException {
	if(read > limit) {
	    if(shouldThrow) throw new IOException("Over limit");
	    else return -1;
	}
	int x = -1;
	try {
	    x = in.read();
	} finally {
	    if(x > 0) read++;
	    if(x == -1) read = limit;
	}
	return x;
    }
    
    public int read(byte[] b) throws IOException {
	return read(b, 0, b.length);
    }
    
    public int read(byte[] b, int off, int len) throws IOException {
	if(read + len > limit) {
	    if(read < limit) {
		len = (int)(limit - read);
	    } else {
		len = 0;
	    }
	}
	if(read > limit) {
	    if(shouldThrow) throw new IOException("Over limit");
	    else return -1;
	}
	int ret = -1;
	if(len <= 0) return -1;
	try {
	    ret = in.read(b, off, len);
	} finally {
	    if(ret != -1) read += ret;
	    if(ret == -1) read = limit;
	}
	return ret;
    }
}
