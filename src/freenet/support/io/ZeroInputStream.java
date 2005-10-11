package freenet.support.io;
import java.io.IOException;

public class ZeroInputStream extends DiscontinueInputStream {

    protected boolean closed = false;
    protected int padValue;
    protected byte[] padBlock;

    /**
     * Creates a new ZeroInputStream.
     * @param  padValue   The value to pad with.
     * @param  blockSize  The array size to use when copying blocks on values
     *                    to return.
     */
    public ZeroInputStream(int padValue, int blockSize) {
        super(null);
        if (padValue < 0)
            throw new IllegalArgumentException("ZeroInputStream illegal pad");
        this.padValue = padValue;
        padBlock = new byte[blockSize];
    }

    public void close() {
        closed = true;
    }

    public int read() throws IOException {
        if (closed) 
            throw new IOException("Stream closed");
        return padValue;
    }

    public int read(byte[] buff, int off, int len) throws IOException {
        if (closed) 
            throw new IOException("Stream closed");
        for (int i=off; i<off+len; i += padBlock.length) {
            System.arraycopy(padBlock, 0, buff, i, Math.min(off+len-i,
                                                            padBlock.length));
        }
        return len;
    }
    
    public int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }

    public int available() {
        return 32768;
    }

    public void discontinue() throws IOException {
        close();
    }
}
