package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.Core;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.support.Fields;
import freenet.support.Logger;
/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU General Public Licence (GPL) 
  version 2.  See http://www.gnu.org/ for further details of the GPL.
 */

/**
 * This message is sent by a node which cannot locate a piece of data.
 * If received by a node, it should either forward it back to whichever
 * node sent the request to it, or should send another request to find
 * the data.
 *
 * @author <A HREF="mailto:I.Clarke@strs.co.uk">Ian Clarke</A>
 * @author <A HREF="mailto:author2@email.addr">Author 2</A>
 **/

public class QueryRejected extends NodeMessage implements HTLMessage {

    // Public Fields
    public final static String messageName = "QueryRejected";
    public final Exception initException;
    public long hopsToLive;

    // Amount the recieving node should attenuate routing to the
    // sending node. 
    // 0 means not at all.
	private long attenuation;
	private String reason;
    
    // Constructors
    
	public QueryRejected(BaseConnectionHandler source, RawMessage m) throws InvalidMessageException {
	super(source, m);

        // i.e. Anytime we make one from the wire.
        Core.diagnostics.occurrenceCounting("inboundQueryRejecteds", 1);

		String hopsString = otherFields.getString("HopsToLive");
		if (hopsString == null || hopsString.length()==0)
	    throw new InvalidMessageException("Can't find Hops To Live field");
	try {
	    hopsToLive = Fields.hexToLong(hopsString);
	} catch (NumberFormatException e) {
	    throw new InvalidMessageException("Failed to read number " + e);
	}

        // REDFLAG: get rid of backwards compatibility once the 
        //          required version number is bumped.
        // Backwards compatible.
        attenuation = 0;
		String attenuationString = otherFields.getString("Attenuation");
	if (attenuationString != null) {
            try {
                attenuation = Fields.hexToLong(attenuationString);
            } catch (NumberFormatException e) {
                throw new InvalidMessageException("Failed to read number " + e);
            }
        }

		reason = otherFields.getString("Reason");
        otherFields.remove("HopsToLive");
        otherFields.remove("Reason");
        otherFields.remove("Attenuation");
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	    initException = new Exception("debug:QR:a");
		else
			initException = null;
    }

    public QueryRejected(long idnum, long htl, FieldSet otherfields) {
		this(idnum,htl,null,otherfields);
    }

	public QueryRejected(long idnum, long htl, String reason, FieldSet otherFields) {
		this(idnum,htl,0,reason,otherFields);
    }

	public QueryRejected(long idnum, long htl, long attenuation, String reason, FieldSet otherFields) {
		super(idnum, otherFields);
        hopsToLive = htl;
        this.attenuation = attenuation;
        this.reason = reason;
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	    initException = new Exception("debug:QR:d");
		else
			initException = null;
    }

    // Public Methods

    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
	RawMessage raw=super.toRawMessage(t,ph);
	raw.fs.put("HopsToLive", Long.toHexString(hopsToLive));
	raw.fs.put("Attenuation", Long.toHexString(attenuation));
        if (reason != null)
            raw.fs.put("Reason",reason);
	return raw;
    }
    
    public final boolean hasTrailer() {
	return false;
    }
    
    public final long trailerLength() {
	return 0;
    }
    
    public int getHopsToLive()   {
        return (int) hopsToLive;
    }

    public String getMessageName() {
        return messageName;
    }
	
	public long getAttenuation()
	{
		return attenuation;
	}
	
	public String getReason(){
		return reason;
	}
    
    public String toString() {
	return super.toString()+": htl="+hopsToLive+", reason="+reason;
    }

    public int getPriority() {
        return 1; // pretty much expendable
    }

    public void setHopsToLive(int i) {
        hopsToLive = i;
    }
}


