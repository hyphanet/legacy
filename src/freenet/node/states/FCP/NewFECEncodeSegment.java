package freenet.node.states.FCP;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.Core;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.SendFailedException;
import freenet.TrailerWriter;
import freenet.TrailerWriterOutputStream;
import freenet.message.client.BlocksEncoded;
import freenet.message.client.DataChunk;
import freenet.message.client.Failed;
import freenet.message.client.FEC.FECEncodeSegment;
import freenet.message.client.FEC.SegmentHeader;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.io.ReadInputStream;

public class NewFECEncodeSegment extends NewClientRequest {

    public NewFECEncodeSegment(long id, PeerHandler source) {
        super(id, source);
    }
    
    public final String getName() {
        return "New FEC Encode Segment";
    }

    // REDFLAG: does a BadStateException kill the chain?
    // REDFLAG: client error msgs?
    public State received(Node n, MessageObject mo) throws BadStateException {
        if (mo instanceof FECEncodeSegment) {

            FECEncodeSegment femo = ((FECEncodeSegment)mo);

            // Careful, can be null.
            int[] requestList = femo.requestedList();

            long tlen = femo.getDataLength();
            long mlen = femo.getMetadataLength();
            long dlen = tlen - mlen;

            if (mlen < 1 || dlen < 1) {
                // REDFLAG
                return null;
            }

            BucketFactory bf = Node.fecTools.getBucketFactory();

            Bucket[] dataBlocks = null;
            Bucket[] checkBlocks = null;
            InputStream in = null;
            try {
                in = femo.getDataStream();

                // Read SegmentHeader off of metadata stream
                //
                // Keep an explict ref to the ReadInputStream so
                // it doesn't get garbage collected and cause 
                // in to be closed *before* we exit this scope.
                // REDFLAG: investigate, unnesc. paranoia?
                ReadInputStream rin = new ReadInputStream(in);
                
                // Hmmm... risky. Can we start reading data
                // in the wrong place if the client sends us
                // a messed up segment header? I don't want
                // to copy to a bucket if I don't have to though
                SegmentHeader header = new SegmentHeader(id, rin);

                // Read data blocks
                dataBlocks = copyFully(in,
                                       dlen, 
                                       header.getBlockCount(),
                                       bf, true);

                // Encode
                checkBlocks = Node.fecTools.encodeSegment(header, 
                                                       requestList, // null ok 
                                                       dataBlocks);

                // Write success msg
                sendMessage(new BlocksEncoded(id, checkBlocks.length, header.getCheckBlockSize()));
                
                // Write data
                sendDataChunks(source, id, checkBlocks, 
                               header.getCheckBlockSize() * checkBlocks.length, 
                               16384);

            }
            catch (Exception e) {
                // REDFLAG: remove
                e.printStackTrace();
                sendMessage(new Failed( id, e.getMessage()));
            }
            finally {
                // Make sure we don't leak buckets.
            	try {
                BucketTools.freeBuckets(bf, dataBlocks);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"WARNING: Cannot free data block buckets",
					e,
					Logger.ERROR);
			}

			try {
                BucketTools.freeBuckets(bf, checkBlocks);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"WARNING: Cannot free check block buckets",
					e,
					Logger.ERROR);
			}

            }

            // Our work is done, so  stop the chain.
            return null;
        }
        else {
            throw new BadStateException("expecting FECEncodeSegment or SegmentHeader");
        }
    }

    // Note: I wrote this from scratch instead of just calling the base class
    //       stream copyFully because it creates a new DataInputStream wrapper on
    //       every call. REDFLAG: revisit.
    //
    // REDFLAG: test!
    // REDFLAG: move
    protected static Bucket[] copyFully(InputStream in, long length, int nBlocks, 
					BucketFactory bf, boolean alwaysCloseIn) 
	throws IOException {
	
        if ((length == 0) || (nBlocks == 0)) {
            throw new IllegalArgumentException("assertion failure: (length == 0) || (nBlocks == 0) " +
                                               " length = " + length + " nBlocks= " + nBlocks);
        }

        
        if ((length % nBlocks) != 0) {
            throw new IllegalArgumentException("assertion failure: (length % nBlocks) != 0" +
                                               " length = " + length + " nBlocks= " + nBlocks);
        }

        int size = (int)(length / nBlocks);
        if (size == 0) {
            throw new IllegalArgumentException("assertion failure: size == 0 " +
                                               " length = " + length + " nBlocks= " + nBlocks);
        }


        Bucket[] ret = null;
        OutputStream out = null;
        int iCurrent = 0;
        int iPrev = -1;
        long count = 0;
        boolean groovy = false;
        try {
            ret = new Bucket[nBlocks];
            // REDFLAG: just copied. Why DataInputStream?
            DataInputStream din = new DataInputStream(in);
            byte[] buf = new byte[Core.blockSize];
	    Core.logger.log(NewFECEncodeSegment.class, "Reading "+length+" bytes", Logger.DEBUG);
            while (count < length ) {
		Core.logger.log(NewFECEncodeSegment.class, "Read "+count+" of "+length, 
				Logger.DEBUG);
                int n = (int) (length < buf.length ? length : buf.length);
		
		try{
                din.readFully(buf, 0, n);
		}catch (EOFException e){
			e.printStackTrace();
			Core.logger.log(NewFECEncodeSegment.class,
					"Caught EOFException in din.readFully: "+e,
					e, Logger.ERROR);
			throw e;
		}

                long offset = 0; // assignment redundant
                while (n > 0) {
                    iCurrent = (int) (count / size);
                    if (iCurrent != iPrev) {
                        // Set up the new stream.
                        if (out != null) {
                            out.close();
                        }
                        ret[iCurrent] = bf.makeBucket(size);
                        out = ret[iCurrent].getOutputStream();
                        iPrev = iCurrent;
                        offset = 0;
                    }

                    int nCurrent = (int) (((iCurrent + 1) * size) - count);
                    if (nCurrent >= n) {
                        nCurrent = n; 
                    }
                    out.write(buf, (int)offset, nCurrent);
                    
                    // Simplify?
                    // grrrr... I hate this kind of coding.
                    count += nCurrent;
                    offset += nCurrent;
                    n -= nCurrent;
                }
            }
            groovy = true;
        } catch (Throwable t) {
	    Core.logger.log(NewFECEncodeSegment.class, "Caught "+t, t, Logger.ERROR);
	} finally {
	    Core.logger.log(NewFECEncodeSegment.class, "Leaving copyFully, groovy="+groovy, 
			    Logger.DEBUG);
            try { if (alwaysCloseIn && in != null) in.close(); } catch (Exception e) { 
		Core.logger.log(NewFECEncodeSegment.class, "Exception "+e+" closing "+in, 
				Logger.ERROR);
		e.printStackTrace();
	    }
            try { if (out != null) out.close(); } catch (Exception e) {
		Core.logger.log(NewFECEncodeSegment.class, "Exception "+e+" closing "+out, 
				Logger.ERROR);
		e.printStackTrace();
	    }
            if (!groovy) {
                BucketTools.freeBuckets(bf, ret);
            }
        }
        return ret;
    }

    // REDFLAG: test!
    // REDFLAG: move
    protected static void sendDataChunks(PeerHandler source, long id,
                                         Bucket[] data, long length, int chunkSize)
        throws IOException {

        if ((length < 1) || (chunkSize < 1)) {
            // REDFLAG: remove? part of a bug hunt
            throw new IllegalArgumentException("length=" + length + " chunkSize= " + chunkSize);
        } 

        InputStream in = null;
        byte[] buf = new byte[chunkSize];
        int index = 0;
        int offset = 0;
        long bucketOffset = 0;
        while (length > 0) {
            // Open stream if nesc.
            if (in == null) {
                in = data[index].getInputStream();
                bucketOffset = 0;
            }
            
            // 1. bytes available in buffer
            // 2. bytes available in Bucket
            // 3. bump down for eof
            long nBytes = buf.length - offset;
            if (nBytes > data[index].size() - bucketOffset) {
                nBytes = data[index].size() - bucketOffset;
            }
            if (nBytes > length) {
                nBytes = length;
            }

            // Read data
            int nRead = in.read(buf, offset, (int)nBytes);
            
            // Close stream if nesc.
            bucketOffset += nRead;
            if (bucketOffset == data[index].size() ) {
                in.close();
                in = null;
                index++;
            }

            // update counting
            offset+= nRead;
            length -= nRead;

            // Write chunk
            if ((length == 0) ||
                (offset == chunkSize)) {
                sendChunk(source, id, buf, offset, length == 0);
                offset = 0;
            }
        }
    }

    protected  static void sendChunk(PeerHandler source, long id,
                                     byte[] buffer, long size, boolean close) 
        throws IOException {

        OutputStream out;
        try {
            // hmmm... correct?
            TrailerWriter tw = source.sendMessage( new DataChunk(id, size, close), 600*1000);
	    if(tw == null) throw new IOException("wtf?");
	    out = new TrailerWriterOutputStream(tw);
        } catch (SendFailedException e) {
            throw new IOException(e.getMessage());
        }
        out.write(buffer, 0, (int)size);
        out.flush();
        out.close();
    }
}

