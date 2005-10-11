package freenet.client;

import freenet.support.Bucket;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.SegmentHeader;

/** Represents a request to make SplitFile metadata
 *  from a list of SegmentHeaders and BlockMaps.
 *  @author giannij
 */
public class MakeMetadataRequest extends Request {

    // Package scope on purpose.
    SegmentHeader[] headers;
    BlockMap[] maps;
    Bucket metaData;
    String description;
    String mimeType;
    String checksum;

    public MakeMetadataRequest(SegmentHeader[] headers, BlockMap[] maps, 
			       Bucket metaData, String description, String mimeType) {
        this.headers = headers;
        this.maps = maps;
        this.metaData = metaData;
        this.description = description;
        this.mimeType = mimeType;
    }
}


