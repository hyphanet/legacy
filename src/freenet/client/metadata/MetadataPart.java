package freenet.client.metadata;
import freenet.FieldSet;
import freenet.client.FreenetURI;
import freenet.client.RequestProcess;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

/** 
 * Freenet metadata (cdocs).
 * @author mjr (?)
 * @author Tavin
 * @author oskar
 */ 
public abstract class MetadataPart {

    /**
     * Adds this part to the Command fieldset.
     */    
    public abstract void addTo(FieldSet fs);

    /**
     * @return the name of this metadata part
     */
    public abstract String name();
    
    /**
     * Whether this part is a control part (ie redirecting for the the data).
     */
    public abstract boolean isControlPart();

    
    public RequestProcess getGetProcess(FreenetURI furi, int htl, 
                                        Bucket data,
                                        BucketFactory ctBuckets,
                                        int recursionLevel,
                                        MetadataSettings ms) {
        return null;
    }

    public RequestProcess getPutProcess(FreenetURI furi, int htl,
                                        String cipher,
                                        Metadata metadata, 
                                        MetadataSettings ms,
                                        Bucket data,
                                        BucketFactory ctBuckets,
                                        int recursionLevel, boolean descend) {
        return null;
    }

}
    




