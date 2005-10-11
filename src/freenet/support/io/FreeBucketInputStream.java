package freenet.support.io;

import freenet.support.Bucket;
import freenet.support.BucketFactory;
import java.io.*;

/** Just hangs around to make sure the Bucket is properly freed
  * when the user of the InputStream is done reading it.
  */
public class FreeBucketInputStream extends DiscontinueInputStream {
    
    private BucketFactory bf;
    private Bucket bucket;

    private boolean closed = false;
    
    public FreeBucketInputStream( InputStream in,
                                  BucketFactory bf, Bucket bucket ) {
        super(in);
        this.bf     = bf;
        this.bucket = bucket;
    }

    public int read() throws IOException {
        if (closed) throw new IOException("stream closed");
        try {
            return in.read();
        }
        catch (IOException e) {
            close();
            throw (IOException) e.fillInStackTrace();
        }
    }
    
    public int read(byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("stream closed");
        try {
            return in.read(buf, off, len);
        }
        catch (IOException e) {
            close();
            throw (IOException) e.fillInStackTrace();
        }
    }
    
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                in.close();
            }
            finally {
                bf.freeBucket(bucket);
            }
        }
    }

    public void discontinue() throws IOException {
        close();
    }
    
    // grrr
    protected void finalize() throws Throwable {
        if (bucket != null && (!closed)) { // don't care if bucket null
            freenet.Core.logger.log(this,
                "I was GC'd without being closed!"
                +" This means freeing of my bucket was delayed: "+bucket,
                freenet.support.Logger.ERROR);
            close();
        }
    }
}


