package freenet.client;

import freenet.message.client.FEC.SegmentHeader;

/** Represents a request to segment a file
 *  for insertion.
 *  @author giannij
 */
public class SegmentFileRequest extends Request {

    // Package scope on purpose.
    SegmentHeader[] headers = new SegmentHeader[0];
    String algoName = null;
    long length = -1;

    public SegmentFileRequest(String algoName, long length) {
        this.algoName = algoName;
        this.length = length;
    }

    public final int segments() { return headers.length; }
    public final SegmentHeader[] getHeaders() { return headers; }
}


