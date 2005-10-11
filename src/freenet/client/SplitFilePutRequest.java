package freenet.client;

import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.SplitFile;
import freenet.support.BucketFactory;
import freenet.support.FileBucket;

/** Represents a request to encode and
 *  insert a FEC SplitFile. 
 * <p>
 * This is an implementation class.   Client code should 
 * use AutoRequester or PutRequestProcess directly.
 *
 * @author giannij
 */
class SplitFilePutRequest extends Request {

    // HACK: The file checksum gets stashed
    //       here by SplitFileInsertManager. 
    //       It is tunneled up the stack
    //       and eventually 
    //       used by PutRequestProcess to
    //       update the checksum value
    //       in the InfoPart.
    MetadataSettings ms;

    int defaultHtl; 
    int defaultRetries;
    int maxThreads;
    ClientFactory cf;
    BucketFactory bf;
    // Must be a FileBucket so that we can do in place
    // segmentation.
    FileBucket srcBucket;
    String algoName;
    SplitFile sf; 
    
    // NOTE: Client implementations must set this.
    SplitFileInsertManager manager;

    // Full CTOR
    public SplitFilePutRequest(SplitFile sf,
                               int defaultHtl, 
                               int defaultRetries,
                               int maxThreads,
                               String algoName,
                               ClientFactory cf,
                               BucketFactory bf,
                               FileBucket srcBucket,
                               MetadataSettings ms) {
        
        this.sf = sf;
        this.defaultHtl = defaultHtl;
        this.defaultRetries = defaultRetries;
        this.maxThreads = maxThreads;
        this.cf = cf;
        this.bf = bf;
        this.srcBucket = srcBucket;
        this.algoName = algoName;
        this.ms = ms;
        // Trap ridiculous arguments so that we fail
        // early in a predictable way.
        if ((sf == null) || (cf == null) || //algoName == null is allowed.
            (srcBucket == null)) {
            throw new IllegalArgumentException("Can't make SplitFile insert request because " +
                                               "of bad arguments.");
        }
    }
}






