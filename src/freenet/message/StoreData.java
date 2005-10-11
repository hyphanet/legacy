package freenet.message;
import freenet.BaseConnectionHandler;
import freenet.FieldSet;
import freenet.InvalidMessageException;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.crypt.RandomSource;
import freenet.node.BadReferenceException;
import freenet.node.NodeReference;
import freenet.support.Fields;

/**
 * This message is sent after the DataSend has been completed and
 * causes a routing table reference to be added for the dataSource.
 *
 * @author oskar
 **/

public class StoreData extends NodeMessage {

    public static final String messageName = "StoreData";

    private final NodeReference dataSource;
    
    private int hopsSinceReset;

    private long requestsPerHour;

    public StoreData(long idnum) {
        this(idnum, null, null, 0, 0);
    }

    public StoreData(long idnum, 
                     NodeReference dataSource, FieldSet estimator,
                     long requestsPerHour, int hopsSinceReset) {
        super(idnum, estimator != null ? new FieldSet() : null);
        this.dataSource = dataSource;
        this.requestsPerHour = requestsPerHour;
        this.hopsSinceReset = hopsSinceReset;
        if(estimator != null) {
        	otherFields.put("Estimator", estimator);
        }
    }

    public StoreData(BaseConnectionHandler source, RawMessage raw) 
        throws InvalidMessageException {
        
        super(source, raw);
        try {
            FieldSet dsource = otherFields.getSet("DataSource");
            dataSource = (dsource == null ? 
                          null : 
                          new NodeReference(dsource));
            
            requestsPerHour = -1;
            String rateAsString = otherFields.getString("RequestRate");
            if (rateAsString != null) {
                requestsPerHour = Fields.hexToLong(rateAsString);
            }

            String hopsAsString = otherFields.getString("HopsSinceReset");
	    if(hopsAsString != null) {
		try {
		    hopsSinceReset = Fields.hexToInt(hopsAsString);
		} catch (NumberFormatException e) {
		    hopsSinceReset = 0;
		}
	    } else hopsSinceReset = 0;
	    
	    otherFields.remove("HopsSinceReset");
            otherFields.remove("DataSource");
            otherFields.remove("RequestRate");
        }
        catch (BadReferenceException e) {
            throw new InvalidMessageException("Bad Reference");
        }
        catch (NumberFormatException nfe) {
            throw new InvalidMessageException("Failed to read number " + nfe);
        }
    }

    public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
        RawMessage raw = super.toRawMessage(t,ph);
        if (dataSource != null) {
            raw.fs.put("DataSource", dataSource.getFieldSet());
        }
        if (requestsPerHour != -1) {
            raw.fs.put("RequestRate", Long.toHexString(requestsPerHour));
        }
	raw.fs.put("HopsSinceReset", Integer.toHexString(hopsSinceReset));
	return raw;
    }
    
    public final boolean hasTrailer() {
	return false;
    }
    
    public final long trailerLength() {
	return 0;
    }
    
    public int getHopsSinceReset() {
	return hopsSinceReset;
    }

    public boolean shouldCache(RandomSource r, float cacheProbPerHop) {
	return r.nextFloat() < cacheProbability(cacheProbPerHop);
    }
    
    public float cacheProbability(float cacheProbPerHop) {
	return (float)Math.pow(cacheProbPerHop, hopsSinceReset);
    }
    
    public final NodeReference getDataSource() {
        return dataSource;
    }
    
    //Returns the requests per hour value
    //(or whatever meaning we choose to define for this number 
    // for now -1, means unknown
    public final long getRequestRate() {
        return requestsPerHour;
    }
    
    public final String getMessageName() {
        return messageName;
    }
    
    public String toString() {
        return "freenet.Message: "+messageName+"@(hopsSinceReset="+
            hopsSinceReset+",requestsPerHour="+requestsPerHour+
            ",dataSource="+(dataSource==null?"(null)":
                            dataSource.toString())+")";
    }

	/**
	 * @return
	 */
	public FieldSet getEstimator() {
		return otherFields.getSet("Estimator");
	}

    public int getPriority() {
        return -9; // fairly high priority because it carries a ref
    }
}




