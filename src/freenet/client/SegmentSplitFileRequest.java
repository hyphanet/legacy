package freenet.client;

import freenet.client.metadata.SplitFile;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.SegmentHeader;

/** Represents a request to segment SplitFile metadata.
  * @author giannij
  */
public class SegmentSplitFileRequest extends Request {

    // Package scope on purpose.
    SegmentHeader[] headers = new SegmentHeader[0];
    BlockMap[] maps = new BlockMap[0];
    SplitFile sf = null;

    public SegmentSplitFileRequest(SplitFile sf) {
        this.sf = sf;
    }

    public final int segments() { return headers.length; }
    public final SegmentHeader[] getHeaders() { return headers; }
    public final BlockMap[] getMaps() { return maps; }

}


