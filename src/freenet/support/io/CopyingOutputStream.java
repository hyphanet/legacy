package freenet.support.io;

import java.io.*;

/** FilterOutputStream which makes a copy of the first n bytes of data 
 *  sent to an OutputStream.
 *
 *  Note: Operations done on the copy stream first.
 *         
 * @author giannij
 */
public class CopyingOutputStream extends FilterOutputStream {
    protected long length = 0;
    protected long pos = 0;
    protected OutputStream copy = null;
    
    public CopyingOutputStream(OutputStream out, OutputStream copy, long maxBytes) {
        super(out);
        this.copy = copy;
        this.length = maxBytes;
    }

    public void write(int b) throws IOException {
        if (pos < length) {
            copy.write(b);
        }
        out.write(b);
        pos++;
    }

    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }

    public void write(byte[] buf, int off, int len) throws IOException {
        if (pos < length) {
            if (pos + len <= length) {
                copy.write(buf,off,len);
            }
            else {
                copy.write(buf,off,(int) (length - pos));
            }
        }

        out.write(buf, off, len);

        pos += len;
    }

    public void flush() throws IOException {
        copy.flush();
        out.flush();
    }
}
