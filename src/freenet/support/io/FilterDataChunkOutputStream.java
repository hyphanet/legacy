package freenet.support.io;

import java.io.*;

import freenet.Core;
import freenet.support.Logger;
import freenet.support.io.DataChunkOutputStream;

public class FilterDataChunkOutputStream extends DataChunkOutputStream {
    protected OutputStream out;
    public FilterDataChunkOutputStream(OutputStream out,
				       long length, long chunkSize) {
	super(length, chunkSize);
	this.out = out;
    }
    
    protected void sendChunk(int chunkSize) throws IOException {
	out.write(buffer, 0, chunkSize);
    }
    
    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            Core.logger.log(this, "Caught "+e+" closing "+out+" in "+this,
                    Logger.ERROR);
        }
    }
}
