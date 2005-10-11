package freenet.client.listeners;

import freenet.client.ClientEvent;
import freenet.client.ClientEventListener;
import freenet.client.Request;
import freenet.client.events.SegmentCompleteEvent;
import freenet.client.events.StateReachedEvent;

public class SegmentCompleteListener implements ClientEventListener
{
    private boolean isComplete = false;
    
    public synchronized void waitEvent() throws InterruptedException {
        while(!isComplete) wait(200);
    }
    
    public void receive(ClientEvent ce) {
        if (ce instanceof SegmentCompleteEvent) {
            synchronized(this) {
                isComplete=true;
                notifyAll();  
            }
        }
        else if (ce instanceof StateReachedEvent) {
            int state=((StateReachedEvent)ce).getState();
            if((state==Request.DONE)||(state==Request.FAILED)) {
               synchronized(this) {
                 isComplete=true;
                 notifyAll();
               }
            }
        }
    }
}
