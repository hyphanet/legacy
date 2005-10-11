package freenet.client;

import freenet.message.client.FEC.SegmentHeader;
import freenet.support.Bucket;
import freenet.support.BucketTools;

/** Represents a request to FEC encode a segment
 *  of a SplitFile. 
 * @author giannij
 */
public class EncodeSegmentRequest extends Request {

    // Package scope on purpose.
    SegmentHeader header;
    Bucket[] data;
    Bucket[] checks;
    
    int checkIndices[];

    // REQUIRES: checks.length == checkIndices.length
    // i.e. You don't have to request all check blocks,
    //      but you must have a bucket slot for the ones
    //      you do request.
    public EncodeSegmentRequest(SegmentHeader header,
                                Bucket[] data, Bucket[] checks, int checkIndices[]) {
        
        this.header = header;
        this.data = data;
        this.checks = checks;
        this.checkIndices = checkIndices;
        // Fail early and predictably.
        if ((data == null) || (checks == null) || 
            (BucketTools.nullIndices(data).length != 0) ||
            (BucketTools.nullIndices(checks).length != 0) ||
            // Check indices can be null. That means "all blocks".
            ((checkIndices != null) && (checks.length != checkIndices.length))) {
            throw new IllegalArgumentException("Can't make encode segment request " +
                                               "because of bad arguments.");
        }
   }
}


