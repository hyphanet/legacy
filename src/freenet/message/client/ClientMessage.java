package freenet.message.client;

import freenet.*;
import freenet.support.Fields;
import java.io.*;

/**
 * Superclass of FCP messages.
 *
 * @author Oskar
 */


public abstract class ClientMessage extends Message {

    protected long dataLength = 0, metaLength = 0;
    protected InputStream data;

    protected boolean formatError = false;

    public ClientMessage(long id) {
        super(id, null);
        close=true;
    }

    public ClientMessage(long id, FieldSet fs) {
        super(id, fs);
        close=true;
    }

    public ClientMessage(long id, String reason) {
        super(id, new FieldSet());
        close=true;
        if (reason != null) otherFields.put("Reason", reason);
    }

    public ClientMessage(BaseConnectionHandler source, RawMessage raw) {
        this(source, raw, false);
    }

    public ClientMessage(BaseConnectionHandler source, RawMessage raw, boolean getData) {
        super(source, Core.getRandSource().nextLong(), raw);
        try {
            if (getData && "Data".equals(raw.trailingFieldName)
                        && raw.trailingFieldLength != 0) {
                dataLength = raw.trailingFieldLength;
                data       = raw.trailingFieldStream;
                String metas = otherFields.getString("MetadataLength");
                metaLength = metas == null ? 0 : Fields.hexToLong(metas);
                otherFields.remove("MetadataLength");
            }
            else {
                if (!"EndMessage".equals(raw.trailingFieldName)
                    || raw.trailingFieldLength != 0) formatError = true;
                InputStream in = raw.trailingFieldStream;
                if (in != null) in.close();
            }
            // always stop reading but keep sustain on Client messages
            close = true;
            sustain = true;
        }
        catch (IOException e)           {}
        catch (NumberFormatException e) {}
    }

    public String toString() {
        return "freenet.Message: "+Integer.toHexString(super.hashCode())+ 
	    ": " + getMessageName() + " @" + Long.toHexString(id);
    }
    
    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
        RawMessage raw = t.newMessage( getMessageName(), close, true,
                                       otherFields, 0, "EndMessage", null, 0);
        if (dataLength > 0) {
            raw.trailingFieldLength = dataLength;
            raw.trailingFieldName   = "Data";
        }
        return raw;
    }
    
    public boolean hasTrailer() {
	return dataLength > 0;
    }
    
    public final long trailerLength() {
	return getDataLength();
    }
    
    public long getDataLength() {
        return dataLength;
    }

    public long getMetadataLength() {
        return metaLength;
    }

    public InputStream getDataStream() {
        return data;
    }

    /** @return  the name of the message, e.g. "ClientPut"
      */
    public abstract String getMessageName();
    
    public int getPriority() {
        return -10; // doesn't matter usually, but when it does...
    }
}


