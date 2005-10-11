package freenet.fs.dir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface Buffer {

    /**
     * length of buffer, buffer may not be filled up yet
     */
    long length();
    
    /**
     * Current length of buffer - number of bytes IN CACHE
     */
    long realLength();
    
    boolean failed();

    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;
    
	//Updates the buffers timestamp
	void touch();
	//Updates the buffers timestamp if deemed neccesary. Prevents excessive updates.
	public void touchThrottled();

	void commit();

	void release();
}
