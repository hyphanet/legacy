package freenet.support;

import java.io.IOException;

/**
 * Interface for pooled RandomAccessFile-like objects. Purpose of pooling is to
 * save FDs. Classes implementing this interface should be synchronized.
 * 
 * @see RandomAccessFilePool
 */
public interface PooledRandomAccessFile {
	
    public long length() throws IOException;
    public void seek(long pos) throws IOException;
	
	/**
	 * Synchronize. Provided to avoid requiring locking in client. In
	 * java.io.RandomAccessFile this would go via the FileDescriptor.
     */
    public void sync() throws IOException;
	
    public int read() throws IOException;
    public int read(byte[] b, int off, int len) throws IOException;
    public void write(int b) throws IOException;
    public void write(byte[] b, int off, int len) throws IOException;
    public long getFilePointer() throws IOException;
	
	/**
	 * Close the underlying RandomAccessFile, if we are short on handles. Call
	 * this when you have temporarily finished with a RAF, but don't want to
	 * close() yet.
     */
    public void closeRAF();
	
    public void close() throws IOException;
}
