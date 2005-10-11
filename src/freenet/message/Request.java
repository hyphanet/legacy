package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.FieldSet;
import freenet.Identity;
import freenet.InvalidMessageException;
import freenet.Key;
import freenet.KeyException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.node.Node;
import freenet.support.Fields;

public abstract class Request extends NodeMessage implements HTLMessage {

    public final Key searchKey;
    public int hopsToLive;
    public long stateTime = -1;
    
    protected final Identity requestSource;

    public Request(long id, int htl, Key key, Identity ref) {
        super(id);
        searchKey     = key;
        hopsToLive    = htl;
        requestSource = ref;
    }

    public Request(long id, int htl, Key key,
                   Identity ref, FieldSet otherFields) {
        super(id, otherFields);
        searchKey     = key;
        hopsToLive    = htl;
        requestSource = ref;
    }
   
    public Request(BaseConnectionHandler source, RawMessage raw) throws InvalidMessageException {
		super(source, raw);
		String keyString = otherFields.getString("SearchKey");
		String hopsString = otherFields.getString("HopsToLive");
		if (hopsString == null || hopsString.length()==0)
			throw new InvalidMessageException("Can't find HopsToLive field");
		if (keyString == null || keyString.length()==0)
			throw new InvalidMessageException("Can't find SearchKey field");
		try {
			searchKey = Key.readKey(keyString);
			hopsToLive = Fields.hexToInt(hopsString);
			// there is no reason to verify this.
			requestSource = source.peerIdentity();
			if(requestSource == null)
			    throw new InvalidMessageException("NULL request source on "+this);
		} catch (KeyException k) {
			throw new InvalidMessageException("Failed to load key: " + k);
		} catch (NumberFormatException e) {
			throw new InvalidMessageException("Failed to read number " + e);
		}
		otherFields.remove("HopsToLive");
		otherFields.remove("SearchKey");
		otherFields.remove("Source");
	}
    
    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
		RawMessage raw = super.toRawMessage(t, ph);
		raw.fs.put("SearchKey", searchKey.toString());
		raw.fs.put("HopsToLive", Long.toHexString(hopsToLive));
		return raw;
	}
    
    public boolean hasTrailer() {
    	return false;
    }
    
    public long trailerLength() {
    	return 0;
    }
    
    public int getHopsToLive()  {
        return hopsToLive;
    }
    
    public Identity getSourceID() {
        return requestSource;
    }
    
    public Key getSearchKey()
    {
    	return searchKey;
    }
    
    public void onSent(PeerHandler ph) {
        Node n = ph.getNode();
        n.logOutgoingRequest();
    }

    public void setHopsToLive(int i) {
        hopsToLive = i;
    }
}
