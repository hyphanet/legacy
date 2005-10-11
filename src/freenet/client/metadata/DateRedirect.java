package freenet.client.metadata;
import freenet.FieldSet;
import freenet.client.FreenetURI;
import freenet.client.GetRequestProcess;
import freenet.client.RequestProcess;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Fields;
//import Freenet.client.Metadata;

public class DateRedirect extends Redirect {
    
    public static final String name = "DateRedirect";

    public static final long DEFAULT_OFFSET = 0;
    public static final int  DEFAULT_INCREMENT = 0x15180;

    private long currentTime; // time use as current in seconds...

    private int increment;
    private long offset;
    //    private FreenetURI target;

    public DateRedirect(int increment, long offset, FreenetURI target) {
        this(increment, offset, System.currentTimeMillis() / 1000, 
             target);
    }

    /**
     * @param increment   The amount of time between updates to the target.
     * @param offset      The baseline offset of the time values.
     * @param currentTime   The time to count as current, IN SECONDS OF EPOCH!
     * @param target       The base URI to use.
     */
    public DateRedirect(int increment, long offset, long currentTime, 
                        FreenetURI target) {
        super(target);
        this.increment = increment;
        this.offset = offset;
        this.target = target;
	this.currentTime = currentTime;
	if (this.currentTime < 0) 
            this.currentTime = System.currentTimeMillis() / 1000;
    }
        
    public DateRedirect(FieldSet rawFields,
                        MetadataSettings ms) throws InvalidPartException {

        super(rawFields, ms);
        currentTime = ms.getCurrentTime() / 1000;
        String offsets = rawFields.getString("Offset");
        String increments = rawFields.getString("Increment");

        try {
            if (offsets == null)
                offset = DEFAULT_OFFSET;
            else
                offset = Fields.hexToLong(offsets);
        
            if (increments == null)
                increment = DEFAULT_INCREMENT;
            else
                increment = (int) Fields.hexToLong(increments);
        } catch (NumberFormatException e) {
           throw new InvalidPartException("DateRedirect: Malformed numeric.");
        }
    }

    public void addTo(FieldSet fs) {
        super.addTo(fs);
        FieldSet me = fs.getSet(name());

        if (offset != DEFAULT_OFFSET)
            me.put("Offset", Long.toHexString(offset));
        if (increment != DEFAULT_INCREMENT)
            me.put("Increment", Long.toHexString(increment));
    }

    public String name() {
        return name;
    }

    protected FreenetURI getCurrentTarget(FreenetURI furi) {
        long reqTime = 
            offset + increment * ( ( currentTime - offset ) / increment);
        FreenetURI ruri = getRequestTarget(furi);
        return ruri.setDocName(Long.toHexString(reqTime) + "-" 
                               + target.getDocName());
    }

    public FreenetURI getTargetForTime(FreenetURI furi, long timeSec) {
        long reqTime = 
            offset + increment * ( ( timeSec - offset ) / increment);
        FreenetURI ruri = getRequestTarget(furi);
        return ruri.setDocName(Long.toHexString(reqTime) + "-" 
                               + target.getDocName());
    }


    public RequestProcess getGetProcess(FreenetURI furi, int htl, 
                                        Bucket data,
                                        BucketFactory ptBuckets, 
                                        int recursionLevel, 
                                        MetadataSettings ms) {
        
        return new GetRequestProcess(getCurrentTarget(furi), htl, data, 
                                     ptBuckets,
                                     ++recursionLevel, ms);
    }

    public RequestProcess getPutProcess(FreenetURI furi, int htl, 
                                        String cipher, Metadata next,
                                        MetadataSettings ms,
                                        Bucket data, BucketFactory ptBuckets,
                                        int recursionLevel, boolean descend) {


        /* Make sure the old docname is left when the URI is updated after
           insert */
        String docname = furi.getDocName();

        return new RedirectPutProcess(getCurrentTarget(furi), docname, 
                                      htl, cipher, 
                                      next, ms,
                                     data, ptBuckets, ++recursionLevel, 
                                     descend);
    }

    public String toString() {
        return "DateRedirect -> " + target + " (" + offset + " + n*" +
            increment + ')';
    }

    /* @return the increment, IN SECONDS */
    public final int getIncrement() { return increment; }
    /* @return the offset, IN SECONDS SINCE THE EPOCH */
    public final long getOffset() { return offset; }
    /* @return the request time, IN SECONDS SINCE THE EPOCH */
    public final long getRequestTime() { return currentTime; }
}




