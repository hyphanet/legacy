package freenet.support;
import java.io.*;
import java.util.Hashtable;
import java.util.Vector;
import java.util.Enumeration;

/*
  This code is distributed under the GNU Public Licence (GPL)
  version 2.  See http://www.gnu.org/ for further details of the GPL.
*/

/**
 * A BucketSequence creates a single InputStream
 * from a sequence of Buckets.
 * <p>
 * This class was written to support streaming
 * SplitFiles in fproxy.
 * 
 * @author giannij
 */
public class BucketSequence implements BucketSink {

    // Handle ranges.
    // Note: Never deal with bucket size explicitly.
    public BucketSequence(int startBucketNum, int endBucketNum, int startBucketOffset,
                           int nRangeBytes, BucketFactory bucketFactory) {

        this.startBucketNum = startBucketNum;
        this.endBucketNum = endBucketNum;
        this.startBucketOffset = startBucketOffset;
	this.length = nRangeBytes;
	this.bucketFactory = bucketFactory;
        // Starts out before first bucket.
        currentBucket = startBucketNum - 1;
    }

    public BucketSequence(int length, BucketFactory bucketFactory) {
        this.startBucketNum = 0;
        this.endBucketNum =  -1;
        this.startBucketOffset = 0;

	this.length = length;
	this.bucketFactory = bucketFactory;
        // Starts out before first bucket.
        currentBucket = startBucketNum - 1;
    }

    // By default, buckets are released as soon as they have been streamed.
    // call setKeepBuckets(true) to keep buckets 
    // until release() is called.
    public final void setKeepBuckets(boolean value) { keepBuckets = value; }

    // Releases buckets that have already been sent.
    // This is a NOP unless KeepBuckets was set true.
    public final void flush() {
        Vector list = new Vector();

	for (Enumeration e = buckets.keys() ; e.hasMoreElements() ;) {
	    Integer number = (Integer)e.nextElement();
            if (number.intValue() < currentBucket) {
                list.addElement(buckets.get(number));
            }
	}

        for (int i = 0; i < list.size(); i++) {
            Bucket b = (Bucket)list.elementAt(i);
	    try {
		bucketFactory.freeBucket(b);
	    }
	    catch(IOException ioe) {
		// NOP
	    }
            buckets.remove(b);
        }
    }


    // It is an error to put the same bucket more than once.
    // It is an error to put different buckets with the same number.
    // It is an error to put a bucket after eod(), abort() or release() have been called.
    // When the range constructor is used, it is an error to put a bucket
    // with a number greater than endBucketNum or less than startBucketNum.
    public synchronized void putBucket(Bucket b, int bucketNum) 
	throws IOException {

	if (eod) {
	    throw new IllegalStateException("Already called eod.");
	}

	Integer key = new Integer(bucketNum);
	buckets.put(key, b);
	if ((bucketNum == startBucketNum) && (currentBucket == (startBucketNum -1))) {
            try {
                nextBucket();
            }
            catch (InterruptedException ie) {
                throw new InterruptedIOException(ie.toString());
            }
	}
	notifyAll();
    }

    // signals that no more buckets will be arriving.
    public synchronized void eod() {
	eod = true;
	notifyAll();
    }

    public synchronized void abort(String errMsg, int block) {
	this.errMsg = errMsg;
	this.errBlock = block;
	
	release();
    }
    
    public synchronized void release() {
	// Slam the current stream closed.
	if (in != null) {
	    try { in.close(); } catch (IOException ioe) {} 
	    in = null;
	}

	// Release all buckets.
	for (Enumeration e = buckets.elements() ; e.hasMoreElements() ;) {
	    Bucket bucket = (Bucket)e.nextElement();
	    try {
		bucketFactory.freeBucket(bucket);
	    }
	    catch(IOException ioe) {
		// NOP
	    }
	}
	buckets.clear();
	eod = true;
	closed = true;
	notifyAll();
    }

    // CAN ONLY BE CALLED ONCE
    // returned stream will throw a an IOException
    // on asynchronous read error.
    public InputStream getInputStream() throws IOException {
	if (inputStream != null) {
	    throw new IOException("Already gave out an InputStream!");
	}
	inputStream = new BucketSequenceInputStream();
	return inputStream;
    } 

    ////////////////////////////////////////////////////////////

    // blocks until bucket available
    private synchronized void nextBucket() throws IOException, InterruptedException {
        if ((endBucketNum != -1) && (currentBucket == endBucketNum)) {
            throw new IOException("Already read last Bucket in range. No more data.");
        }

	Integer key = new Integer(currentBucket + 1);
	while (buckets.get(key) == null && (errMsg == null) && (!eod)) {
	    wait(200);
	}
	handleError();

	if (eod && (buckets.get(key) == null)) {
	    throw new IOException("No more data in BucketSequence.");
	}

	if (in != null) {
	    InputStream tmpIn = in;
	    in = null;
	    tmpIn.close();
	}

        if (currentBucket == startBucketNum) {
            // Handle offset in starting block.
            nTotalBytesRead += currentBucketLength - startBucketOffset;
        }
        else {
            nTotalBytesRead += currentBucketLength;
        }

	releaseBucket(currentBucket);
	currentBucket++;
	pos = 0;

	Bucket current = (Bucket)buckets.get(new Integer(currentBucket));
	if (current == null) {
	    // REDFLAG: remove? This should be be unreachable...
	    throw new RuntimeException("No more buckets!");
	}

	currentBucketLength = (int)current.size();

        // Check before opening stream.
        if (currentBucket == startBucketNum) {
            if (startBucketOffset >= currentBucketLength) {
                throw new IOException("startBucketOffset >= currentBucketLength");
            }
        }

	in = current.getInputStream();
        if (currentBucket == startBucketNum) {
            // Special case code for reading from ranges.
            int nSkip = startBucketOffset;
            while (nSkip > 0) {
                // REDFLAG: Throws on EOF right?
                long nSkipped = in.skip(nSkip);
                nSkip -= nSkipped;
            }
            pos = startBucketOffset;
        }
        //System.err.println("BucketSequence.nextBucket -- " + currentBucket +
        //                   "[" + currentBucketLength + "]");
    }

    private synchronized void releaseBucket(int bucketNum) throws IOException {
	if ((bucketNum < 0) || keepBuckets) {
	    return;
	}

	Integer key = new Integer(bucketNum);
	Bucket b = (Bucket)buckets.get(key);
	if (b != null) {
	    buckets.remove(b);
	    if (bucketFactory != null) {
		bucketFactory.freeBucket(b);
	    }
	}
    }

    private final synchronized void handleError() throws IOException {
	if (errMsg != null) {
	    throw new IOException(errMsg);
	}
    }

    private final synchronized int position() {
	if (pos == -1) {
	    return 0;
	}
	
	return nTotalBytesRead + pos;
    }

    private final synchronized int readable() throws IOException {
	if (currentBucket == startBucketNum - 1) {
	    handleError();
	    return 0;
	}

	if ((pos == currentBucketLength) && (position() < length)) {
	    try {
		nextBucket();
	    }
	    catch (InterruptedException ie) {
		throw new InterruptedIOException(ie.toString());
	    }
	}

	return currentBucketLength - pos;
    }

    // Won't block as long as len < readable().
    private final synchronized int read(byte[] buf, int offset, int len) throws java.io.IOException {
	if (position() >= length) {
	    // System.err.println("BucketSequence.read -- EOF @ " + position());
	    releaseBucket(currentBucket);
	    return -1; // EOF
	}

	if (position() + len > length) {
	    len = length - position();
	    //System.err.println("BucketSequence.read -- reduced len to " + len);
	}

	int written = len;
	int nBytes = 0;
	while (len > 0) {
            // wait for data
            // REDFLAG: if this fix is correct, check read() to
            nBytes = readable();
	    while ((nBytes == 0) && (!eod)) {
		try {
		    wait(200);
		} 
		catch (InterruptedException ie) {
		    throw new InterruptedIOException(ie.toString());
		}
		nBytes = readable();
	    }	    

	    handleError();
	    if (eod && (nBytes == 0)) {
		throw new IOException("No more data in BucketSequence.");
	    }

	    if (nBytes > len) {
		nBytes = len;
	    }

	    int nReallyRead = in.read(buf, offset, nBytes);
	    if (nReallyRead < 1) {
		throw new RuntimeException("read failed.");
	    }
	    pos += nReallyRead;
	    len -= nReallyRead;
	}
	return written;
    }

    private synchronized int read() throws java.io.IOException {
	// REDFLAG: untested code path
	if (position() >= length) {
	    //System.err.println("BucketSequence.read(1) -- EOF @ " + position());
	    releaseBucket(currentBucket);
	    return -1; // EOF
	}
	
	int nBytes = readable();
	while((nBytes == 0) && (!eod)) {
	    try {
		wait(200);
	    }
	    catch (InterruptedException ie) {
		throw new InterruptedIOException(ie.toString());
	    }
	    nBytes = readable();
	}

	if (eod && (nBytes == 0)) {
	    throw new IOException("No more data in BucketSequence.");
	}
	handleError();

	pos++;
	return in.read();
    }

    ////////////////////////////////////////////////////////////
    // InputStream filter implementation.
    // 
    class BucketSequenceInputStream extends java.io.FilterInputStream {
	BucketSequenceInputStream() {
	    super(null);
	}

	public int read() throws IOException {
	    handleError();
	    return BucketSequence.this.read();
	}

	public int read(byte[] buf) throws IOException {
	    handleError();
	    return BucketSequence.this.read(buf, 0, buf.length);
	}

	public int read(byte[] buf, int offset, int len) throws IOException {
	    handleError();
	    return BucketSequence.this.read(buf, offset, len);
	}

	public long skip(long skip) throws IOException {
	    long skipped = 0;
	    handleError();
	    byte[] buffer = new byte[1024];
	    while (skip > 0) {
		long nBytes = skip;
		if (skip > 1024) {
		    nBytes = 1024;
		}
		int nSkipped = BucketSequence.this.read(buffer, 0, (int)nBytes);
		if (nSkipped < 0) {
                    // REDFLAG: hmmm.... BUG?
                    // What happens the next time skip() is called?
		    break;
		}
		skipped += nSkipped;
		skip -= nSkipped;
	    }
	    return skipped;
	}

	public int available() throws IOException {
	    handleError();
	    return BucketSequence.this.readable();
	}

	public void close() {
	    closed = true;
	    release();
	}

	public synchronized void mark(int value) {}
	public synchronized void reset() {}
	public boolean markSupported() { return false; }

	private final void handleError() throws IOException {
	    synchronized (BucketSequence.this) {
		BucketSequence.this.handleError();
		if (closed) {
		    throw new IOException("InputStream closed!");
		}
	    }
	}
    } 
    
    ////////////////////////////////////////////////////////////
    BucketFactory bucketFactory = null;

    Hashtable buckets = new Hashtable();

    int length = -1;

    int startBucketOffset = 0;
    int startBucketNum = 0;
    int endBucketNum = -1;
    boolean keepBuckets = false;

    int nTotalBytesRead = 0;
    int currentBucket = -1; 
    int currentBucketLength = 0;

    // position in the current bucket [0, currentBucketLength - 1]
    int pos = -1;
    boolean eod = false;
    boolean closed = false;

    // stream for the current bucket.
    InputStream in = null;

    String errMsg = null;
    int errBlock = -1;

    // Contiguous input stream spanning
    // all buckets.
    InputStream inputStream = null;
}








