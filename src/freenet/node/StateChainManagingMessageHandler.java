package freenet.node;

import java.io.PrintStream;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.Core;
import freenet.MessageHandler;
import freenet.MessageObject;
import freenet.diagnostics.ExternalCounting;
import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.Heap;
import freenet.support.LesserLockContentionHashTable;
import freenet.support.Logger;

/**
 * This class handles incoming messages.
 *
 * @author <A HREF="mailto:I.Clarke@strs.co.uk">Ian Clarke</A>
 * @author oskar
 * @author tavin
 * @author Iakin
 */
public class StateChainManagingMessageHandler extends MessageHandler {

	private final Node node;
    
    //A table containing all known/started ChainContainer's/StateChain's
    //When a ChainContainer/Chain is created or dies they will/should be
    //added resp. removed from this table. 
    //Maps from TicketIndex to ChainContainer
    private final LesserLockContentionHashTable ticketTable;
    
	/** Number of locks in the ticketTable lockpool */
	private static final int TICKETTABLE_BUCKETCOUNT = 40;

	//A by-state-priority-sorted list of currently active
	// StateChain's/ChainContainer's.
    //At any given time no currently executing ChainContainer's/StateChain's
    //will/is allowed to be present in this collection since all items in the
    //collection is candidates for excess purging
	private final Heap ticketLadder;

	private int limit; //The limit of how many concurrent (live) chains we
	// will allow
	private final ExternalCounting liveChains =
		Core.diagnostics.getExternalCountingVariable("liveChains");
    
    /**
	 * @param n
	 *            The node that should be used to send messages and for which
	 *            the datastore should be updated
	 * @param m
	 *            The number of message objects that will be remembered
     */
    public StateChainManagingMessageHandler(Node n, int m) {
        node = n;
		ticketTable =
			new LesserLockContentionHashTable(TICKETTABLE_BUCKETCOUNT, m);
		
		//Prevent Heap resizing and resulting GC thrashing
		ticketLadder = new Heap(10,m);
        limit = m;
    }
    
    /**
     * Handle a message
	 * 
	 * @param mo
	 *            The message to handle
     */
    public boolean handle(MessageObject mo, boolean onlyIfCanRunFast) {
        
        if (!(mo instanceof NodeMessageObject)) { 
			Core.logger.log(
				this,
							"Received a MessageObject that the node cannot handle: "+mo,
							Logger.ERROR);
            return true;
        }
        NodeMessageObject message = (NodeMessageObject) mo;

		if(Core.logger.shouldLog(Logger.DEBUG,this))
			Core.logger.log(this, toString()+" handling "+mo,Logger.DEBUG);
        ChainID ti = new ChainID(message.id(), message.isExternal());
        ChainContainer chainContainer;
        
        // Create or fetch ChainContainer for this id
        synchronized (ticketTable.getLockFor(ti)) {
            chainContainer = (ChainContainer) ticketTable.get(ti);
            if (chainContainer == null) {
				// no two threads can make duplicate tickets
				chainContainer = new ChainContainer(ti);
				ticketTable.put(ti, chainContainer);
            }
        }

        return chainContainer.received(node, message, onlyIfCanRunFast);

    }

    /**
	 * Prints information about the current state of a chain (and past states
	 * if history is enabled).
     */
    public void printChainInfo(long id, PrintStream ps) {
        ChainContainer t;
        synchronized(ticketTable) {
            t = (ChainContainer)ticketTable.get(new ChainID(id, true));
            if (t == null) {
                t = (ChainContainer) ticketTable.get(new ChainID(id, false));
            }
        }
        if (t != null) {
            t.chain.printStateInfo(ps);
        } else {
            ps.println("No information for chain id: " + Long.toString(id, 16));
        }
    }
    
	/**
	 * Purges overflowing Tickets from the ticketLadder and ticketTable If it
	 * really has to do something then it might be a sign that something bad is
	 * going on.
	 */
	private void purgeExcessTickets(){
		
		//Doublechecked locking bug situation here.. but who
		//cares. The next purge performed will do the purging instead
		//(there is some performance benefits to be gained
		//from not entering the synchronized block below if
		//it isn't really neccessary)
		if(ticketLadder.size() <= limit) 
			return;
        	
		LinkedList lExcessTickets = new LinkedList();
		synchronized (ticketLadder) { //TODO: switch places of the
			// 'synchronized' block and the 'while'
			// block? Decreases locking and increases
			// lock calls.. good or bad?
			ChainContainer excessTicket = null;
			while (ticketLadder.size() > limit) {
				excessTicket = (ChainContainer) ticketLadder.pop();
				lExcessTickets.add(excessTicket);
				if(ticketTable.remove(excessTicket.index) == null)
					Core.logger.log(
						this,
						"Huh?, ChainContainer '"
							+ excessTicket
							+ "' not known to ticketTable when trying to purge it",
						Logger.ERROR);
			}
		}
	    
		//Clean up lost states. No need to synchronize here since
		//we know that we are the only one holding this purged ticket
		//(we have removed it from both the ticketTable and the
		//ticketLadder)
		if(Core.logger.shouldLog(Logger.DEBUG,this))
			Core.logger.log(
				this,
				"Dropping " + lExcessTickets.size() + " excess tickets",
				Logger.DEBUG);
		for(Iterator i = lExcessTickets.iterator();i.hasNext();) {
			ChainContainer t = (ChainContainer)i.next();
			int severity =
				t.priority >= State.OPERATIONAL ? Logger.ERROR : Logger.DEBUG;
			if(Core.logger.shouldLog(severity,this))
				Core.logger.log(this, "Lost "+t, severity);
			t.lost(node);
		}
	}

	/**
	 * Container for a 64-bit unique id that prevents internal chains from
	 * colliding with external ones.
	 */
    private final static class ChainID {
        private final long id;
        private final boolean external;
        public ChainID(long id, boolean external) {
            this.id = id;
            this.external = external;
        }

        public final long id() {
            return id;
        }

        public final boolean equals(Object o) {
            ChainID ot = (ChainID) o;
            return ot.id == id && ot.external == external;
        }

        public final int hashCode() {
            return (int) id ^ (int) (id >> 8)  ^ (external ? 0xffffffff : 0);
        }

        public final String toString() {
            return (external ? "ext:" : "int:") + Long.toHexString(id);
        }
    }

    //Wraps a StateChain.
    //Manages chain execution and the chains message queue 
	private final class ChainContainer
		extends Heap.Element
		implements Runnable {
        
        private final ChainID index;

		//Manages the actual state execution flow/state transitions within
		// this ChainContainer
        private final StateChain chain = new StateChain();  

		//Our backlog of received MessageObjects (used in a FIFO kind of way)
		// TODO: Create a proper FIFO class or dequeue class (will make the
		// code somewhat easier to understand)
        private final DoublyLinkedList workList = new DoublyLinkedListImpl();
        
        //Wheter or not we/some thread are/is currently working on this chain
        //synchronize on workList (above) whenever accessing this variable
        private boolean working = false; //A new ChainContainer is not working
        
        //True while we are yet to receive our first message.
		//Synchronize on workList (above) whenever accessing this variable
		private boolean virgin = true;

        protected int priority = State.OPERATIONAL; // default

		/**
		 * The time of the last state transition (the last time the StateChain
		 * completed handling of a MessageObject)
		 */
		protected long lastTransitionTime = System.currentTimeMillis();
	
		public String toString() {
			return super.toString()+" for "+chain;
		}
	
        private ChainContainer(ChainID index) {
            this.index = index;
        }

		private final boolean received(
			Node n,
			NodeMessageObject mo,
									   boolean onlyRunIfFast) {
            boolean shouldrun = false;
            synchronized (workList) {
                if (!working) {
					// Don't run in-thread if not possible to run fast or if we
					// have any MessagesObjects enqueued for processing
					if(onlyRunIfFast) {
						if(!workList.isEmpty() || !chain.canRunFast(node, mo))
							return false;
					}

					if(virgin){	
						virgin = false;
					} else { //If we are 'virgin' we aren't present in the
						// ticketLadder yet.. no need to try to take ut out
						// of it..
						//We dont want to be purged while working and btw. we
						// will
						//need to re-insert ourself into the ticketLadder
						// later on since we
						//might have a new priority and will have a new
						// transition time
						//after we have executed a message
						synchronized (ticketLadder) {
							this.remove(); //Equal to
							// 'ticketLadder.remove(this)'
						}
					}
					
					working = true;
					shouldrun = true;
				} else if (onlyRunIfFast)
					return false; //Don't run in-thread if
				// somebody else is already
				// running
				workList.push(new ChainWorkEntry(node, mo));
            }
	    
            if (shouldrun)
                run();
	    	return true; //We consumed the MessageObject
        }

        //Call when synchronized on workList only
		private final void stopWorking() {
			working = false;
			if (chain.alive()) 
				//TODO: Hmmm.. should EXPENDABLE chains be stuffed back here?
				synchronized(ticketLadder){
					ticketLadder.put(this);// check the ticket back in
				} else
				ticketTable.remove(index); // forget all about the ticket
		}
	
		/** Returns the next enqueued ChainWorkEntry or null if we dont have
		 ** any more right now (more might arrive as long as the chain it alive
		 ** though). Call when synchronized on workList only.
		  */
		private ChainWorkEntry getNextEntry() {
			if (workList.isEmpty())
				return null;
			return (ChainWorkEntry) workList.shift();
		}

        //Processes any ChainWorkEntry's enqueued in our workList 
        public void run() {
            ChainWorkEntry entry;

			while (true) { 
				// Work as long as we have more 'messages' to work upon
            	synchronized(workList){
					entry = getNextEntry();
					if(entry==null){ //No more ChainWorkEntry's enqueued
						stopWorking(); 
						// Stop working on this ChainContainer/StateChain for now
						break;
					}
            	}
				
                boolean wasAlive, isAlive;   
     
                synchronized (chain) {

					wasAlive =
						chain.alive() && chain.priority() > State.EXPENDABLE;

                    chain.received(entry.getNode(), entry.getMessageObject());

					isAlive =
						chain.alive() && chain.priority() > State.EXPENDABLE;
					// TODO: Hmmm.. is EXPENDABLE chains really dead?

					if (!wasAlive && isAlive) //If the chain wasn't yet
						// started but started due to the
						// supplied message
                        liveChains.count(1);
					else if (wasAlive && !isAlive) //If the chain was started
						// but died due to the
						// supplied message
                    	liveChains.count(-1);

					//The (potentially) new current-state in the chain might
					// cause
                    //the chain to have another priority than the previous one
                    priority = chain.priority(); 
                    lastTransitionTime = System.currentTimeMillis();
                }
            }
			//Purge any excess tickets if necessary
			//TODO: This maintenance code should be called from somwehere else
			// really
			purgeExcessTickets();
        }

        /**
		 * Returns 1 if this is of lesser priority or if priority is equal and
		 * we have been longer since transition. And conversely.
         */
        public int compareTo(Object o) {
            ChainContainer t = (ChainContainer) o;
			return (
				t.priority > priority
					? 1
					: (t.priority < priority
						? -1
						: (t.lastTransitionTime > lastTransitionTime
							? 1
							: t.lastTransitionTime < lastTransitionTime
							? -1
							: 0)));
        }

        //Notifies the ChainContainer of a premature termination situation.
        //Should be called whenever such a situation occurrs
		private final void lost(Node n) {
			if (chain.lost(n)) {
				Core.logger.log(
					node,
					"States overflow, discarding: " + chain,
					Logger.DEBUG);
                if (chain.priority() > State.EXPENDABLE) {
					Core.logger.log(
						node,
						"State queue overflow! Event: " + chain + "lost.",
						Logger.NORMAL);
					liveChains.count(-1);
                }
            }
        }
    }

    //A StateChain message queue item
	private static final class ChainWorkEntry
		extends DoublyLinkedListImpl.Item {
        private final Node node;
        private final NodeMessageObject mo;

        public ChainWorkEntry(Node node, NodeMessageObject mo) {
            this.node = node;
            this.mo = mo;
        }

        public Node getNode() {
            return node;
        }

        public NodeMessageObject getMessageObject() {
            return mo;
        }
    }
}