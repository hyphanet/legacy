package freenet.client.listeners;

import freenet.client.ClientEvent;
import freenet.client.ClientEventListener;
import freenet.client.Request;
import freenet.client.events.StateReachedEvent;

/**
  * The DoneListener will call notify on itself when the request reaches 
  * a terminal state (either DONE or FAILED).
  */
public class DoneListener implements ClientEventListener {

    private boolean isComplete = false;
    
    public synchronized void waitEvent() throws InterruptedException {
        while (!isComplete) wait(200);
    }

    public synchronized void waitDone() {
        while (!isComplete) {
            try { wait(200); }
            catch (InterruptedException e) {}
        }
    }

    public synchronized void strongWait() {
        while (!isComplete) {
            try {
                wait(200);
            } catch (InterruptedException e) {
            }
        }
    }

    public final boolean isDone() {
        return isComplete;
    }

    public void clearState() {
		isComplete=false;
	}
    
    /** Callback - override this to do something when the request finishes
     */
    protected void onDone(StateReachedEvent sr) {
    }
    
    public void receive(ClientEvent ce) {
        StateReachedEvent sr;
        if (!(ce instanceof StateReachedEvent)) 
            return; 
        else
            sr = (StateReachedEvent) ce;
        
        if (sr.getState() == Request.FAILED || sr.getState() == Request.DONE) {
            synchronized (this) {
                isComplete = true;
                notifyAll();  
            }
	    onDone(sr);
        }
    }
}

