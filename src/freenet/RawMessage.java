/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU General Public Licence (GPL) 
  version 2.  See http://www.gnu.org/ for further details of the GPL.
 */

/**
 * RawMessages are a generalized form of message that can be created
 * and written to the protocol. They are are created by calling the 
 * a protocol's readMessage or newMessage methods.
 *
 * RawMessages contains the name of the message, it's source, any
 * connection settings, a set of the fields in the message, and 
 * the stream of trailing data.
 *
 * @author <A HREF="mailto:I.Clarke@strs.co.uk">Ian Clarke</A>
 * @author <A HREF="mailto:blanu@uts.cc.utexas.edu">Brandon Wiley</A>
 **/
package freenet;

import java.io.IOException;
import java.io.OutputStream;

import freenet.support.io.DiscontinueInputStream;

public abstract class RawMessage {

    // Public Fields
    /** The type of the message **/
    public String messageType;
    /** The set of fields **/
    public FieldSet fs;
    /** The length of the trailing data **/
    public long trailingFieldLength;
    /** The name of the trailing data **/
    public String trailingFieldName;
    /** The mux id of the trailing data **/
    public int trailingFieldMuxID;
    /** The trailing data stream **/
    public DiscontinueInputStream trailingFieldStream;
    /** Whether to close after this message **/
    public boolean close;
    /** Whether to keep the connection alive as long as possible **/
    public boolean sustain;

    // Constructors
    /**
     *
     * @param messageType   The name of the message type.
     * @param close         Whether to close the connection after 
     *                      receiving or sending this message.
     * @param sustain       Whether to sustain the connection.
     * @param fs            A set of message specific fields.
     * @param trailingLength The length of the trailing data, or 0 if there 
     *                       is no trailing data
     * @param trailingName   The name of the trailing field, or null if there
     *                       is no trailing data
     * @param trailing       An inputstream containing the trailing data,
     *                       in a format that can be copied to destination
     *                       of this message.
     **/
    protected RawMessage(String messageType, boolean close, boolean sustain, 
                         FieldSet fs, long trailingLength, 
                         String trailingName, DiscontinueInputStream trailing, 
						 int trailerMuxID) {
        this.messageType = messageType;
        this.close = close;
        this.sustain = sustain;
        this.fs = new FieldSet(fs);
        this.trailingFieldLength = trailingLength;
        this.trailingFieldName = trailingName;
        this.trailingFieldStream = trailing;
        this.trailingFieldMuxID = trailerMuxID;
    }

    protected RawMessage() {
        // Do nothing
    }
    
    // Public Methods

    public abstract void writeMessage(OutputStream out) throws IOException;
}

