package freenet.client;
import freenet.client.metadata.Metadata;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
/**
 * A request process that inserts a whole site from a list of buckets.
 *
 * @author oskar
 */

public class PutSiteProcess extends RequestProcess {

    private FreenetURI uri;
    private Metadata metadata;
    private Bucket[] files;
    private RequestProcess[] ps;

    private int served = 0;

    private int htl;
    private String cipher;
    private boolean failed = false;
    private boolean aborted = false;
    /** Reason for failure */
    protected String error;
    /** Throwable responsible for failure */
    protected Throwable origThrowable;

    /**
     * @param uri      The base uri.
     * @param metadata      The metadata tree.
     * @param files    The files. Each will be inserted as uri+//+getName()
     *                 on the bucket. The metadata tree must contain an 
     *                 entry for each of these.
     */
    public PutSiteProcess(FreenetURI uri, int htl, String cipher, 
                          Metadata metadata, Bucket[] files,
                          BucketFactory ptBuckets) {
        super(null, ptBuckets, 0);
        this.uri = uri;
        this.metadata = metadata;
        this.htl = htl;
        this.cipher = cipher;
        this.files = files;
        this.ps = new RequestProcess[files.length];
    }

    public synchronized Request getNextRequest() {
        if (aborted || failed)
            return null;
        // The idea is - because we are using no descend, there should only
        // be one request per process. If a subprocess fucks up on this it 
        // won't croak - only run a little slower.
        if (served < files.length - 1) {
            FreenetURI iuri = 
                uri.addMetaStrings(new String[] {files[served].getName()});
                    
            ps[served] = new PutRequestProcess(iuri, htl, cipher, metadata,
                                               metadata.getSettings(),
                                               files[served], ptBuckets, 0, 
                                               false);
            if (ps[served] == null) {
                failed = true;
		error = "Failed to allocate new PutRequestProcess";
		origThrowable = new Exception(error);
                return null;
            } else {
                Request r = ps[served++].getNextRequest();
                if (r == null) {
                    failed = true;
		    error = "Failed to allocate new Request";
		    origThrowable = new Exception(error);
		}
                return r;
            }
        } else if (served < files.length) {
            for (int i = 0 ; i < ps.length - 1 ; i++) {
                Request r = ps[i].getNextRequest();
                if (r != null)  // like I said, not expected, but...
                    return r;
            }

            FreenetURI iuri = 
                uri.addMetaStrings(new String[] {files[served].getName()});

            ps[served] = new PutRequestProcess(iuri, htl, cipher, metadata,
                                               metadata.getSettings(),
                                               files[served], ptBuckets, 0, 
                                               true); // note
        
            if (ps[served] == null) {
                failed = true;
		error = "Failed to allocate new PutRequestProcess";
		origThrowable = new Exception(error);
                return null;
            } else
                return ps[served++].getNextRequest();
        } else {
            Request r = ps[served - 1].getNextRequest();
            failed = ps[served - 1].failed();
	    if(failed) {
		error = "SubRequest Failed: "+ps[served - 1].getError();
		origThrowable = ps[served - 1].getThrowable();
	    }
            return r;
        }
    }

    public void abort() {
        aborted = true;
	error = "aborted";
	origThrowable = new Exception(error);
    }

    public int availableRequests() {
        return served == files.length ? ps[served - 1].availableRequests() : 
            files.length - 1 - served;
    }

    /**
     * This makes very little sense here.
     */
    public Metadata getMetadata() {
        return metadata;
    }

    public FreenetURI getURI() {
        return uri;
    }

    public boolean failed() {
        return failed || aborted;
    }

    public synchronized String getError() {
	return error;
    }

    public synchronized Throwable getThrowable() {
	return origThrowable;
    }

}

