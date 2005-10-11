package freenet.support.io;

import java.io.*;

/** Blindly removes control bytes from an OutputStream.
  */
public class CBStripOutputStream extends FilterOutputStream {

    protected long partLength, pos = 0;
    protected int cbLength;
    
    public CBStripOutputStream(OutputStream out, long partLength, int cbLength) {
        super(out);
        this.partLength = partLength;
        this.cbLength   = cbLength;
    }

    public void write(int b) throws IOException {
        if (pos >= 0) {
            out.write(b);
            if (++pos == partLength) pos = -1 * cbLength;
        }
        // skip control bytes
        else ++pos;
    }

    public void write(byte[] buf, int off, int len) throws IOException {
        while (len > 0) {
            // skip control bytes
            if (pos < 0) {
                int skip = -1 * (int) pos;
                if (skip > len) skip = len;
                pos += skip;
                off += skip;
                len -= skip;
                continue;
            }
            // move data bytes
            int read = len;
            if (read > partLength - pos) read = (int) (partLength - pos);
            out.write(buf, off, read);
            pos += read;
            off += read;
            len -= read;
            // maybe go back to skipping control bytes
            if (pos == partLength) pos = -1 * cbLength;
        }
    }
}






