package freenet.support.servlet;
import javax.servlet.ServletOutputStream;
import java.io.OutputStream; 
import java.io.IOException;
/**
 *"Many are the strange chances of the world, and help oft shall come from
 * the hands of the weak when the wise falter." - Mithrandir
 * @author oskar
 */

public final class ServletOutputStreamImpl extends ServletOutputStream {

    private final OutputStream out;

    public ServletOutputStreamImpl(OutputStream out) {
	if(out == null) throw new IllegalArgumentException("null argument");
        this.out = out;
    }

    public final void write(int i) throws IOException {
        out.write(i);
    }

    public final void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    public final void flush() throws IOException {
        out.flush();
    }

    public final void close() throws IOException {
        out.close();
    }
}

