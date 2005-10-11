package freenet.client.listeners;

import freenet.client.ClientEvent;
import freenet.client.ClientEventListener;
import freenet.client.Request;
import freenet.client.events.StateReachedEvent;
import freenet.client.events.TransferStartedEvent;

public class TransferStartedListener implements ClientEventListener
{
    public TransferStartedEvent event = null;
    private boolean isComplete = false;
    
    public synchronized void waitEvent() throws InterruptedException {
        while(!isComplete) wait(200);
    }

    public void receive(ClientEvent ce) {
        if (ce instanceof TransferStartedEvent) {
            event = (TransferStartedEvent) ce;
            synchronized (this) {
                isComplete = true;
                notifyAll();  
            }
        }
        else if (ce instanceof StateReachedEvent &&
                 ((StateReachedEvent) ce).getState() == Request.FAILED) {
            synchronized (this) {
                isComplete = true;
                notifyAll();
            }
        }
    }
}

