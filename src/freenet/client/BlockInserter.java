package freenet.client;

import freenet.Core;
import freenet.KeyException;
import freenet.support.Logger;
import freenet.support.Bucket;
import freenet.support.NullBucket;
import freenet.client.Client;
import freenet.client.events.StateReachedEvent;
import freenet.client.events.GeneratedURIEvent;
import freenet.client.events.CollisionEvent;
import freenet.client.ClientFactory;

import java.io.IOException;
import java.net.MalformedURLException;

public class BlockInserter {
    public BlockInserter(ClientFactory factory) {
        this.factory = factory;
    }
    
    volatile boolean notified; // FIXME: can we just use run ?
    
    public synchronized void start(Bucket[] blocks, int htl, int threads, boolean stopOnFailure) {
        if (isRunning()) {
            throw new IllegalStateException("Already running.");
        }
        
        // Make requests
        requests = new CHKInsertRequest[blocks.length];
        try {
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == null) {
                    throw new IllegalArgumentException("null blocks not allowed.");
                }
                requests[i] = new CHKInsertRequest(blocks[i], i, htl);
            }
        }
        catch (MalformedURLException mfu) {
            // This can't happen.
            throw new RuntimeException("Assertion Failure!");
        }
        catch (InsertSizeException ise) {
            // This can't happen.
            throw new RuntimeException("Assertion Failure!");
        }
        
        this.stopOnFailure = stopOnFailure;

        // Reset state
        nPending = 0;
        index = 0;
        uris = new String[0];
        run = true;
        canceled = false;
        this.nThreads = threads;

        // Start worker thread
        worker = new Thread(task, "BlockInserter -- insert");
        worker.start();
    }

    public synchronized void cancel() {
        run = false;
        if (worker != null) {
            worker.interrupt();
        }
	notified = true;
        notifyAll();
    }

    public synchronized boolean isRunning() {
        return worker != null;
    }

    // Can have null entries for failed blocks.
    public synchronized String[] getURIs() {
        return uris;
    }

    public final int getSuccessful() { return successful; }

    public synchronized void setBlockListener(BlockEventListener l) {
        blockListener = l;
    }

    // REDFLAG: deadlock issues...
    public interface BlockEventListener {
        public void receive(int blockNum, boolean succeeded, ClientEvent evt);
    }
    
    ////////////////////////////////////////////////////////////
    private class CHKInsertRequest extends PutRequest implements ClientEventListener {
        public CHKInsertRequest(Bucket data, int blockNum, int htl)
            throws MalformedURLException, InsertSizeException {
            super(htl, "freenet:CHK@", CIPHER, new NullBucket(), data);
            this.blockNum = blockNum;
	    addEventListener(this);
        }

	// Keep track of client so we can cancel.
	public void setClient(Client c) {
	    client = c;
	}

	public void cancel() {
	    if (client != null) {
		client.cancel();
	    }
	}

        public String getFinalURI() { return finalURI; }
        public boolean getSucceeded() { return success; }

	public void receive(ClientEvent ce) {
            //System.err.println("block " + Integer.toString(blockNum) + " : " +
            //                   ce.getDescription());

            // Forward the event to our clients
            if (blockListener != null) {
                blockListener.receive(blockNum, success, ce);
            }

	    if (ce instanceof StateReachedEvent) {
		StateReachedEvent sr = (StateReachedEvent) ce;
		
		switch (sr.getState()) {
                case Request.DONE:
                    success = true;
                    synchronized (BlockInserter.this) {
                        nPending--;
                        successful++;
			notified = true;
                        BlockInserter.this.notifyAll();
                    } 
                    break;
		case Request.FAILED:
		case Request.CANCELLED:
                    synchronized (BlockInserter.this) {
                        // Key collision sets success.
                        if ((!success) && stopOnFailure) {
                            // cancel.
                            run = false;
                        }
                        nPending--;
			notified = true;
                        BlockInserter.this.notifyAll();
                    }
		    break;
		default:
		    // NOP
		}
	    }
            else if (ce instanceof GeneratedURIEvent) {
                GeneratedURIEvent gue = (GeneratedURIEvent)ce;
                if (gue.getURI() != null) {
                    finalURI = gue.getURI().toString();
                }
            }
            else if (ce instanceof CollisionEvent) {
                // REDFLAG: safe to count on gue for uri?
                success = true;
            }
	}
        private int blockNum = -1;
        private String finalURI = null;
        private boolean success = false;
        private Client client = null;
    }

    private final synchronized void startRequests() 
        throws IOException, InterruptedException, KeyException {
        index = 0;
    outer_loop:
        while ((index < requests.length) && run) {
            while ((nPending < nThreads) && run) {
                Client c = factory.getClient(requests[index]);
                requests[index].setClient(c);
                Core.logger.log(this, "inserting block " + index, 
                                Logger.DEBUG);
                index++;
                nPending++;
                c.start();
                if (index == requests.length) {
                    break outer_loop;
                }
            }
            if (run) {
                //System.err.println("BlockInserter.startRequests -- " + nPending + " " +
                //                   nThreads);
		while(!notified) {
		    wait(200);
		    // JVM bugs
		}
		notified = false;
            }
        }
    }

    private final void cancelAll() {
        if (canceled) {
            return;
        }
        for (int i = 0; i < requests.length; i++) {
            if (requests[i] != null) {
                // This can cause ClientEvents to be dispatched
                // so we don't want to hold a lock while calling
                // it.
                //
                requests[i].cancel();
            }
        }
        canceled = true;
    }

    
    private final void waitForPendingRequests()
        throws InterruptedException {
        while (nPending > 0) {
            if ((!run) && (!canceled)) {
                cancelAll();
            }

            synchronized (this) {
                // Recheck the predicate holding the 
                // lock.
                if (nPending <= 0) { 
                    break;
                }
                while(!notified) {
		    wait(200);
		    // JVM bugs
		}
		notified = false;
            }
        }
    }

    private final synchronized String[] copyURIs() {
        String ret[] = new String[requests.length];
        for (int i = 0; i < requests.length; i++) {
            // Only copy URIs for successful requests.
            if (requests[i].getSucceeded()) {
                ret[i] = requests[i].getFinalURI();
            }
        }
        return ret;
    }

    private class RunnableImpl implements Runnable {
        public void run() {
            try {
                startRequests();
                waitForPendingRequests();
                uris = copyURIs();
            } catch (Exception e) {
                cancel();
                try {
                    waitForPendingRequests();
                }
                catch (InterruptedException ie) {
                    // NOP
                }
            } finally {
                synchronized(BlockInserter.this) {
                    worker = null;
		    notified = true;
                    BlockInserter.this.notifyAll();
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////
    private final static String CIPHER = "Twofish";

    private BlockEventListener blockListener = null;
    private ClientFactory factory = null;
    private CHKInsertRequest[] requests = null;
    private int nPending = 0;
    private int nThreads = 0;
    private int index = 0;
    private int successful = 0;
    private Thread worker = null;
    private Runnable task = new RunnableImpl();
    private String[] uris =  new String[0];
    private boolean run = false;
    private boolean canceled = false;
    private boolean stopOnFailure = false;
}





