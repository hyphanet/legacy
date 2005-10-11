package freenet.client;

import freenet.message.client.FEC.SegmentHeader;
import freenet.support.Bucket;

/** Represents a request to FEC decode a segment
 *  of a SplitFile. 
 * @author giannij
 */
public class DecodeSegmentRequest extends Request {

    // Package scope on purpose.
    SegmentHeader header;
    Bucket[] data;
    Bucket[] checks;
    Bucket[] decoded;
    
    int dataIndices[];
    int checkIndices[];
    int requestedIndices[];
        
    // NOTE: You can also request missing check blocks
    //       by passing indices between
    //       header.getBlockCount() and header.getBlockCount() + header.getBlockCount() - 1
    //
    //       This was added to support clients that re-insert unretrievable blocks.

    // NOTE: The decoded data blocks are copied into the
    //       Buckets in the decoded[] array.  i.e. You must
    //       pass in Buckets to fill.
    public DecodeSegmentRequest(SegmentHeader header,
                                Bucket[] data, Bucket[] checks, Bucket[] decoded,
                                int[] dataIndices, int[] checkIndices, int requestedIndices[]) {
        
        this.header = header;
        this.data = data;
        this.checks = checks;
        this.decoded = decoded;
        this.dataIndices = dataIndices;
        this.checkIndices = checkIndices;
        this.requestedIndices = requestedIndices;
    }
}


