package freenet.support.io;

import java.io.*;
import freenet.support.Bucket;

// REDFLAG: Test this bad boy.

/**
 * An adapter which presents an array of
 * buckets as a single contiguous InputStream.
 * <p>
 *
 * This code is distributed under the GNU Public Licence (GPL)
 * version 2.  See http://www.gnu.org/ for further details of the GPL.
 * <p>
 * @author giannij
 */
public class BucketInputStream extends InputStream {

    long pos = 0;
    long len = -1;
    int nBytesLeftInBucket = 0;

    Bucket[] buckets = null;
    InputStream currentStream = null;
    int index = -1; // nextBucket() increments

    public BucketInputStream(Bucket[] buckets, long len)
        throws IOException {
        this.buckets = buckets;
        this.len = len;

        // Fail early.
        long count = 0;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] == null) {
                throw new IllegalArgumentException("buckets[" + i + "] is null");
            }
            if (buckets[i].size() < 1) {
                throw new IllegalArgumentException("buckets[" + i + "] is empty.");
            }
            count += buckets[i].size();
            //System.err.println("BucketInputStream -- bucket[" + i + "]: " + buckets[i].size());
        }

        if (count < len) {
            throw new IllegalArgumentException("Buckets don't contain enough data.");
        }

        nextBucket();
    }

    public int available() {
        return (int)(len - pos);
    }

    public void close() throws IOException { 
        if (currentStream != null) {
            currentStream.close();
            currentStream = null;
        }
    }

    public int read() throws IOException {
	if (pos >= len) {
	    return -1; // EOF
	}

        if (nBytesLeftInBucket < 1) {
            nextBucket();
        }
        
        nBytesLeftInBucket--;
        pos++;

        int ret = currentStream.read();
        if (ret == -1) {
            throw new RuntimeException("Assertion Failure: unexpected eof");
        }
        return ret;
    } 

    public int read(byte[] b, int off, int n) throws IOException {
	if (pos >= len) {
	    return -1; // EOF
	}

	if (pos + n > len) {
	    n = (int)(len - pos);
	}

        int localOffset = 0;
        while (n > 0) {
            if (nBytesLeftInBucket < 1) {
                nextBucket();
            }

            int nBytes = nBytesLeftInBucket;
            if (nBytes > n) {
                nBytes = n;
            }

//              System.err.println("BucketInputStream.read -- pos: " + pos);
//              System.err.println("BucketInputStream.read -- len: " + len);
//              System.err.println("BucketInputStream.read -- index: " + index);
//              System.err.println("BucketInputStream.read -- n: " + n);
//              System.err.println("BucketInputStream.read -- b: " + b.length);
//              System.err.println("BucketInputStream.read -- off: " + off);
//              System.err.println("BucketInputStream.read -- localOffset: " + localOffset);
//              System.err.println("BucketInputStream.read -- nBytes: " + nBytes);
            // REDFLAG: REMOVE
            int nBytesRead = currentStream.read(b, off + localOffset, nBytes);
            if (nBytesRead < 1) {
                //                  System.err.println("BucketInputStream.read -- pos: " + pos);
//                  System.err.println("BucketInputStream.read -- len: " + len);
//                  System.err.println("BucketInputStream.read -- index: " + index);
//                  System.err.println("BucketInputStream.read -- n: " + n);
//                  System.err.println("BucketInputStream.read -- b: " + b.length);
//                  System.err.println("BucketInputStream.read -- off: " + off);
//                  System.err.println("BucketInputStream.read -- localOffset: " + localOffset);
//                  System.err.println("BucketInputStream.read -- nBytes: " + nBytes);
                // REDFLAG: remove
                throw new IOException("Couldn't read enough bytes out of bucket.");

            }
            nBytesLeftInBucket -= nBytesRead;
            pos+= nBytesRead;
            localOffset += nBytesRead;
            n -= nBytesRead;
        }

        return localOffset;
    }
    
    private void nextBucket() throws IOException {
        if (nBytesLeftInBucket != 0) {
            throw new RuntimeException("Assertion Failed: nBytesLeftInBucket != 0"); 
        }
        if (index == buckets.length - 1) {
            throw new RuntimeException("Assertion Failed: index == buckets.length - 1"); 
        }

        if (currentStream != null) {
            currentStream.close();
            currentStream = null;
        }        
        
        index++;
        currentStream = buckets[index].getInputStream();
        nBytesLeftInBucket = (int)buckets[index].size();
        //System.err.println("BucketInputStream.nextBucket -- advanced to: " + index +
        //                   " of " + buckets.length + " " + buckets[index] + " len: " + nBytesLeftInBucket);
    }
}







