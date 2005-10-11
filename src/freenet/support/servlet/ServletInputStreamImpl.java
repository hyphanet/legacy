package freenet.support.servlet;
import java.io.IOException;
import java.io.InputStream;
import javax.servlet.ServletInputStream;

/**
 * Simple implementation.
 *
 * @author oskar
 */

public final class ServletInputStreamImpl extends ServletInputStream {

    private final InputStream in;

    public ServletInputStreamImpl(InputStream in) {
        this.in = in;
    }

    public final int read() throws IOException {
	if(in == null) return -1;
        return in.read();
    }

    public final int read(byte[] b) throws IOException {
	if(in == null) return -1;
        return in.read(b, 0, b.length);
    }

    public final int read(byte[] b, int off, int len) throws IOException {
	if(in == null) return -1;
        return in.read(b, off, len);
    }

    public final void close() throws IOException {
	if(in == null) return;
        in.close();
    }

    public final int available() throws IOException {
	if(in == null) return -1;
        return in.available();
    }

    public final boolean markSupported() {
	if(in == null) return false;
        return in.markSupported();
    }

    public final void mark(int rl) {
	if(in == null) return;
        in.mark(rl);
    }

    public final void reset() throws IOException {
	if(in == null) return;
        in.reset();
    }

    public final void skip(int n) throws IOException {
	if(in == null) return;
        in.skip(n);
    }
}


