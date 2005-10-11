package freenet.client.listeners;

import freenet.client.*;
import freenet.client.events.*;

/** A simple listener used by AutoClient. */
public class SimpleStatusListener implements ClientEventListener {

    boolean started = false,
            metadataDone = false,
            done = false,
            failed = false;
    
    long dataLength, metadataLength;
    
    /** Waits until the transfer starts.
      * @return true on success, false on failure.
      */
    public synchronized boolean waitForStart() {
        try { while (!failed && !started) wait(200); }
        catch (InterruptedException e) { }
        return !failed;
    }
    
    /** @return length of data (modulo metadata), or -1 if not started. */
    public synchronized long dataLength() {
	return started ? dataLength : -1;
    }
    
    /** @return length of metadata, or -1 if not started. */
    public synchronized long metadataLength() {
	return started ? metadataLength : -1;
    }
    
    /** Waits until the metadata finishes transferring.
      * @return true on success, false on failure.
      */
    public synchronized boolean waitForMetadata() {
        try { while (!failed && !metadataDone) wait(200); }
        catch (InterruptedException e) { }
        return !failed;
    }
    
    /** Waits until all data has finished transferring.
      * @return true on success, false on failure.
      */
    public synchronized boolean waitForCompletion() {
        try { while (!failed && !done) wait(200); }
        catch (InterruptedException e) { }
        return !failed;
    }

    public synchronized void receive(ClientEvent ce) {
        if (ce instanceof TransferStartedEvent) {
            TransferStartedEvent tse = (TransferStartedEvent) ce;
            started = true;
            dataLength = tse.getDataLength();
            metadataLength = tse.getMetadataLength();
            notifyAll();
        }
        if (ce instanceof SegmentCompleteEvent) {
            metadataDone = true;
            notifyAll();
        }
        if (ce instanceof StateReachedEvent) {
            StateReachedEvent sre = (StateReachedEvent) ce;
            if (sre.getState() == Request.DONE)
                done = true;
            if (sre.getState() == Request.FAILED)
                failed = true;
            notifyAll();
        }
    }
}

