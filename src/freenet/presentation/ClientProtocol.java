package freenet.presentation;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import freenet.BaseConnectionHandler;
import freenet.ConnectionHandler;
import freenet.FieldSet;
import freenet.OpenConnectionManager;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.Ticker;
import freenet.node.Node;
import freenet.session.Link;
import freenet.support.io.CountedInputStream;
import freenet.support.io.DiscontinueInputStream;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.ThrottledAsyncTCPWriteManager;

public final class ClientProtocol extends Presentation {

    public final static int DESIGNATOR = 0x0002;

    public int designatorNum() {
        return DESIGNATOR;
    }
    
    protected int readBytes = -1;
    
    public int readBytes() {
	return readBytes;
    }
    
    public RawMessage readMessage(byte[] buf, int start, int maxSize) {
	readBytes = -1;
	// FIXME: quick and very dirty
	// REDFLAG: but also pretty slow
	FCPRawMessage m = null;
	CountedInputStream in = 
	    new CountedInputStream(new ByteArrayInputStream(buf, start, maxSize));
	try {
	    try {
		m = new FCPRawMessage(in);
	    } catch (EOFException e) {
		return null;
	    }
	    return m;
	} finally {
	    readBytes = (int)(in.count()); // it will NOT be out of range for an int, this is a message not trailing fields
	}
    }
    
    /**
     * Creates a new RawMessage of a connection by reading this presentation. This
     * method locks until an entire message has been read (excluding trailing)
     * @return      A new raw message
     */
    public RawMessage readMessage(InputStream in) 
        throws EOFException{

        return new FCPRawMessage(in);
    }


    /** 
     * Creates a new RawMessage of a given type that uses this presentation
     * @param messageType   The name of the message type
     * @param close         Whether to keep alive the connection after 
     *                      receiving or sending this message.
     * @param sustain       Whether to sustain the connection.
     * @param fs            A set of message specific fields.
     * @param trailingLength The length of the trailing data, or 0 if there 
     *                       is no trailing data
     * @param trailingName   The name of the trailing field, or null if there
     *                       is no trailing data
     * @param data           An inputstream containing the trailing data,
     *                       straight off the DataStore (decrypted).
     * @param muxID ignored, see superclass for derivation, FCP doesn't mux - FIXME
     * @return     A new raw message
     **/
    public  RawMessage newMessage(String messageType, boolean close, 
                                  boolean sustain, FieldSet fs, 
                                  long trailingLength, 
                                  String trailingName, 
                                  DiscontinueInputStream data, int muxID) {
        return new FCPRawMessage(
            messageType, close, fs, trailingLength, trailingName, data
        );
    }

    public int exptMessageSize() {
        return 5000;
    }

	/* (non-Javadoc)
	 * @see freenet.Presentation#createConnectionHandler(freenet.OpenConnectionManager, freenet.session.Link, freenet.Ticker, int, int, boolean)
	 */
	public BaseConnectionHandler createConnectionHandler(OpenConnectionManager manager, Node n, Link l, Ticker ticker, int maxInvalid, int maxPadding, boolean outbound,ThrottledAsyncTCPReadManager rsl,ThrottledAsyncTCPWriteManager wsl) 
		throws IOException {
		return new ConnectionHandler(manager, n, this, l, ticker, maxInvalid, outbound,rsl,wsl);
	}
}
