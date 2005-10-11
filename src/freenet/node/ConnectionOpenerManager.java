package freenet.node;

import java.util.Iterator;
import java.util.LinkedList;

import freenet.Core;
import freenet.OpenConnectionManager;
import freenet.PeerHandler;
import freenet.node.rt.NodeSortingRoutingTable;
import freenet.node.states.maintenance.Checkpoint;
import freenet.support.Checkpointed;
import freenet.support.Logger;

/**
 * Class to manage current connection open attempts, open new 
 * connections when necessary, and so on.
 * @author amphibian
 */
public class ConnectionOpenerManager implements Checkpointed {

	private final int maxNegotiations;
    private ConnectionOpener[] openers;
    // For our purposes, a negotiation is live if it's scheduled
    private int liveNegotiations = 0;
    private final NodeSortingRoutingTable rt;
    private final OpenConnectionManager ocm;
    // Running on the ticker we end up multiplying it every time we reschedule
    // On the KISS principle, lets just have another thread for now.
    private final Thread thread;
    private final COMRunnable myRunnable;
    private final Node node;
    boolean logDEBUG = true;
    
    private final long createdTime;

    // Run it on its own thread.
    private class COMRunnable implements Runnable {

        public void run() {
            while(true) {
                try {
                    synchronized(this) {
                        wait(5000);
                    }
                } catch (InterruptedException e) {
                    // Wake up early, something changed
                }
                try {
                    rescheduleNow();
                } catch (Throwable t) {
                    Core.logger.log(this, "Caught "+t+" rescheduling!", Logger.ERROR);
                }
            }
        }
    }
    
    //The reschedule method will throttle calls to it, this value defines the minimum number
    //of milliseconds between two consecutive calls  
    private final static int MINIMUM_RESCHEDULING_INTERVAL = 1000;
    
    ConnectionOpenerManager(int maxNegotiations, NodeSortingRoutingTable rt,
            OpenConnectionManager ocm, Node n) {
        this.maxNegotiations = maxNegotiations;
        openers = new ConnectionOpener[maxNegotiations];
        this.rt = rt;
        this.ocm = ocm;
        this.node = n;
        myRunnable = new COMRunnable();
        thread = new Thread(myRunnable);
        thread.setDaemon(true);
        thread.start();
        createdTime = System.currentTimeMillis();
    }
    
    private long dontRescheduleBefore = 0; // the epoch
    /** Are we actually running a reschedule? */
	private boolean rescheduling = false;
    private final Object dontRescheduleBeforeLock = new Object();
    /** If true, we want to run immediately because something has changed */
    private boolean rescheduleNow = false;
    /** If true, we have scheduled ourselves and we MUST NOT schedule again until it is completed */
    private boolean scheduled = false;

    public void reschedule() {
        synchronized(myRunnable) {
            myRunnable.notify();
        }
    }
    
    /**
     * Start opening some connections, if necessary.
     */
    public void rescheduleNow() {
		try {
		    long now = System.currentTimeMillis();
		    Core.logger.log(this, "rescheduleNow(): rescheduleNow="+rescheduleNow+
		            ", rescheduling="+rescheduling+", t="+(dontRescheduleBefore-now)+
		            " on "+this, Logger.DEBUG);
		    //Throttle calls to this method; we don't need very many to
		    //perform the task we are intended to do.
		    synchronized(dontRescheduleBeforeLock) {
		        rescheduleNow = false;
		        if(rescheduling || now < dontRescheduleBefore)
		            return;
		        rescheduling = true;
		        // Value of dontRescheduleBefore doesn't matter so long as rescheduling is true.
		    } 
		    //Only one thread at a time can get here.
		    //We want to start some new negotiations.
		    //Do we have any candidates?
			doReschedule();
		} finally {
			synchronized(dontRescheduleBeforeLock) {
				// Allow another thread to start rescheduling,
				// but not before minimum rescheduling interval has elapsed.
			    scheduled = false;
				rescheduling = false;
				dontRescheduleBefore = System.currentTimeMillis() + MINIMUM_RESCHEDULING_INTERVAL;
				if(logDEBUG)
				    Core.logger.log(this, "Exited doReschedule()", Logger.DEBUG);
			}
		} 
	}

	private synchronized void doReschedule() {
	    boolean logMINOR = Core.logger.shouldLog(Logger.MINOR, this);
	    long startTime = System.currentTimeMillis();
		if(liveNegotiations >= maxNegotiations) return; //Should hopefully never be '>'... 
		int maxCandidates = maxNegotiations - liveNegotiations;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if(logDEBUG) Core.logger.log(this, toString()+".reschedule(): we need "+maxCandidates,
			Logger.DEBUG);
		PeerHandler[] availablePHs = ocm.getPeerHandlers();
		if(logDEBUG) Core.logger.log(this, "OCM returned "+availablePHs.length+" nodes", Logger.DEBUG);
		dump(availablePHs, "Before ordering:");
		rt.order(availablePHs, false); //Ask RT to prioritize nodes to connect to for us
		dump(availablePHs, "After ordering: ");
		/**
		 * For each PH in the ocm:
		 * Start at the top, move downwards.
		 * Avoid enqueueing any more ConnectionOpeners if we are about to 
		 * overrun the nodes max allowed connectioncount.
		 * Skip if the node is already connected. 
		 * Skip if the node is unconnectable.
		 * Skip if the node is backed off.
		 * ..else add it to the list.
		 * If the list is full, break.
		 * 
		 * Afterwards, open connections to any located candidates
		 */
		LinkedList candidates = new LinkedList(); //A list of candidates for connection-opening
		int maxConns = ocm.getMaxNodeConnections();
		int alreadyConnected = 0;
		
		for(int i = 0;i<availablePHs.length;i++){
			if((candidates.size()+liveNegotiations) > maxConns)
				break;
			if(logDEBUG) Core.logger.log(this, "In loop: candidates="+candidates.size()+
				", i="+i+", maxConns="+maxConns,Logger.DEBUG);
			PeerHandler p = availablePHs[i];
			if(p == null) continue; 
			boolean connected = p.isConnected();
			if(logDEBUG) Core.logger.log(this, "Got: "+i+": connected="+connected+": "+p, Logger.DEBUG);
			if(!connected){ //Possible candidate for opening then.. 
				//Figure out wheter or not it actually is more than a _possible_ candidate for opening
				if (p.notContactable()) {
					if (logDEBUG) 
						Core.logger.log(this, "Not contactable", Logger.DEBUG);
					continue;
				}
				if (p.isBackedOff()) {
					if (logDEBUG) 
						Core.logger.log(this, "Backed off for next " + p.backoffRemaining(), Logger.DEBUG);
					continue;
				}
				if (alreadyOpening(p)) {
					if (logDEBUG) 
						Core.logger.log(this, "Already opening connection", Logger.DEBUG);
					continue;
				}
				candidates.addLast(p);
				if (logDEBUG)
					Core.logger.log(this, "Got candidate " + candidates.size() + ":  " + p, Logger.DEBUG);
				if (candidates.size() >= maxCandidates) //Should hopefulle never be '>'... 
					break;
			}else
				alreadyConnected++;
		} 
		
		if(logDEBUG)
		    Core.logger.log(this, "Got "+candidates.size()+
		            " candidates, opening connections...", Logger.DEBUG);
		
		if(alreadyConnected == availablePHs.length)
			if (Core.logger.shouldLog(Logger.MINOR,this))
				Core.logger.log(this, "Huh? Asked to reschedule connection openers but all available PHs where already connected",new Exception("debug"), Logger.MINOR);
		//Now open a connection to each candidate found
		Iterator it = candidates.iterator();
		while(it.hasNext()) {
			PeerHandler p = (PeerHandler)it.next();
			int idx = getNextFreeOpenerIndex();
			if(idx == -1) break;
			ConnectionOpener opener = new ConnectionOpener(p.getIdentity(),
				rt, ocm, node, p, this, idx);
			openers[idx] = opener;
			liveNegotiations++;
			new Checkpoint(opener).schedule(node);
		}
		long endTime = System.currentTimeMillis();
		long length = endTime-startTime;
		boolean logHeavy = length > 5000 && startTime - createdTime > 600*1000;
		if(logMINOR || logHeavy)
		    Core.logger.log(this, "doReschedule took "+length+"ms",
		            logHeavy ? Logger.NORMAL : Logger.MINOR);
	}

    /**
     * @param availablePHs
     * @param string
     */
    private void dump(PeerHandler[] availablePHs, String string) {
        if(!logDEBUG) return;
        Core.logger.log(this, string, Logger.DEBUG);
        for(int i=0;i<availablePHs.length;i++) {
            Core.logger.log(this, Integer.toString(i)+": "+
                    availablePHs[i], Logger.DEBUG);
        }
    }

    private boolean alreadyOpening(PeerHandler p) {
        for(int i=0;i<openers.length;i++) {
            if(openers[i] != null && openers[i].ph == p) return true;
        }
        return false;
    }

    private int getNextFreeOpenerIndex() {
        for(int i=0;i<openers.length;i++) {
            if(openers[i] == null) return i;
        }
        return -1;
    }

    public String getCheckpointName() {
        return "Check for connections to open";
    }

    public long nextCheckpoint() {
        return System.currentTimeMillis() + 
        	(rescheduleNow ? 0 : 5000);
    }

    public void checkpoint() {
        rescheduleNow();
    }

	public void openerTerminated(ConnectionOpener opener, int openerIndex) {
		synchronized (this) {
			if (openers[openerIndex] == opener) {
				openers[openerIndex] = null;
				liveNegotiations--;
			} else {
				Core.logger.log(this, "Opener finished but not in list!: " + opener, Logger.ERROR);
			}
		}
		rescheduleNow();
	}
}
