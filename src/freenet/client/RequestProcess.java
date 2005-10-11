package freenet.client;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.client.metadata.Metadata;
/**
 * Client factories control the order and what kinds of Requests 
 * are issued. For example, in a Redirect request the factory could
 * first issue a request for the redirect, and then one for the data.
 *
 * A Request process concerns any insert or request of a single file. 
 *
 * @author oskar
 */

public abstract class RequestProcess {

    /** Recursion limit */
    public static final int RECURSION_LIMIT=20;

    /** The data bucket */
    protected Bucket data;
    /** BucketFactory for an auxiliary temp buckets needed */
    protected BucketFactory ptBuckets;
    /** The level of recursion in the making of a request. */
    protected int recursionLevel;

    /**
     * Create a new RequestProcess.
     * @param data       The destination bucket of the requested data.
     * @param ptBuckets  BucketFactory for an auxiliary temp buckets needed
     * @param recursionLevel  The recursion level do to redirects. Kept track
     *                        of to avoid runaway redirect loops.
     */ 
    public RequestProcess(Bucket data, BucketFactory ptBuckets,
                          int recursionLevel) {
        if (recursionLevel > RECURSION_LIMIT)
            throw new RuntimeException("Redirect overflow");
        this.data = data;
        this.ptBuckets = ptBuckets;
        this.recursionLevel = recursionLevel;
    }

    /**
     * Returns the next Request to be executed in this process. This may
     * allow Requests to run in parallel, or lock until some previous
     * request is completed.
     * @return  The next Request object if more requests need to be made.
     *          When the requestprocess is finished, null should be returned
     *          and data and metadata buckets are expected to contain their
     *          final data.
     */
    public abstract Request getNextRequest();
    
    /**
     * Abort the process.
     */
    public abstract void abort();
    
    /**
     * The number of parallel requests that can be made at the current time.
     * This value should never decrease except when getNextRequest is called.
     * @return  The number of times getNextRequest can be called without 
     *          locking (for example the number of parts in a splitfile).
     */
    public abstract int availableRequests();

    /**
     * Whether the process was successful or not.
     * @return  false if the process was successful or is not completed,
     *          true it has failed.
     */
    public abstract boolean failed();

    /**
     * The Reason Why failed() is true, if available.
     * @return String describing reason for failure
     */
    public abstract String getError();

    /**
     * The Reason Why failed() is true, as a Throwable, if available
     */
    public abstract Throwable getThrowable();

    /**
     * Returns the final metadata after the requets is finished (and 
     * didn't fail)
     */
    public abstract Metadata getMetadata();

    /**
     * Returns the final URI after the request is finished.
     */
    public abstract FreenetURI getURI();

}
