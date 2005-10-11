package freenet;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import freenet.node.Node;
import freenet.session.Link;
import freenet.support.io.DiscontinueInputStream;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.ThrottledAsyncTCPWriteManager;

/**
 * Implementations of Presentation are used to handle connections
 * with a certain message protocol. It is used used to initialize
 * new connections, read messages off the connection into the RawMessage 
 * format, and creating new RawMessages for writing to a stream with
 * this protocol.
 **/

public abstract class Presentation {

    /**
     * These are control byte values used in the streams of all presentations 
     **/
    public static final int
        CB_OK        = 0x00,
        CB_RESTARTED = 0x01,  // parallels QueryRestarted
        CB_ABORTED   = 0x02;  // parallels QueryAborted

    public static final int LAST_EXTERNAL_CB = CB_ABORTED;

    //Values over 128 are internal
    public static final int 
        CB_BAD_DATA       = 0x81,
        CB_SEND_CONN_DIED = 0x82,
        CB_RECV_CONN_DIED = 0x83,
        /** Source sent bad Storables, or bad length, etc */
        CB_BAD_KEY        = 0x84,
        /** Internal error; our fault */
        CB_CACHE_FAILED   = 0x85,
        /** Insert request cancelled */
        CB_CANCELLED      = 0x86,
        CB_RECEIVER_KILLED = 0x87,
        CB_SEND_TIMEOUT = 0x88;
    
    
    public static final String getCBname(int cb) {
        switch (cb) {
            case CB_OK:                 return "CB_OK";
            case CB_RESTARTED:          return "CB_RESTARTED";
            case CB_ABORTED:            return "CB_ABORTED";
            case CB_BAD_DATA:           return "CB_BAD_DATA";
            case CB_SEND_CONN_DIED:     return "CB_SEND_CONN_DIED"; 
            case CB_RECV_CONN_DIED:     return "CB_RECV_CONN_DIED";
            case CB_BAD_KEY:            return "CB_BAD_KEY";
            case CB_CACHE_FAILED:       return "CB_CACHE_FAILED";
            case CB_CANCELLED:          return "CB_CANCELLED";
            case CB_RECEIVER_KILLED:	return "CB_RECEIVER_KILLED";
            case CB_SEND_TIMEOUT:		return "CB_SEND_TIMEOUT";
            default:                    return "Unknown control byte";
        }
    }

    public static final String getCBdescription(int cb) {
        return "0x"+Integer.toHexString(cb)+" ("+getCBname(cb)+")";
    }

        
    /**
     * Returns the designator number of this Presentation type.
     */
    public abstract int designatorNum();

    /**
     * Creates a new RawMessage of a stream by reading this presentation. This
     * method locks until an entire message has been read (excluding trailing)
     * REDFLAG: Obsolete with muxing? What about FCP? In any case mux impls
     * should return null..
     * @param in    The stream to read from.
     * @return      A new raw message.
     **/
    public abstract RawMessage readMessage(InputStream in) throws InvalidMessageException, EOFException;

    
    /**
     * a non-blocking equivalent of readMessage.  It returns
     * null if a message could not be parsed. This could be
     * because there is not enough data, in which case we will
     * be called again with more. No internal buffering is done
     * in the Presentation class - if we are called with a
     * buffer, and return null, and then later are called again,
     * the buffer next time will include the buffer this time.
     * @param buf buffer to read from
     * @param maxSize maximum number of bytes to read from it
     */
    public abstract RawMessage readMessage(byte[] buf, int start, int maxSize) 
	throws InvalidMessageException;
    // I changed it to byte[], because the rest of the Presentation
    // layer is currently set up to eat InputStreams and convert
    // them to char's itself.
    
    /**
     * @return the number of bytes read to parse the previous message
     */
    public abstract int readBytes();
    
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
     * @param muxID The multiplexing ID for the trailer, if available.
     * @return     A new raw message
     **/
    public abstract RawMessage newMessage(String messageType, boolean close, 
                                          boolean sustain, FieldSet fs, 
                                          long trailingLength, 
                                          String trailingName, 
                                          DiscontinueInputStream data,
										  int muxID);

    /**
     * An estimation of the avergae size of a message (not including
     * trailing) encoded in this presenation in bytes
     */
    public abstract int exptMessageSize();

    /**
     * Subclasses can override this to put a default Message on the stack
     * if the RawMessage is not recognized by the MessageHandler.
     */
    public Message getDefaultMessage() {
        return null;
    }

    /**
     * Subclasses can override this to put have a message get sent when 
     * a Connection has timed out and is ready to close. If a message is
     * returned from this, it will be sent as a close token, and the 
     * connection will not be forced close, otherwise it will.
     */
    public Message getCloseMessage() {
        return null;
    }

    /**
     * Subclasses that support setting connections to be sustained can override
     * this to return a message that is sent down a Connection and sets
     * Sustain to true.
     */
    public Message getSustainMessage() {
        return null;
    }

	/**
	 * Create a BaseConnectionHandler suitable for a connection using 
	 * this Presentation. Originally, all Presentations used ConnectionHandler,
	 * but with the implementation of multiplexing, there is more than one,
	 * and in future there may be many more implementations of it.
	 * @param manager the OpenConnectionManager that handles all connections.
	 * @param l the Link over which the connection runs
	 * @param ticker the Ticker on which to schedule new FNP messages
	 * @param maxInvalid the maximum number of invalid messages before
	 * we close the connection. May be ignored.
	 * @param maxPadding the maximum number of padding bytes before we complain.
	 * May be ignored.
	 * @param outbound whether the connection is outbound i.e. whether
	 * we originated the connection.
	 * @return a BaseConnectionHandler for the new link.
	 * @throws IOException if we have a problem setting up the new connection,
	 * in which case the link is dead. 
	 */
	public abstract BaseConnectionHandler createConnectionHandler(OpenConnectionManager manager, Node n,
														 Link l, Ticker ticker, int maxInvalid, int maxPadding, 
														 boolean outbound,ThrottledAsyncTCPReadManager rsl, ThrottledAsyncTCPWriteManager wsl) throws IOException;

    /**
     * Adjust a control byte received from an external data source.
     * CB_ABORTED and CB_RESTARTED are passed through as is.
     * Anything else becomes CB_BAD_DATA.
     */
    public static int fixReceivedFailureCode(int result) {
        if (result == CB_OK || result > LAST_EXTERNAL_CB) {
			result = Presentation.CB_BAD_DATA;
			// Result is ONLY CB_OK, CB_RESTARTED or CB_ABORTED.
			// All other values are internal and therefore >= 0x80.
			// And CB_OK is invalid given that we failed.
		}
        
        return result;
    }

}


