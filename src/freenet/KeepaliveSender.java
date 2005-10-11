package freenet;

import java.util.Vector;

import freenet.crypt.RandomSource;
import freenet.node.NodeReference;
import freenet.node.QueueManager;
import freenet.support.Logger;

/**
 * Thread created on startup. Purpose of this thread is to ensure that:
 * a) We send a keepalive message every 60 seconds on any idle 
 * connection, so that we will see if we lose the connection and can
 * recycle it.
 * b) We send a message updating the current MRI within 10 seconds of
 * the MRI changing, even if there are no other MRI-carrying messages
 * being exchanged at the time (perhaps because of a high MRI!). 
 * Also runs the queue manager every second or so.
 */
public class KeepaliveSender implements Runnable {

    final OpenConnectionManager ocm;
    final QueueManager qm;
    
    public KeepaliveSender(OpenConnectionManager ocm, QueueManager qm) {
        this.ocm = ocm;
        this.qm = qm;
    }

    public void run() {
        while(true) {
            PeerHandler[] oph = ocm.getPeerHandlers();
            PeerHandler[] ph = randomize(oph);
            int blockSize = (ph.length / 5) + 1;
            for(int i=0;i<5;i++) {
                try {
                    // All this randomness mainly to avoid oscillations and so on
                    // Also it may confuse attackers - but don't count on it.
                    Thread.sleep(750 + Core.getRandSource().nextInt(500));
                } catch (InterruptedException e) {
                    // Whatever... doesn't matter
                }
                qm.runQueue();
                long now = System.currentTimeMillis();
                for(int j=i * blockSize; j < Math.min((i+1)*blockSize, ph.length);j++) {
                    PeerHandler p = ph[j];
                    if(!p.isConnected()) continue;
                    NodeReference ref = p.ref;
                    if(ref != null && ref.badVersion()) continue;
                    // FIXME: hardcoded
                    if(now - p.lastSentRequestIntervalTime() < 5000)
                        continue;
                    /**
                     * Use real idle time, not fake idle time.
                     * Thus if the peer has been idle for 60 seconds,
                     * but has queued messages, it may be due to some
                     * sort of race resulting in a message not being
                     * sent, which will be cleared by sending a message.
                     */
                    if(p.getRealIdleTime() > 60000) {
                        // Skip potentially expensive tests
                    } else {
                        if(p.queuedMessagesWithMRI()) continue;
                        if(p.inFlightMessagesWithMRI()) continue;
                        double mri = p.getRequestInterval();
                        double omri = p.lastSentMRI();
                        if(!((Math.abs(mri - omri) / Math.max(mri, omri)) > 0.1))
                            continue;
                    }
                    // Send a message containing the MRI
                    MRIPacketMessage m = new MRIPacketMessage(p);
                    if(Core.logger.shouldLog(Logger.MINOR,this))
                    	Core.logger.log(this, "Sending message: "+m, Logger.MINOR);
                    p.innerSendMessageAsync(m);
                }
            }
        }
    }

    /**
     * Randomize an array of PeerHandlers.
     */
    private PeerHandler[] randomize(PeerHandler[] oph) {
        Vector v = new Vector(oph.length);
        RandomSource rand = Core.getRandSource();
        for(int i=0;i<oph.length;i++) v.add(oph[i]);
        int i = 0;
        int sz;
        PeerHandler[] ph = new PeerHandler[oph.length];
        while((sz = v.size()) > 0) {
            PeerHandler p = (PeerHandler)
                	(sz == 1 ? v.remove(0) :
                	    v.remove(rand.nextInt(sz)));
            ph[i] = p;
            i++;
        }
        return ph;
    }
}
