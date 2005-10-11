package freenet.client;
import freenet.Core;
import freenet.client.listeners.DoneListener;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataPart;
import freenet.client.metadata.MetadataSettings;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Logger;
/**
 * Shared superclass for requestprocesses that follow control documents.
 *
 * @author oskar
 */
public abstract class ControlRequestProcess extends RequestProcess {

    protected FreenetURI uri;
    protected int htl;

    protected Metadata metadata;

    /** Requests on this level */
    protected Request r;
    protected DoneListener dl;

    /** Whether we failed. */
    protected boolean failed = false;
    /** Whether we were aborted */
    protected boolean aborted = false;

    /** Reason for failure */
    protected String error;
    /** Throwable responsible for failure */
    protected Throwable origThrowable;

    /** Whether we are now serving this or it's child */
    protected boolean nextLevel;
    /** Child request process */
    protected RequestProcess next;

    /** Whether to do more than one step */
    protected boolean follow;

    /** Settings for metadata */
    protected MetadataSettings msettings;

    public ControlRequestProcess(FreenetURI uri, int htl, Bucket data,
                                 BucketFactory ptBuckets, int recursionLevel,
                                 boolean follow, MetadataSettings msettings) {
        super(data, ptBuckets, recursionLevel);
        this.uri = uri;
        this.htl = htl;
        this.follow = follow;
        this.msettings = msettings;
    }

    public synchronized void abort() {
        aborted = true;
	error = "aborted";
	origThrowable = new Exception(error);
        if (nextLevel && next != null)
            next.abort();
    }

    public synchronized int availableRequests() {
        return nextLevel ? (next == null ? 0 : next.availableRequests()) : 
            (r == null ? 1 : 0);
    }

    public synchronized boolean failed() {
        return failed || aborted;
    }

    public synchronized String getError() {
	return error;
    }

    public synchronized Throwable getThrowable() {
	return origThrowable;
    }

    /**
     * Returns the final metadata once the request is finished.
     */
    public synchronized Metadata getMetadata() {
        if (next == null) {
            return metadata != null ? metadata : new Metadata(msettings);
        } else {
            Metadata res = next.getMetadata();
            if (metadata != null) {
                //System.err.print("LALA R1: ");
                DocumentCommand me = null;
                if (uri.getMetaString() != null)
                    me = metadata.getDocument(uri.getMetaString());
                if (me == null)
                    me = metadata.getDefaultDocument();
                //System.err.println(me);
                MetadataPart[] mdp = null;
                if (me != null)
                    mdp = me.getNonControlParts();
                //System.err.println(mdp[0]);
                if (mdp != null && mdp.length > 0) {
                    DocumentCommand d = res.getDefaultDocument();
                    if (d == null) {
                        d = new DocumentCommand(msettings);
                        res.addDocument(d);
                    }
                    for (int i = 0 ; i < mdp.length ; i++) {
                        try {
                            d.addPart(mdp[i]);
                        } catch (InvalidPartException e) {
                            throw new Error(e + " when moving metadata.");
                            //System.err.println("LALA INVALID WHEN READDING: "
                            //                   + e);
                        }
                    }
                }
            }
            return res;
        }
    }

    /**
     * Returns the final URI of the root object (that is this object, not 
     * any redirects I followed.
     */
    public synchronized FreenetURI getURI() {
        return uri;
    }
    
    protected static String getNextFailedErrorString(Throwable t, String s) {
	if((s == null || s.length()==0) && t == null) {
	    Core.logger.log(ControlRequestProcess.class, 
			    "GRRR! BOTH t AND s NULL in "+
			    "getNextFailedErrorString - REPORT TO "+
			    "devl@freenetproject.org", Logger.ERROR);
	    return "BOTH t AND s NULL! - NO DATA";
	}
	if(s == null || s.length()==0)
	    s = t.toString();
	if(s.length()==0) s = t.getClass().getName();
	s = "Next failed: "+s;
	return s;
    }
    
    public String toString() {
	return super.toString() + ":"+uri+"@"+htl+","+nextLevel;
    }
}
