package freenet.client;
import freenet.client.events.StateReachedEvent;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.SplitFile;
import freenet.support.BucketFactory;
import freenet.support.FileBucket;

/**
 * A request process that segments, encodes and inserts 
 * FEC SplitFiles.
 *
 * @author giannij
 */
public class SplitFileInsertProcess extends RequestProcess {
    
    private SplitFilePutRequest request;
    private SplitFileDoneListener doneListener;
    private boolean firstTime = true;
    private boolean cancelLater = false;
    public SplitFileInsertProcess(SplitFile sf, String cipher, FileBucket data, 
                                  BucketFactory bf, MetadataSettings ms) {
        super(data, bf , 0);
	if(ms == null) {
	    throw new NullPointerException("MS NULL!");
	}
        request = new SplitFilePutRequest(sf,
                                          ms.getBlockHtl(), 
                                          ms.getSplitFileRetries(),
                                          ms.getSplitFileThreads(),
                                          ms.getSplitFileAlgoName(),
                                          ms.getClientFactory(),
                                          bf,
                                          data,
                                          ms);



        // REDFLAG: implement or remove
        //request.enableParanoidChecks(ms.doParanoidChecks());
        
        doneListener = new SplitFileDoneListener(request);
        request.addEventListener(doneListener);
    }

    ////////////////////////////////////////////////////////////
    // Helper connector class tells us when the request is done
    // and grabs the requests SplitFileInsertManager instance
    // so that we can asynchronously cancel.
    //
    // REDFLAG: Worth reducing code duplication with sfrp?
    class SplitFileDoneListener implements ClientEventListener {
        SplitFilePutRequest request;
        boolean done = false;
	boolean notified = false;
        SplitFileInsertManager manager = null;

        SplitFileDoneListener(SplitFilePutRequest r) {
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
                    continue; // Recheck predicates.
                }
		while(!doneListener.notified) {
		    try {
			doneListener.wait();
		    }
		    catch (InterruptedException ie) {
			if (firstTime || doneListener.done) {
			    return null;
			}
			if (doneListener.manager != null) {
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

        if (doneListener.manager == null) {
            return true;
        }

        return !doneListener.manager.succeeded();
    }

    public Metadata getMetadata() {
        // hmmmm...
        return new Metadata(new MetadataSettings());
    }

    public FreenetURI getURI() {
        // REDFLAG: hmmmm... pass uri into ctr just to pass it out here.
        throw new IllegalStateException("SplitFileInsertProcess.getURI() doesn't make any sense"); 
    }

    // Meaningless?
    public Throwable getThrowable() { return null; }

    public final String getError() { 
        if (doneListener.manager == null) {
            return null; 
        }
        return doneListener.manager.getMsg();
    }
}










