package freenet.client;


import freenet.Key;
import freenet.support.*;
import freenet.client.metadata.SplitFile;
import freenet.client.events.*;
import freenet.message.client.FEC.SegmentHeader;
import freenet.message.client.FEC.BlockMap;
import freenet.Core; // for logging. It is reasonable to assume we have a Core here.

import java.util.Hashtable;
import java.util.Vector;
import java.util.Enumeration;
import java.io.IOException;

/**
 * Abstract base class which factors out conncurrent
 * request management funtionality used
 * by SplitFileRequestProcess and SplitFileInsertProcess.
 **/
abstract class RequestManager {

    protected RequestManager(SplitFile sf, 
                             int defaultHtl, 
                             int defaultRetryIncrement,
                             int defaultRetries,
                             int maxThreads,
                             boolean nonLocal,
                             BucketFactory bf) {

        this.state = STATE_START;
        this.sf = sf;
        this.defaultHtl = defaultHtl;
        this.defaultRetryIncrement = defaultRetryIncrement; 
        this.defaultRetries = defaultRetries;
        this.maxThreads = maxThreads;
        if(maxThreads < 1) throw new IllegalStateException("maxThreads = "+maxThreads);
        this.nonLocal = nonLocal;
        this.bf = bf;
	this.logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
    }
    
    volatile boolean notified; // helper for broken JVMs (including Sun!)
    // wait() is dangerous
    // FIXME: can probably use some internal state for this...
    
    ////////////////////////////////////////////////////////////
    // Helper connector class.
    ////////////////////////////////////////////////////////////
    class EventConnector implements ClientEventListener {
        EventConnector(Request r, RequestInfo info) {
            this.request = r;
            this.info = info;
            r.addEventListener(EventConnector.this);
        }

        void release() {
            request.removeEventListener(this);
        }
       
        public void receive(ClientEvent ce) {
            info.rawEvent(ce); 

            StateReachedEvent sr;
            if (!(ce instanceof StateReachedEvent)) 
                return; 
            else
                sr = (StateReachedEvent) ce;

            if (sr.getState() == Request.FAILED || 
                sr.getState() == Request.CANCELLED ||
                sr.getState() == Request.DONE) {
                handleDone(request, sr.getState() == Request.DONE);
            }
        }
        Request request;
        RequestInfo info;
    }

    private final void handleDone(Request r, boolean success) {
        RequestInfo ref = null;

        synchronized (this) {
            // Remove the request from the table of 
            // running requests.
            ref = (RequestInfo)workingMap.get(r);
            workingMap.remove(r);
        }

        //assertTrue(ref != null);
        if (ref == null) {
            // REVISIT:
            // InternalClient can post terminal StateReached events
            // multiple times, causing handleDone to be posted multiple
            // times.  We just ignore calls other than the first.
            if(logDEBUG)
                Core.logger.log(this, "Ignored null ref in handleDone.",
                                Logger.DEBUG);
            return;
        }
 
       try { 
            ref.done(success);
        }
        finally {
            try {
                ref.cleanup(success);
            }
            catch (Exception e) {
                System.err.println("--- unexpected exception handling ref.cleanup ---");
                e.printStackTrace();
            }
        }

        // hmmmm... sloppy
        // Because working map changed.
        synchronized (this) {
	    notified = true;
            notifyAll();
        }
    }

    ////////////////////////////////////////////////////////////
    // Intrastructure for managing information about requests.
    // 
    // RANT: 
    // The necessity of using workingMap and DoneConnetor
    // points out design flaws in the current 
    // Request/ClientEvent implementation.  
    //
    // 1) ClientEvents should have a source field which
    //    points to the EventProducer which produced them.
    //    i.e. Upcastable to XXXRequest.
    // 2) There should be a userData field of type Object
    //    in the Request which allows client code to store
    //    a reference to application specific data useful
    //    during event handling. 
    // 3) There is a standard Java idiom for the Observer
    //    pattern.  Namely, java.util.EventObject / 
    //    java.util.EventListener.  Why aren't we
    //    using it?
    //
    
    protected class RequestInfo {
        Request req;
        Client client;

        // All of these are called 
        // without a lock on RequestManager.this.
        void rawEvent(ClientEvent e) {}
        void done(boolean success) {}
        void cleanup(boolean success) {}
    }

    // Maps Request -> Info *only* for running requests
    private Hashtable workingMap = new Hashtable();

    // LATER: This will suck for large queue sizes.
    //        Re-implement using a dlist.
    
    // FIFO of RequestInfos
    private Vector requestQueue = new Vector();

    private final Request getRequest(RequestInfo info) throws IOException {
        Request r = constructRequest(info);
        if (r == null) {
            throw new IllegalArgumentException("I don't know how to make that kind of request.");
        }

        // Update the map.
        if (info.req != null) {
            workingMap.remove(info.req);
        }
        info.req = r;
        workingMap.put(r, info);

        return r;
    }
    
    ////////////////////////////////////////////////////////////
    // Interface for subclasses.
    ////////////////////////////////////////////////////////////
    
    protected abstract void produceEvent(ClientEvent ce);

    // Subclasses provide this to make appropriate Requests.
    protected abstract Request constructRequest(RequestInfo i) throws IOException;

    // Queues a request to be run after all currently pending
    // requests.
    protected synchronized void queueRequest(RequestInfo i) {
        requestQueue.addElement(i);
	if(logDEBUG)
	    Core.logger.log(this, "Queued request, queue length "+requestQueue.size(),
			    Logger.DEBUG);
	notified = true;
        notifyAll();
    }

    // Queues a request to be run before all currently pending
    // requests.
    protected synchronized void preQueueRequest(RequestInfo i) {
        requestQueue.insertElementAt(i, 0);
	notified = true;
        notifyAll();
    }

    // Trims the queue, removing oldest elements first.
    protected synchronized void trimQueue(int maxSize) {
        boolean trimmed = requestQueue.size() > maxSize;
        while (requestQueue.size() > maxSize) {
            requestQueue.removeElementAt(0);
        }
        if (trimmed) {
            requestQueue.trimToSize();
        }
	notified = true;
        notifyAll();
    }

    // hmmm... They should be running.
    protected synchronized final int requestsRunning() {
        return workingMap.size();
    } 

    protected synchronized final int requestsQueued() {
        return requestQueue.size();
    } 

    // respects maxThreads
    protected synchronized final boolean canRequest() {
        return (requestQueue.size() > 0) && 
            (workingMap.size() < maxThreads);
    }

    protected synchronized final boolean isWorking() {
        return  (requestQueue.size() > 0) ||  (workingMap.size() > 0);

    }

    // IMPORTANT:
    // Don't call this on the event dispatch thread
    // or you may hit nasty deadlock bugs in 
    // internal client.
    protected void cancelAll() {
	logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	if(logDEBUG) Core.logger.log(this, "cancelAll()", 
				     Logger.DEBUG); // FIXME
        // REDFLAG: Send failed events for all queued requests
	Vector v;
	synchronized(this) {
	    requestQueue.removeAllElements();
	    requestQueue.trimToSize();
	    v = new Vector(workingMap.size());
	    // Deadlock evasion
	    for (Enumeration e = workingMap.elements() ; e.hasMoreElements() ;) {
		v.add(e.nextElement());
	    }
	}
	for(int x=0;x<v.size();x++) {
	    RequestInfo i  = (RequestInfo)v.elementAt(x);
            // Will cause running requests to finish as 
            // soon as possible.
            if (i.client != null) {
		if(logDEBUG) Core.logger.log(this, "Cancelling client ("+x+"/"+
					     v.size()+") "+i.client, Logger.DEBUG);
                i.client.cancel();
		if(logDEBUG) Core.logger.log(this, "Cancelled client ("+x+"/"+
					     v.size()+") "+i.client, Logger.DEBUG);
            }
        }
	if(logDEBUG) Core.logger.log(this, "cancelled all, notifying", 
				     Logger.DEBUG);
	synchronized(this) {
	    notified = true;
	    notifyAll();
	}
	if(logDEBUG) Core.logger.log(this, "cancel notified all", Logger.DEBUG);
    }
    
    protected synchronized boolean succeeded() {
        return state == STATE_DONE;
    }

    protected synchronized void abort() {
        errorMsg = "Request was canceled.";
        setState(STATE_CANCELING);
        cancelAll();
    }
    
    // Make a separate function so I can reuse it 
    // in subclasses.
    protected static final void shuffleVector(Vector v) {
        Vector list = (Vector)v.clone();
        v.removeAllElements();
        while (list.size() > 0) {
            int index = (int)(Math.random() * list.size());
            v.addElement(list.elementAt(index));
            list.removeElementAt(index);
        }
	v.trimToSize();
    }


    // Used to randomize order of block requests.
    protected synchronized void shuffleRequestQueue() {
        shuffleVector(requestQueue);
    }

    // IMPORTANT:
    // My event handling scheme assumes that requests returned by
    // this functon will be executed.   
    //
    // IMPORTANT: 
    // Can release lock. If subclass override blocks, it should wait().
    //
    // IMPORTANT:
    // Must free all resources on exception.
    //
    // IMPORTANT:
    // Overrides must call this version or the
    // RequestInfo map won't work.
    synchronized Request getNextRequest() throws IOException {
	if(logDEBUG)
	    Core.logger.log(this, "getNextRequest()", new Exception("debug"),
			    Logger.DEBUG);
        for (;;) {
            if (state == STATE_DONE ||
                state == STATE_FAILED ||
                state == STATE_CANCELED) {
		if(logDEBUG)
		    Core.logger.log(this, 
				    "Returning null because done, failed or cancelled",
				    Logger.DEBUG);
                return null;
            }

            if (workingMap.size() == 0 && requestQueue.size() == 0) {
                // Our work is done.
		if(logDEBUG)
		    Core.logger.log(this, "Returning null because no more work",
				    Logger.DEBUG);
                return null;
            }

            if (requestQueue.size() > 0) {
                RequestInfo i = (RequestInfo)requestQueue.elementAt(0);
                requestQueue.removeElementAt(0);
		requestQueue.trimToSize();
                Request r = getRequest(i);
                if (r != null) {
                    // Make sure the request tells us when it's done.
                    new EventConnector(r, i);
                }
		notified = true;
                notifyAll();

                // Our work is done.
		if(logDEBUG)
		    Core.logger.log(this, "Returning an actual request", 
				    Logger.DEBUG);
                return r;
            }

	    if(logDEBUG)
		Core.logger.log(this, "Waiting() in getNextRequest", 
				Logger.DEBUG);
	    while(!notified) {
		try {
		wait(200);
		} catch (InterruptedException e) {
			// Check notified flag
		}
	    }
	    notified = true;
        }
    } 

    // Generic to all multiple uri inserts / requests
    int state;
    int successes = 0;
    int failures = 0;
    int failuresAllowed = 0;
    int successesRequired = 0;
    
    // Shared by sfi/sfr
    boolean nonLocal = false;
    int segmentCount = 0;
    int currentSegment = 0;
    int currentSegmentNr = 0;
    long length = 0;
    SplitFile sf = null;
    boolean logDEBUG;

    int defaultHtl = 15;
    int defaultRetryIncrement = 3;
    int defaultRetries = 3;
    int maxThreads = 5;

    SegmentHeader[] headers = null;
    BlockMap[] maps = null;
    BucketFactory bf = null;

    // Contains nulls for missing segments.
    Bucket[] blocks;
    Bucket[] checks;
    String checksum;

    String errorMsg = "";
    Throwable errorThrowable = null;
    
    // State constants. 
    static final int STATE_START = 1;
    static final int STATE_CANCELING = 2;
    static final int STATE_FAILING = 3;
    static final int STATE_DONE = 4;
    static final int STATE_FAILED = 5;
    static final int STATE_CANCELED = 6;


    synchronized void setState(int s) {
        if(Core.logger.shouldLog(Logger.DEBUG, this))
            Core.logger.log(this, "State for "+this+": "+
                    stateAsString(state)+" -> "+stateAsString(s),
                    Logger.DEBUG);
        // String initialState = stateAsString();
        state = s;
        // This is actually pretty useful.
        // System.err.println("[" + initialState +"-> " + stateAsString() + "] RequestManager.setState()");
        //(new Exception("get stack")).printStackTrace();
	notified = true;
        notifyAll();
    }

    synchronized void nextSegment() {
        currentSegment++;
        currentSegmentNr++;
    }

    // REDFLAG: eventually remove or put somewhere else
    final void assertTrue(boolean condition) {
        if (!condition) {
            throw new RuntimeException("Assertion failed.");
        }
    }

    ////////////////////////////////////////////////////////////
    // ABC for retryable block insertion and request
    // requests.
    //
    // I do some kind of grotty stuff here in order to 
    // reduce code duplication in subclasses.
    ////////////////////////////////////////////////////////////
    abstract class RetryableInfo extends RequestInfo {
        String uri;
        int index;
        boolean isData;
        boolean downloading;
        int segment;
        int htl;
        int htlRetryIncrement;
        int retries;
        int retryCount;
        Bucket data;

	// set to true if requeued, so that inserts can keep the bucket
	boolean requeued = false;

        // hmmmm... 
        boolean started = false;

        // The state the system is in while the RetrybleInfo
        // should keep retrying.
        int workingState;

        // The state the system should transition to when
        // enough RetryableInfo subclasses instances have been successfully
        // executed.
        int targetState;

        // Reset these on requeue
        ClientEvent reason;
        int suggestedExitCode = -1;

	public String toString() {
	    return "["+RequestManager.this.getClass().getName()+
		": "+uri+":"+segment+"."+index+
		(isData?"(data)":"(not data)")+
		(downloading?"(downloading)":"(uploading)")+
		"@"+htl+"(try "+retryCount+"/"+retries+") "+
		(started?"(STARTED)":"(NOT STARTED)")+
		", workingState="+workingState+", targetState="+
		targetState+", reason="+
		(reason==null?"(null)":reason.toString())+", suggested exit code="+
		suggestedExitCode+"]";
	}
	
	
        RetryableInfo(int working, int target, boolean d) {
            workingState = working;
            targetState = target;
            downloading = d;
	    if(logDEBUG)
		Core.logger.log(this, "Constructed RetryableInfo("+
				RequestManager.this.getClass().getName()+
				"): "+toString(), Logger.DEBUG);
        }
	
        void rawEvent(ClientEvent ce) {
	    if(logDEBUG)
		Core.logger.log(this, "RetryableInfo RAW EVENT: "+ce+
				" for "+this, Logger.DEBUG);
            //System.err.println("RAW EVENT [" + req + "]: " + ce.getDescription());
            switch (ce.getCode()) {
            case RouteNotFoundEvent.code:
            case DataNotFoundEvent.code:
            case ExceptionEvent.code:
            case ErrorEvent.code:
		if(logDEBUG)
		    Core.logger.log(this, "Set reason to "+reason+" for "+this,
				    Logger.DEBUG);
                reason = ce;
                break;
		
            case RestartedEvent.code:
		if(logDEBUG)
		    Core.logger.log(this, "Restarted: "+ce+" for "+this,
				    Logger.DEBUG);
                produceEvent( new BlockRestartedEvent(headers[currentSegment], downloading,
                                                      index,
                                                      isData, 
                                                      realHtl(),
                                                      ce));
                break;
            case TransferEvent.code:
		if(logDEBUG)
		    Core.logger.log(this, "TransferEvent: "+ce+" for "+this,
				    Logger.DEBUG);
                // REDFLAG: look at how I did this for manifest tools.
                //          Weird numbers ???
                produceEvent( new BlockTransferringEvent(headers[currentSegment], downloading,
                                                         index,
                                                         isData, 
                                                         realHtl(),
                                                         ce));
                break;

	    case TransferStartedEvent.code:
		if(logDEBUG)
		    Core.logger.log(this, "TransferStartedEvent: "+ce+" for "+this,
				    Logger.DEBUG);
		produceEvent( new BlockStartedTransferringEvent(headers[currentSegment],
								downloading,
								index, isData, 
								realHtl(), ce));
		break;
		
            case PendingEvent.code:
		if(logDEBUG)
		    Core.logger.log(this, "PendingEvent: "+ce+" for "+this,
				    Logger.DEBUG);
                produceEvent( new BlockPendingEvent(headers[currentSegment], downloading,
                                                    index,
                                                    isData, 
                                                    realHtl(),
                                                    ce));
                break;

            case StateReachedEvent.code:
		if(logDEBUG)
		    Core.logger.log(this, "StateReachedEvent: "+ce+" for "+this,
				    Logger.DEBUG);
		int stateReached = ((StateReachedEvent)ce).getState();
                switch(stateReached) {
                case Request.PREPARED:
		    if(logDEBUG)
			Core.logger.log(this, "Reached state PREPARED - for "+this,
					Logger.DEBUG);
                    started = true;
                    produceEvent( new BlockStartedEvent(headers[currentSegment], downloading, 
                                                        index,
                                                        isData, 
                                                        realHtl()));
                    break;
                case Request.DONE:
                case Request.FAILED:
                case Request.CANCELLED: {
		    if(logDEBUG)
			Core.logger.log(this, "Reached a terminal event "+
					stateAsString(stateReached)+
					" for "+this, Logger.DEBUG);
		    int ec = SplitFileEvent.SUCCEEDED;
                    if (((StateReachedEvent)ce).getState() == Request.FAILED) {
                        ec = SplitFileEvent.FAILED;
                    }
                    if (((StateReachedEvent)ce).getState() == Request.CANCELLED) {
                        ec = SplitFileEvent.CANCELED;
                    }
                    
		    if(logDEBUG)
			Core.logger.log(this, "Set suggestedExitCode to "+
					stateAsString(ec), Logger.DEBUG);
		    
                    // Defer posting event to the subclass done implementation.
                    RetryableInfo.this.suggestedExitCode = ec;
                    break;
                }
		    
                }
            }
        }
        
        // If this is true, the success statistics
        // are not updated on success.  chained()
        // should spawn a new "chained" child
        // request which calls notifySuccess or
        // notifyFailure on this object when it is done.

        // Called *with* a lock on RequestManager.this
        boolean chain() { return false; }

        // This is the only stuff that's different for
        // CHK inserts and requests.
        
        // Called *with* a lock on RequestManager.this
        abstract void onSuccess();

        abstract int realHtl();

        // These functions update
        // the success statistics.
        // They should be called by chained requests.
        void notifySuccess() {
	    if(logDEBUG)
		Core.logger.log(this, "Got notifySuccess() for "+this,
				Logger.DEBUG);
            synchronized (RequestManager.this) {
                successes++;
		
		if(logDEBUG)
		    Core.logger.log(this, "Synchronized on RequestManager: "+this,
				    Logger.DEBUG);
		
                produceEvent( new BlockFinishedEvent(headers[currentSegment], downloading,
                                                     index,
                                                     isData, 
                                                     realHtl(),
                                                     reason,
                                                     suggestedExitCode));
            
                onSuccess();
            
                if (successes >= successesRequired) {
		    if(logDEBUG)
			Core.logger.log(this, "Set targetState as successes="+successes+
					", successRequired="+successesRequired+" for "+
					this, Logger.DEBUG);
                    setState(targetState);
                    // Subclass calls cancelAll() on client thread.
                }
            }
        }

        void notifyFailure() {
	    if(logDEBUG)
		Core.logger.log(this, "notifyFailure() on "+this,
				Logger.DEBUG);
            synchronized (RequestManager.this) {
		if(logDEBUG)
		    Core.logger.log(this, "Synchronized on RequestManager.this for "+
				    this, Logger.DEBUG);
                if ((retryCount < retries) && 
                    (state == workingState)) {
		    
		    if(logDEBUG)
			Core.logger.log(this, "Retrying "+this,
					Logger.DEBUG);
		    
                    retryCount++;
		    
                    // NOTE: We don't post a BlockRequestFinishedEvent on this code path
		    
                    ClientEvent oldReason = reason;
		    
                    // Reset per execution state.
                    reason = null;
                    suggestedExitCode = -1;
		    
                    queueRequest(this);
		    requeued = true;
		    if(logDEBUG)
			Core.logger.log(this, "Requeued "+this,
					Logger.DEBUG);
                    produceEvent( new BlockRequeuedEvent(headers[currentSegment], downloading,
                                                         index,
                                                         isData, 
                                                         realHtl(),
                                                         oldReason) ); 
                }
                else {
                    failures++;
		    if(logDEBUG)
			Core.logger.log(this, "Failed failures="+failures+
					", successes="+successes+
					", successesRequired="+successesRequired+
					" for "+this, Logger.DEBUG);
                    produceEvent( new BlockFinishedEvent(headers[currentSegment], downloading,
                                                         index,
                                                         isData, 
                                                         realHtl(),
                                                         reason,
                                                         suggestedExitCode));
                
                
                    if (failures > failuresAllowed) {
			errorMsg = "Could only fetch "+successes+" of "+
			    headers[currentSegment].getBlocksRequired() +
			    " blocks in segment "+(currentSegment+1)+" of "+
			    headers[currentSegment].getSegments()+": "+
			    failures+" failed, total available "+
			    (headers[currentSegment].getBlockCount()+
			     headers[currentSegment].getCheckBlockCount());
			
			if(logDEBUG)
			    Core.logger.log(this, "Failure for "+this+" caused: "+
					    errorMsg, Logger.DEBUG);
			
                        setState(STATE_FAILING);
                        // NOTE:
                        // Subclass sm does a cancelAll on the
                        // client thread.
                        // Avoid canceling on the event dispatch
                        // thread to avoid known bugs in InternalClient.
                    }
                }
            }
        }
        
        void done(boolean success) {
	    if(logDEBUG)
		Core.logger.log(this, "RetryableInfo.done("+success+") for "+this,
				Logger.DEBUG);
            synchronized (RequestManager.this) {
		if(logDEBUG)
		    Core.logger.log(this, "Synchronized(RequestManager.this) for "+this,
				    Logger.DEBUG);
                if (!started) {
		    if(logDEBUG)
			Core.logger.log(this, "Done but not started! for "+this,
					Logger.DEBUG);
                    // This is to handle really bad error conditions where the
                    // request is executed but never makes it to the PREPARED
                    // state.
                    //
                    // e.g. Node dies.
                    //
                    started = true;
                    produceEvent( new BlockStartedEvent(headers[currentSegment], downloading, 
                                                        index,
                                                        isData, 
                                                        realHtl()));
                }


                if ((currentSegment != segment) ||
                    state != workingState) {
		    if(logDEBUG)
			Core.logger.log(this, "Finished Block (moved on to next phase "+
					"or segment - segment: "+segment+"->"+
					currentSegment+", state: "+stateAsString(state)+
					"(should be "+stateAsString(workingState)+") "+
					this, Logger.DEBUG);
                    produceEvent( new BlockFinishedEvent(headers[currentSegment], downloading,
                                                         index,
                                                         isData, 
                                                         realHtl(),
                                                         reason,
                                                         suggestedExitCode));
		    
		    notified = true;
                    RequestManager.this.notifyAll();
                    return;
                }
                
                if (success) {
		    if(logDEBUG)
			Core.logger.log(this, "Got success for "+this,
					Logger.DEBUG);
                    if (!chain()) {
                        notifySuccess();
                    } else {
			if(logDEBUG)
			    Core.logger.log(this, "Chain says don't pass on success "+
					    "for "+this, Logger.DEBUG);
		    }
                } else {
		    if(logDEBUG)
			Core.logger.log(this, "Got failure for "+this,
					Logger.DEBUG);
                    notifyFailure();
                }
            }
        }
	
        void cleanup(boolean success) {
	    // Default behaviour is for downloading
	    if(logDEBUG)
		Core.logger.log(this, "Cleanup("+success+") for "+this,
				Logger.DEBUG);
	    requeued = false;
            if (data != null) {
                try {bf.freeBucket(data);} catch (Exception e) {}
            }
        }
    }

    // RANT:
    // We execute all the requests ourselves so
    // that we can asyncronously cancel
    // unnecessary pending requests when enough blocks 
    // have arrived to encode / decode the segment.
    //
    // This is not in the spirit of the RequestProcess
    // architecture, but I did not see any way to implement
    // this necessary functionality within its constraints.
    // 
    public synchronized void execute(ClientFactory f) {
        if (state != STATE_START) {
            throw new IllegalStateException("You can only execute once!");
        }

        if (f == null) {
            throw new IllegalArgumentException("f == null");
        }


        // Exception cases:
        // 0) getNextRequest throws  
        //    Subclasses must handle this cleanly.
        // 1) getClient / clientStart throws.
        //    We force shutdown of the state machine.
        boolean groovy = false;
        try {
	    if(logDEBUG)
		Core.logger.log(this, "Executing", new Exception("debug"),
				Logger.DEBUG);
            Request r = getNextRequest();
            while (r != null) {
		if(logDEBUG)
		    Core.logger.log(this, "getNextRequest returned non-NULL",
				    Logger.DEBUG);
                Client c = f.getClient(r);
                RequestInfo i = (RequestInfo)workingMap.get(r);
                assertTrue(i != null);
                assertTrue(c != null);
                i.client = c;
                // REDFLAG: try/finally wrap to protect  request info state?
                c.start();
                r = getNextRequest();
            }
	    if(logDEBUG)
		Core.logger.log(this, "getNextRequest returned NULL",
				Logger.DEBUG);
        }
        catch (Exception e) {
	    if(logDEBUG)
		Core.logger.log(this, "Got exception in execute(): ", e, 
				Logger.DEBUG);
            System.err.println("--- uh ooh ---");
            e.printStackTrace();
        }
        finally {
	    if(logDEBUG)
		Core.logger.log(this, "Finalizing in execute()", Logger.DEBUG);
            if (!groovy && 
                (!(state == STATE_DONE || 
                   state == STATE_FAILED ||
                   state == STATE_CANCELED))) {

                try {
                    cancelAll();
                    setState(STATE_FAILED);
                    // Poke state machine to force cleanup.
                    getNextRequest();
                }
                catch (Exception e) {
		    if(logDEBUG)
			Core.logger.log(this, "Ignoring exception in execute: ",
					e, Logger.DEBUG);
                    System.err.println("RequestManager.execute -- IGNORED EXCEPTION");
                    e.printStackTrace();
                }
            }
        }
	if(logDEBUG)
	    Core.logger.log(this, "Leaving execute()", Logger.DEBUG);
    }

    public String stateAsString() {
	return stateAsString(state);
    }
    
    static public String stateAsString(int state) {
        switch(state) {
        case STATE_START:
            return "STATE_START";
        case STATE_CANCELING:
            return "STATE_CANCELING";
        case STATE_FAILING:
            return "STATE_FAILING";
        case STATE_DONE:
            return "STATE_DONE";
        case STATE_FAILED:
            return "STATE_FAILED";
        case STATE_CANCELED:
            return "STATE_CANCELED";
        }
        return null;
    }

    public final String getMsg() { return errorMsg; }
    
    public final Throwable getThrowable() { return errorThrowable; }

    /**
     * Return true if the CHKs are the same, ignoring formatting 
     * differences.
     **/
    protected static boolean equal(String chk1, String chk2) {
        try {
            Key key1 = AbstractClientKey.createFromRequestURI( new FreenetURI( chk1 ) ).getKey();
            Key key2 = AbstractClientKey.createFromRequestURI( new FreenetURI( chk2 ) ).getKey();
            return key1.equals(key2);
        }
        catch (Exception e) {
            // NOP
        }
        return false;
    }

}






