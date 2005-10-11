package freenet.client;

import freenet.client.metadata.SplitFile;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

/** Represents a request to retrieve a
 * and decode a FEC encoded SplitFile. 
 * <p>
 * This is an implementation class.   Client code should 
 * use AutoRequester or GetRequestProcess directly.
 * <p>
 * @author giannij
 */
class SplitFileGetRequest extends Request {
    String checksum;
    boolean nonLocal = false;
    SplitFile sf; 
    int defaultHtl; 
    int defaultRetryIncrement;
    int defaultRetries;
    int healPercentage;
    int healingHtl;
    int maxThreads;
    ClientFactory cf;
    BucketFactory bf;
    Bucket destBucket;

    // Switch that turns on extra debugging 
    // code to check for corrupt SplitFile
    // downloads.
    boolean doParanoidChecks = false;

    BackgroundInserter inserter = null;

    boolean randomSegs = false;

    // NOTE: Client implementations must set this.
    SplitFileRequestManager manager;

    // Full CTOR
    public SplitFileGetRequest(SplitFile sf, 
                               int defaultHtl, 
                               int defaultRetryIncrement,
                               int defaultRetries,
                               int healPercentage,
                               int healingHtl,
                               int maxThreads,
                               ClientFactory cf,
                               BucketFactory bf,
                               Bucket destBucket,
                               boolean nonLocal,
                               String checksum,
                               BackgroundInserter inserter,
                               boolean randomSegs) {

        this.sf = sf;
        this.defaultHtl = defaultHtl;
        this.defaultRetryIncrement = defaultRetryIncrement;
        this.defaultRetries = defaultRetries;
        this.healPercentage = healPercentage;
        this.healingHtl = healingHtl;
        this.maxThreads = maxThreads;
        this.cf = cf;
        this.bf = bf;
        this.destBucket = destBucket;
        this.nonLocal = nonLocal;
        this.checksum=checksum;
        this.inserter=inserter;
        this.randomSegs = randomSegs;
    }

    public final void enableParanoidChecks(boolean value) { doParanoidChecks = value; }
}












