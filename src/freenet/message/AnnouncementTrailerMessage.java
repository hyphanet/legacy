/*
 * Created on Aug 5, 2004
 */
package freenet.message;

import java.io.IOException;

import freenet.BaseConnectionHandler;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.support.KeyList;


/**
 * @author amphibian
 * 
 * Base class for Announcement messages with KeyList trailers.
 */
public abstract class AnnouncementTrailerMessage extends PossTrailerNodeMessage {

    private KeyList keys;
    
    public AnnouncementTrailerMessage(long id, KeyList keys) {
        super(id, null);
        this.keys = keys;
    }
    
    public AnnouncementTrailerMessage(BaseConnectionHandler ch, RawMessage raw) 
        throws InvalidMessageException {
        
        super(ch, raw);
        keys = new KeyList();
        in = (raw.trailingFieldLength == 0 ? null : raw.trailingFieldStream);
    }
    
    public RawMessage toRawMessage(Presentation p, PeerHandler ph) {
        RawMessage raw = super.toRawMessage(p,ph);
        //raw.messageType = messageName;   
        raw.trailingFieldName = getTrailingFieldName();
        raw.trailingFieldLength = keys.streamLength();
        //if (raw.trailingFieldLength != 0)
        //    raw.trailingFieldStream = keys.getStream();

        return raw;
    }
    
    protected abstract String getTrailingFieldName();

    public boolean hasTrailer() {
        return keys.streamLength() != 0;
    }
    
    public long trailerLength() {
        return keys.streamLength();
    }
    
    public void setTrailerLength(long length) {
        throw new UnsupportedOperationException();
    }
    /** Read up to limit values from the stream into the list.
     */
   public void readKeys(int limit) throws IOException {
       try {
           KeyList.readKeyList(in, keys, limit);
       }
       finally {
           try { in.close(); }
           catch (IOException e) {}
       }
   }

   public KeyList getKeys() {
       return keys;
   }

   public void setKeys(KeyList keys) {
       this.keys = keys;
   }

}
