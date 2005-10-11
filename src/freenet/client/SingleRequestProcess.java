package freenet.client;
import freenet.client.listeners.DoneListener;
import freenet.client.metadata.Metadata;
import freenet.support.Bucket;
/*
 * This performs a single request, and locks until the first is finished
 * if getNextRequest is called after that. Subclasses need to to specify
 * the request type.
 *
 * @author oskar
 */
public abstract class SingleRequestProcess extends RequestProcess {

    protected FreenetURI uri;
    protected boolean aborted = false;
    protected Request dr;
    private DoneListener dl;

    /** Reason for failure */
    protected String error;
    /** Throwable responsible for failure */
    protected Throwable origThrowable;

    public SingleRequestProcess(Bucket data) {
        super(data, null, 0);
    }

    protected synchronized Request getNextRequest(Request dr) {
        if (aborted) 
            return null;
        else if (dl == null) {
            this.dr = dr;
            dl = new DoneListener();
            dr.addEventListener(dl);
            return dr;
        } else {
            dl.strongWait();
            return null;
        }
    }

    public synchronized void abort() {
        if (dr.state() != Request.DONE) {
	    error = "aborted";
	    origThrowable = new Exception("aborted");
            aborted = true;
	}
    }

    public synchronized int availableRequests() {
        return dl == null ? 1 : 0;
    }

    public synchronized boolean failed() {
        if (aborted)
            return true;
        else if (dl == null || !dl.isDone()) {
            return false;
        } else {
	    if(dr.state() != Request.DONE) {
		origThrowable = new WrongStateException("waiting for Request",
							Request.DONE, dr.state());
		error = origThrowable.toString();
		return true;
	    } else return false;
        }
    }

    public synchronized String getError() {
	return error;
    }

    public synchronized Throwable getThrowable() {
	return origThrowable;
    }

    public Metadata getMetadata() {
        return null;
    }

    public FreenetURI getURI() {
        return uri;
    }

}

