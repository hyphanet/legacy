package freenet.message;
import java.io.InputStream;

import freenet.BaseConnectionHandler;
import freenet.Core;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.node.State;
import freenet.node.states.FNP.NewVoid;
import freenet.support.io.ZeroInputStream;

/**
 * Void messages are empty and meaningless.
 */

public class VoidMessage extends PossTrailerNodeMessage {

    public static final String messageName = "Void";

    private long dataLength;
    private boolean close;
    private boolean sustain;

    public VoidMessage(long id, boolean close, FieldSet otherFields) {
        super(id, otherFields);
        this.close = close;
        this.sustain = false;
    }
    
    public VoidMessage(long id, boolean close, boolean sustain, 
                       FieldSet otherFields) {

        super(id, otherFields);
        this.close = close;
        this.sustain = sustain;
    }

    /**
     * Assigns a NullInputStream of dataLength to the DataStream.
     */
    public VoidMessage(long id, boolean close, boolean sustain, 
                       long dataLength, 
                       FieldSet otherFields) {
        super(id, otherFields, new ZeroInputStream(0, Core.blockSize));
        this.dataLength = dataLength;
        this.close = close;
        this.sustain = sustain;
    }

    public VoidMessage(BaseConnectionHandler source, 
                       RawMessage raw) throws InvalidMessageException {
        super(source, raw);
        if (raw.trailingFieldLength > Core.maxPadding)
            throw new InvalidMessageException("Void messages can only have " +
                                              Core.maxPadding +" long data.");
        dataLength = raw.trailingFieldLength;
        if (dataLength > 0) {
            in = raw.trailingFieldStream;
        }
    }

    public RawMessage toRawMessage(Presentation p, PeerHandler ph) {
        RawMessage r = super.toRawMessage(p,ph);
        r.messageType = messageName;
        r.close = close;
        r.sustain = sustain;
        if (dataLength > 0) {
            r.trailingFieldLength = dataLength;
            r.trailingFieldName   = "Padding";
        }
        return r;
    }
    
    public boolean hasTrailer() {
	return dataLength > 0;
    }
    
    public final long trailerLength() {
	return dataLength;
    }
    
    public String getMessageName() {
        return messageName;
    }

    public State getInitialState() {
        return dataLength == 0 ? null : new NewVoid(id);
    }

    public InputStream getDataStream() {
        return in;
    }

    public long length() {
        return dataLength;
    }

    public void setTrailerLength(long l) {
        this.dataLength = l;
    }

    public int getPriority() {
        if(!close)
            return 2; // less important even than QR!
        else
            return -1; // important but doesn't time out
    }
}
