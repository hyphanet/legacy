package freenet.presentation;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import freenet.BaseConnectionHandler;
import freenet.ConnectionHandler;
import freenet.Core;
import freenet.FieldSet;
import freenet.Message;
import freenet.OpenConnectionManager;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.Ticker;
import freenet.message.VoidMessage;
import freenet.node.Node;
import freenet.session.Link;
import freenet.support.Logger;
import freenet.support.io.CountedInputStream;
import freenet.support.io.DiscontinueInputStream;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.ThrottledAsyncTCPWriteManager;

/**
 * This is the implementation of the standard protocol used by Freenet
 * to communicate messages. Freenet Protocol (FNP) uses text based messages
 * with a simple header and fieldname=fieldvalue format. FNP utilizes 
 * encryption, currently by doing a DH exchange.
 **/

public class FreenetProtocol extends Presentation {

    public static int DESIGNATOR = 0x0001;
    
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
	FNPRawMessage m = null;
	ByteArrayInputStream bais = 
	    new ByteArrayInputStream(buf, start, maxSize);
	CountedInputStream in = 
	    new CountedInputStream(bais);
	try {
	    try {
		m = new FNPRawMessage(in);
	    } catch (EOFException e) {
		return null;
	    }
	    return m;
	} finally {
	    readBytes = (int)(in.count()); // it will NOT be out of range for an int, this is a message not trailing fields
	    if(in != null) try {
		in.close();
	    } catch (IOException e) {
		Core.logger.log(this, "Closing "+in+": "+e, e, Logger.NORMAL);
	    }
	}
    }
    
    /**
     * Creates a new RawMessage of a connection by reading this presentation. This
     * method locks until an entire message has been read (excluding trailing)
     * @param in    The input stream to read
     * @return      A new raw message
     */
    public RawMessage readMessage(InputStream in) 
	throws EOFException{

	return new FNPRawMessage(in);
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
     *                       straight off the DataStore (decrypted). Apparently never used on initialization...
     * @param muxID The multiplexing trailer ID if necessary.
     * @return     A new raw message
     */
    public  RawMessage newMessage(String messageType, boolean close, 
                                  boolean sustain, FieldSet fs, 
                                  long trailingLength, 
                                  String trailingName, 
                                  DiscontinueInputStream data, int muxID) {
        return new FNPRawMessage(messageType, close, sustain, fs, 
                                 trailingLength, trailingName, data, muxID);
    }


    public int exptMessageSize() {
	return 5000;
    }

    public Message getCloseMessage() {
        return new VoidMessage(Core.getRandSource().nextLong(), true, null);
    }

    public Message getSustainMessage() {
        return new VoidMessage(Core.getRandSource().nextLong(), false, true, null);
    }


	/* (non-Javadoc)
	 * @see freenet.Presentation#createConnectionHandler(freenet.OpenConnectionManager, freenet.session.Link, freenet.Ticker, int, int, boolean)
	 */
	public BaseConnectionHandler createConnectionHandler(OpenConnectionManager manager, Node n, Link l, Ticker ticker, int maxInvalid, int maxPadding, boolean outbound,ThrottledAsyncTCPReadManager rsl, ThrottledAsyncTCPWriteManager wsl) 
		throws IOException {
		return new ConnectionHandler(manager, n, this, l, ticker, maxInvalid, outbound,rsl,wsl);
	}
}
