package freenet.message.client;

import freenet.Core;
import freenet.support.Logger;

/**
 * This is the FCP DataChunk message.
 */
public class DataChunk extends ClientMessage {

    public static final String messageName = "DataChunk";
    
    public DataChunk(long id, long chunkSize, boolean close) {
        super(id);
        dataLength = chunkSize;
        this.close = close;
	Core.logger.log(this, "Creating "+this+" ("+Long.toHexString(id)+
			","+chunkSize+","+close+")", new Exception("debug"),
			Logger.MINOR);
    }

    public String getMessageName() {
        return messageName;
    }
}
