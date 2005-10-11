package freenet.client;

import freenet.Core;
import freenet.support.*;
//import freenet.Core;

import java.io.*;

/**
 * Class to insert healing blocks in the background.
 * <p>
 * @author giannij
 **/
public class BackgroundInserter extends RequestManager implements BackgroundHealer {

    Thread workerThread = null;
    int maxSize = -1;
    ClientFactory cf = null;
    volatile boolean running = false;
    
    
    ////////////////////////////////////////////////////////////
    // Allow a shared instance to be stashed in the class.
    private static BackgroundInserter sharedInstance = null;

    public static BackgroundInserter getInstance() {
        return sharedInstance;
    }

    public static void setSharedInstance(BackgroundInserter value) {
        sharedInstance = value;
    }
    ////////////////////////////////////////////////////////////

    public BackgroundInserter(int nThreads, int maxSize, ClientFactory cf, BucketFactory bf) {
        super(null, -1, -1, -1, nThreads, false, bf);
        this.cf = cf;
        this.maxSize = maxSize;
        // We know no other state.
        setState(STATE_START);
    }

    public void start() {
	//Core.logger.log(this, "Attempting to start()", Logger.DEBUG);
        synchronized (this) {
            if (workerThread != null) {
                // hmmmmm.... feeble. LATER: fix.
                throw new IllegalStateException("You can only start a BackgroundInserter once.");
            }
	    //Core.logger.log(this, "Starting workerThread", Logger.DEBUG);
            workerThread = new Thread( new Runnable() {
                    public void run() {
                        onStart();
                        running = true;
                        try {
                            execute(cf);
                        } finally {
                            running = false;
                            onExit();
                        }
                    }
                },"Background inserter");
            workerThread.start();
        }
    }

    protected void onStart() {
        // Callback for subclasses - override
    }
    
    protected void onExit() {
        // Callback for subclasses - override
    }
    
    // REDFLAG: UTTERLY UNTESTED.
    public synchronized void stop() {
        cancelAll();
        if (running && workerThread != null) {
            workerThread.interrupt();
        }
    }
    
    protected void logDebug(String s, boolean trace) {
	//System.err.println(s);
	//if(trace) new Exception("debug").printStackTrace(System.err);
    }
    
    public synchronized void queue(Bucket block, BucketFactory owner, int htl, 
				   String cipher) {
        assertTrue(block != null);
        assertTrue(owner != null);
	
	if(cf.isReallyOverloaded()) return;
	
        HealingInsert hi = new HealingInsert(block, owner, htl, cipher);
        // punt some old requests if nescessary.
        trimQueue(maxSize);
        queueRequest(hi);
	if(!running)
	    start();
    }

    
    protected void onDone(boolean success, int htl, FreenetURI uri) {
        // For subclass logging
    }

    
    protected void onRawEvent(ClientEvent e) {
        // For subclass logging
    }

    ////////////////////////////////////////////////////////////
    // RequestInfo subclass to insert blocks.
    //
    // Note: I didn't use RequestManager.Retryable because
    //       it requires acccess to the segment headers.
    //       Just give up on failure.
    //       Don't post any events.
    //        
    ////////////////////////////////////////////////////////////

    protected class HealingInsert extends RequestInfo {
        Bucket data;
        BucketFactory parentFactory;
        int htl;
        String cipher;
	FreenetURI uri = null;

        // REDFLAG: remove. for debugging.
        void rawEvent(ClientEvent e) {
	    onRawEvent(e);
	    if(e instanceof freenet.client.events.GeneratedURIEvent) {
		uri = ((freenet.client.events.GeneratedURIEvent)e).getURI();
	    }
	}
	
        HealingInsert(Bucket d, BucketFactory pbf, int h, String c) {
            HealingInsert.this.data = d;
            HealingInsert.this.parentFactory = pbf;
            HealingInsert.this.htl = h;
            HealingInsert.this.cipher = c;
        }

        // We don't really care.
        void done(boolean success) {
	    onDone(success, htl, uri);
	}
	
        void cleanup(boolean success) {
            if (data != null) {
                try {
                    parentFactory.freeBucket(data);
                } catch (IOException e) {
                    Core.logger.log(this, "freeBucket("+data+") failed: "+e+" on "+this, 
                            e, Logger.ERROR);
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////
    // RequestManager abstract implementaions.
    ////////////////////////////////////////////////////////////

    protected void produceEvent(ClientEvent ce) {
        // Don't care. Could override in subclasses.
    }

    protected Request constructRequest(RequestInfo i) throws IOException {
        HealingInsert ib = (HealingInsert)i; 
        try {
            ib.req = new PutRequest(ib.htl, "CHK@", ib.cipher, 
                                    new NullBucket(), ib.data);
        }
        catch (InsertSizeException ise) {
            System.err.println("--- Unexpected exception making PutRequest ! ---");
            ise.printStackTrace();
            // This should not happen since I am only using CHK keys.
            throw new IOException("Block insert request creation failed: " + ise.getMessage());
        }
        return ib.req;
    }

    ////////////////////////////////////////////////////////////
    // Overrides
    ////////////////////////////////////////////////////////////
    
    // keep going as long as the BackgroundInserter is running.
    synchronized Request getNextRequest() throws IOException {
	logDebug("getNextRequest", true);
        //System.err.println("BackgroundInserter.getNextRequest -- called");

        // canRequest respects the thread limit.
        while( (!canRequest()) && running) {
	    logDebug("waiting()...", true);
            try {
				wait(1000); // wait() is dangerous
			} catch (InterruptedException e) {
				// Do nothing except check for requests again
			}
        }
        
	logDebug("about to run", true);
        if (canRequest() && running) {
	    logDebug("running super.getNextRequest()", true);
	    long sleepTime = 1000;
	    while(cf.isOverloaded()) {
    	// Don't just sleep, because then we cause new job
    	// additions to block
		//Thread.sleep(sleepTime);
		logDebug("Sleeping for "+sleepTime+" because overloaded node", false);
	    	long startTime = System.currentTimeMillis();
	    	while(sleepTime > 0) {
	    		try {
					wait(sleepTime);
				} catch (InterruptedException e) {
					// Do absolutely nothing
				}
	    		long endTime = System.currentTimeMillis();
	    		sleepTime -= (endTime - startTime);
	    	}
		sleepTime += sleepTime;
	    }
            return super.getNextRequest();
        }
	
	logDebug("returning null from getNextRequest()", true);
        return null;
    }
}

