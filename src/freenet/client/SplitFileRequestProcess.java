package freenet.client;
import freenet.client.events.StateReachedEvent;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.SplitFile;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

/**
 * A request process that retrieves and FEC decodes 
 * SplitFiles.
 *
 * @author giannij
 */
public class SplitFileRequestProcess extends RequestProcess {
    
    private SplitFileGetRequest request;
    private SplitFileDoneListener doneListener;
    private boolean firstTime = true;
    private boolean cancelLater = false;
    
    public SplitFileRequestProcess(SplitFile sf, Bucket data, BucketFactory bf, MetadataSettings ms) {
        super(data, bf , 0);

        request = new SplitFileGetRequest(sf,
                                          ms.getBlockHtl(), 
                                          ms.getSplitFileRetryHtlIncrement(),
                                          ms.getSplitFileRetries(),
                                          ms.getHealPercentage(),
                                          ms.getHealingHtl(),
                                          ms.getSplitFileThreads(),
                                          ms.getSplitFileMaxHTL(),
                                          ms.getClientFactory(),
                                          bf,
                                          data,
                                          ms.isNonLocal(),
                                          ms.getChecksum(),
                                          ms.getBackgroundInserter(),
                                          ms.getRandomSegs());

        request.enableParanoidChecks(ms.doParanoidChecks());

        doneListener = new SplitFileDoneListener(request);
        request.addEventListener(doneListener);
    }

    ////////////////////////////////////////////////////////////
    // Helper connector class tells us when the request is done
    // and grabs the requests SplitFileRequestManager instance
    // so that we can asynchronously cancel.
    //
    class SplitFileDoneListener implements ClientEventListener {
        SplitFileGetRequest request;
        boolean done = false;
        SplitFileRequestManager manager = null;
	boolean notified = false;

        SplitFileDoneListener(SplitFileGetRequest r) {
            this.request = r;
        }

        public void receive(ClientEvent ce) {
            StateReachedEvent sr;
            if (!(ce instanceof StateReachedEvent)) 
                return; 
            else
                sr = (StateReachedEvent) ce;

            if (sr.getState() == Request.FAILED || 
                sr.getState() == Request.CANCELLED ||
                sr.getState() == Request.DONE) {
                synchronized (SplitFileDoneListener.this) {
                    done = true;
		    notified = true;
                    SplitFileDoneListener.this.notifyAll();
                }
            }
            else if (sr.getState() == Request.REQUESTING) {
                synchronized (SplitFileDoneListener.this) {
                    manager = request.manager;
		    notified = true;
                    SplitFileDoneListener.this.notifyAll();
                }
            }
        }
        
    }

    public Request getNextRequest() {
        for (;;) {
            synchronized(doneListener) {
                if (doneListener.done) {
                    return null;
                }
                if (firstTime) {
                    firstTime = false;
                    if (cancelLater) {
                        // Aborted before the request was 
                        // ever given out.
                        return null;
                    }
                    // All the real work of requesting and decoding
                    // is done by the Client implementation for
                    // SplitFileRequest.
                    return request;
                }
                if (cancelLater && doneListener.manager != null) {
                    doneListener.manager.cancelAll();
                    cancelLater = false;
                    // REDFLAG: SM stall if working q is empty.

                    continue; 
                }
		while(!doneListener.notified) {
		    try {
			doneListener.wait();
			// wait() is dangerous (JVM bugs)
		    } catch (InterruptedException ie) {
			if (firstTime || doneListener.done) {
			    return null;
			}
			if (doneListener.manager != null) {
			    // Nested locks. DoneListener, RequestManager. hmmm...
			    doneListener.manager.cancelAll();
			    // Must wait for pending child requests to finish.
			    continue; 
			}
		    }
                }
		doneListener.notified = false;
            }
        }
    }
    
    public void abort() {
        boolean cancelNow = false;
        synchronized (doneListener) {
            if (doneListener.manager != null) {
                cancelNow = true;
            }
            else {
                // We don't have a reference to the manager yet
                cancelLater = true;
            }
        }
        if (cancelNow) {
            doneListener.manager.cancelAll();
        }
    }
    
    public int availableRequests() {
        return 1; // hmmm...
    }

    public boolean failed() {
        if (!doneListener.done) {
            // Handle abort before request ever started.
            return cancelLater;
        }
        return !doneListener.manager.succeeded();
    }

    public Metadata getMetadata() {
        return new Metadata(new MetadataSettings());
    }

    public FreenetURI getURI() {
        // REDFLAG: hmmmm... pass uri into ctr just to pass it out here.
        throw new IllegalStateException("SplitFileRequestProcess.getURI() doesn't make any sense"); 
    }
    
    public Throwable getThrowable() { 
	return doneListener.manager.getThrowable(); 
    }
    
    public final String getError() { 
        if (doneListener.manager == null) {
            return null; 
        }
        return doneListener.manager.getMsg();
    }
}










