package freenet;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.message.DataRequest;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.rt.NodeSorter;
import freenet.support.AtomicIntCounter;
import freenet.support.Checkpointed;
import freenet.support.Fields;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SnapshotCachingLesserLockContentionHashTable;
import freenet.transport.tcpAddress;
import freenet.transport.tcpTransport;

/**
 * I moved out of the LinkManager code because I think that the crypto links
 * and open ConnectionHandlers are fairly orthogonal. Yeah, it means Another
 * Fucking Hashtable (AFH (tm)), but the other way was another fucking
 * headache, and I've had enough of those too.
 *
 * <p>
 * Anyways, the job of this class is pretty obvious - it caches open and active
 * ConnectionHandler objects.
 * </p>
 *
 * @author oskar
 */
public final class OpenConnectionManager implements Checkpointed {

	private final MultiValueTable chs=new MultiValueTable(50, 3);
    
    /** Map of Identity -> PeerHandler */
	private final SnapshotCachingLesserLockContentionHashTable peerHandlers =
		new SnapshotCachingLesserLockContentionHashTable(1000, 1000);
    
	private final AtomicIntCounter openConns = new AtomicIntCounter();

	private final LinkedList closedList = new LinkedList();
    
    private final LRUQueue lru = new LRUQueue();

    private int maxConnections = -1;
    
    private boolean logDEBUG = true;
    
	private NodeSorter sorter;
    /**
     * If true then we will render the OCM HTML page in PeerHandler mode, else
     * in Connectionsmode
     */
    private boolean peerHandlerHTMLMode = Node.defaultToOCMHTMLPeerHandlerMode;
    
	private final ConnectionsHTMLRenderer connectionsHTMLRenderer =
		new ConnectionsHTMLRenderer(this);

	private final PeerHTMLRenderer peerHTMLRenderer = new PeerHTMLRenderer(this);

	/** The maximum fraction of the nodes connected that are allowed to be
	 * newbie nodes. Above this point we reject connections, unless we can
	 * accept connections without dropping connections.
	 */
	private double maxNewbieFraction;
	
	/**
	 * Create an OpenConnectionManager
	 * @param maxConnections the current maximum connections limit.
	 * We will try very hard to keep the actual number of registered 
	 * connections within this limit. Of course this does not include
	 * connections that haven't been registered yet - 
	 * @see freenet.interfaces.FreenetConnectionRunner, and @see 
	 * freenet.ConnectionJob
	 * @param sorter a NodeSorter to determine which connection to
	 * throw out when we have too many. Null means to throw out the
	 * strict LRU; this is not recommended for anything but the most
	 * trivial apps.
	 */
	public OpenConnectionManager(
		int maxConnections,
		double maxNewbieFraction,
		NodeSorter sorter) {
        this.maxConnections = maxConnections;
		this.sorter = sorter;
		this.maxNewbieFraction = maxNewbieFraction;
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
    }
   
	public void updateMaxNewbieFraction(double newMaxNewbieFraction)
	{
		maxNewbieFraction = newMaxNewbieFraction;
	}
	
    public int getMaxNodeConnections(){
        return maxConnections; 
    }

    public void setMaxNodeConnections(int iNewValue){
        maxConnections = iNewValue;
        synchronized(lru) {
            lru.notifyAll();
        }
    }

    public int getNumberOfOpenConnections(){
        return lru.size();
    }

    public int countPeerHandlers() {
        return peerHandlers.size();
    }

    /**
     * Get the NodeReference for the identity given, if it is available, from
     * the peer handlers.
     */
    public NodeReference getNodeReference(Identity id) {
		PeerHandler ph = (PeerHandler) peerHandlers.get(id);
		if (ph == null)
			return null;
		return ph.getReference();
	}
    
    public void removePeerHandler(Identity i) {
	    Core.logger.log(this, "Removing "+i, Logger.MINOR);
       peerHandlers.remove(i);
    }
    
	public void addPeerHandler(
		Identity i,
		PeerHandler ph,
		boolean allowedToFail) {
		if (i == null)
			return; // FCP peerHandlers are not indexed
		synchronized (peerHandlers.getLockFor(i)) {
            Object oph = peerHandlers.get(i);
            if(oph != null) {
                if(!allowedToFail)
					Core.logger.log(
						this,
						"Not replacing " + oph + " with " + ph + " for " + i,
						new Exception("debug"),
						Logger.NORMAL);
			} else {
			    Core.logger.log(this, "Adding "+i+":"+ph,
			            Logger.MINOR);
                peerHandlers.put(i, ph);
        }
    }
	}
    
    /**
     * This method is package only, meant only for BaseConnectionHandler
     * objects to add themselves.
     */
    void put(BaseConnectionHandler ch) {
		if (logDEBUG)
			Core.logger.log(this, "put(" + ch + ")", Logger.DEBUG);
        Identity i = ch.peerIdentity();
		if (i == null)
			throw new IllegalArgumentException("Must have a peerIdentity");
        // Find Peer for ch
            // REDFLAG: with sessionv2 we will know the Peer from negotiation
		PeerHandler ph = (PeerHandler) peerHandlers.get(i);

		NodeReference ref = null;
		if (ph == null) {
			ref = ch.targetReference();
			Main.node.reference(null, i, ref, null);
			ph = getPeerHandler(i);
        }
        // ph != null now
        while(true) {
            try {
                ch.setPeerHandler(ph); // will register on ph
            } catch (RemovingPeerHandlerException e) {
				Core.logger.log(
					this,
					"Caught " + e + " setting ph for " + ch,
					Logger.MINOR);
				Main.node.reference(null, i, ref, null);
				ph = getPeerHandler(i);
                continue;
            }
            break;
        }
            chs.put(i, ch);

        lru.push(ch); // synchronized(lru)
        Core.diagnostics.occurrenceCounting("liveConnections",1);
        if (ch.isOutbound() && (Core.outboundContacts != null)) {
            // We really mean live, not active.  Some of the 
            // "active" connections may be idle.
            Core.outboundContacts.incActive(ch.peerAddress().toString());
            Core.outboundContacts.incSuccesses(ch.peerAddress().toString());
        }
		openConns.inc();
		if (maxConnections != 0 && lru.size() < maxConnections)
			return;
        /* At toad's request moving back to doing this in every thred. */
//        lru.notifyAll();
        KillSurplusConnections();
    }
    
    public void updateReference(NodeReference ref) {
		if (ref == null)
			return;
		PeerHandler ph = (PeerHandler) peerHandlers.get(ref.getIdentity());
		if (ph != null)
			ph.updateReference(ref);
	}
    
	public PeerHandler makePeerHandler(
		Identity i,
		NodeReference ref,
		Presentation p) {
		if (i == null)
			i = ref.getIdentity();
		synchronized (peerHandlers.getLockFor(i)) {
            PeerHandler ph = (PeerHandler)(peerHandlers.get(i));
            if(ph != null) {
				if (ref != null)
					ph.updateReference(ref);
                return ph;
            }
			if (ref == null)
				ref = Main.node.rt.getNodeReference(i);
			ph =
				new PeerHandler(
					i,
					ref,
					Main.node,
					Node.muxTrailerBufferLength,
					Main.node.getMaxPacketLength(),
					p);
			if(Core.logger.shouldLog(Logger.MINOR,this))
				Core.logger.log(this, "Adding new PH: " + ph, Logger.MINOR);
            peerHandlers.put(i, ph);
            return ph;
        }
    }
    
    public PeerHandler getPeerHandler(Identity id) {
		return (PeerHandler) peerHandlers.get(id);
	}
    
    // Removing a connection that isn't in the OCM is a 
    // legal NOP.
    BaseConnectionHandler remove(BaseConnectionHandler ch) {
        // CH.terminate() will remove from PeerHandler
        //if (ch.peerIdentity() == null) return null;
        Identity id = ch.peerIdentity();

		boolean removedFromCHS = chs.removeElement(ch.peerIdentity(), ch);

        if(removedFromCHS) {
			if (logDEBUG)
				Core.logger.log(this, "Removed BaseConnectionHandler " + ch, new Exception("debug"), Logger.DEBUG);
            Core.diagnostics.occurrenceCounting("liveConnections", -1);
			if (ch.isOutbound()
				&& (Core.outboundContacts != null)
				&& ch.peerAddress() != null) {
                Core.outboundContacts.decActive(ch.peerAddress().toString());
            }
            lru.remove(ch); // is synchronized(lru)
			openConns.dec();

            return ch;
        } else
            return null;
    }
    
    public void markClosed(BaseConnectionHandler ch) {
        synchronized(closedList) {
            closedList.addFirst(ch);
        }
    }
    
    public int countConnections(Identity id) {
        Object syncOb = chs.getSync(id);
		if (syncOb == null)
			return 0;
        synchronized(syncOb) {
            Enumeration e = chs.getAll(id);
            int count = 0;
            while(e.hasMoreElements()) {
                count++;
                e.nextElement();
            }
            return count;
        }
    }
    
    public int countInboundConnections(Identity id) {
        Object syncOb = chs.getSync(id);
		if (syncOb == null)
			return 0;
        synchronized(syncOb) {
            Enumeration e = chs.getAll(id);
            int count = 0;
            while(e.hasMoreElements()) {
				BaseConnectionHandler ch =
					(BaseConnectionHandler) (e.nextElement());
				if (!ch.isOutbound())
					count++;
            }
            return count;
        }
    }
    
    public int countOutboundConnections(Identity id) {
        Object syncOb = chs.getSync(id);
		if (syncOb == null)
			return 0;
        synchronized(syncOb) {
            Enumeration e = chs.getAll(id);
            int count = 0;
            while(e.hasMoreElements()) {
				BaseConnectionHandler ch =
					(BaseConnectionHandler) (e.nextElement());
				if (ch.isOutbound())
					count++;
            }
            return count;
        }
    }
    
	public TrailerWriter sendMessage(
		Message m,
		Identity i,
		NodeReference ref,
		long timeout,
		int msgPrio,
		Presentation p)
		throws SendFailedException {
		if (i == null)
			i = ref.getIdentity();
		PeerHandler ph = getPeerHandler(i);
		if (ph == null) {
			Core.logger.log(this, "Dropped message send to " + i + "/" + ref + ": " + m + ": no PeerHandler",
				Logger.NORMAL);
			throw new SendFailedException(null, i, "No PeerHandler", true);
		}
		return ph.sendMessage(m, timeout, msgPrio);
    }
    
	/**
	 * Send a message asynchronously.
	 * @param m the FNP message to send.
	 * @param i the Identity of the target node. Can be null if ref is not.
	 * @param ref the NodeReference of the target node. Can be null if ref is
	 * not.
	 * @param cb a callback to call when the send has completed or failed.
	 * @param timeout timeout in milliseconds, after which we don't want the
	 * node to continue trying to send the message. Specifically, the message
	 * will be deleted from the queue after this time. 0 means no timeout but
	 * is deprecated.
	 * @param msgPrio priority of message, either PeerPacketMessage.EXPENDABLE
	 * or PeerPacketMessage.NORMAL. The former means the message can be dropped
	 * if there are no open connections to the node.
	 * FIXME: review this mechanism - obsolete? Was only used for QueryRejected.
	 */
	public void sendMessageAsync(
		Message m,
		Identity i,
		NodeReference ref,
		MessageSendCallback cb,
		long timeout,
		int msgPrio) {
		if (i == null)
			i = ref.getIdentity();
		PeerHandler ph = getPeerHandler(i);
		if (ph == null) {
			Core.logger.log(
				this,
				"Dropped message send to "
					+ i
					+ "/"
					+ ref
					+ ": "
					+ m
					+ ": no PeerHandler",
				Logger.NORMAL);
			if(cb != null)
			    cb.thrown(new SendFailedException(null, i, "No PeerHandler", true));
			return;
    }
		ph.sendMessageAsync(m, cb, timeout, msgPrio);
    }
    
    public void unsendMessage(Identity i, MessageSendCallback cb) {
        PeerHandler ph = getPeerHandler(i);
        if(ph != null) {
            ph.unsendMessage(cb);
        }
    }
    
	boolean runningKillSurplusConnections = false;
	/** Please don't coalesce this lock with other locks unless
	 * you know what you are doing!
     */
	Object killSurplusLock = new Object();
	
    private void KillSurplusConnections() {
	    try {
	        synchronized(killSurplusLock) {
	            if(runningKillSurplusConnections) return;
	            runningKillSurplusConnections = true;
                    }
	        int sz = lru.size();
	        if (maxConnections <= 0 || sz < maxConnections)
	            return;
	        sz = Math.max(sz, maxConnections);
	        PeerHandler[] ph = new PeerHandler[sz + 5];
	        int i = 0;
	        int nullCount = 0;
	        synchronized (lru) {
                            for (Enumeration e = lru.elements(); e.hasMoreElements();) {
	                BaseConnectionHandler ch =
	                    (BaseConnectionHandler) e.nextElement();
	                if (ch == null) {
	                    nullCount++;
	                    continue;
                    }
	                if (i >= ph.length) {
	                    // WSH!
	                    Core.logger.log(
	                            this,
	                            "Too many connections! Making array of size "
	                            + ph.length * 2,
	                            Logger.NORMAL);
	                    PeerHandler[] nph = new PeerHandler[ph.length * 2];
	                    System.arraycopy(ph, 0, nph, 0, i);
	                    ph = nph;
	                }
	                ph[i] = ch.getPeerHandler();
	                i++;
	            }
	        }
	        Core.logger.log(
	                this,
	                "Null count: " + nullCount,
	                nullCount > 1 ? Logger.NORMAL : Logger.DEBUG);
	        if (sorter != null)
	            sorter.order(ph, false);
	        // REDFLAG: Otherwise reading from the end will return MRU
	        // Which is only used by ClientCore, so it's ok :)
	        for (int x = ph.length - 1;
	        	x >= 0 && maxConnections > 0 && lru.size() > maxConnections;
	        	x--) {
	            PeerHandler p = ph[x];
	            if (p == null)
	                continue;
	            BaseConnectionHandler ch,prevCH=null,pprevCH=null;
	            while ((ch = p.getConnectionHandler()) != null
	                    && maxConnections > 0 && lru.size() > maxConnections) {
	                if(prevCH != null && ch == prevCH){ //If this happens we would probably end up spinning here forever.. Fix the cause of so! 
	                    Core.logger.log(this,"Terminated connection but it didn't disappear from its PeerHandler, ch=" + ch,new Exception("debug"),
	                            Logger.ERROR);
	                    if(ch.getPeerHandler() == null)
	                        Core.logger.log(this, "... because ch.peerhandler was null!",
	                                Logger.ERROR);
	                    p.unregisterConnectionHandler(ch);
	                    
	                    // FIXME: remove this once we know it doesn't happen
	                    if(pprevCH == prevCH) {
	                        Core.logger.log(this, "p.unregisterConnectionHandler failed too!",
	                                Logger.ERROR);
	                        break;
                }
            }
	                if (ch.blockedSendingTrailer())
	                    Core.logger.log(
	                            this,
	                            "Killing sending connection " + ch + " !!!",
	                            Logger.ERROR);
	                if (ch.receiving())
	                    Core.logger.log(
	                            this,
	                            "Killing receiving connection " + ch + "!!!",
	                            Logger.ERROR);
	                ch.terminate();
	                Core.diagnostics.occurrenceBinomial("connectionTimedout", 1, 0);
	                pprevCH = prevCH;
	                prevCH = ch;
            }
        }
	    } finally {
	        runningKillSurplusConnections = false;
	    }
    }
    
    /**
     * Gives the number of registered open connections.
     */
    public final int countConnections() {
		return openConns.get();
    }
    
    public final int countOpenLRUConnections() {
        int count = 0;
        for (Enumeration e = lru.elements() ; e.hasMoreElements() ;) {
			BaseConnectionHandler ch =
				(BaseConnectionHandler) (e.nextElement());
			if (ch.isOpen())
				count++;
        }
        return count;
    }

	List getConnectionListSnapshot() {
        List lConnections = new LinkedList();
        synchronized(lru) {
            for (Enumeration e = lru.elements() ; e.hasMoreElements() ;)
                lConnections.add(e.nextElement());
            // Extract the BaseConnectionHandlers so that we can sort them if
            // we want to
        }
        return lConnections;    
    }
    
	Hashtable getPeerHandlersHashSnapshot() {
		return peerHandlers.snapshot();
    }
    
	/**
	 * @return true if we want another node to add to the routing table.
	 * Used to prevent incoming connections from swamping the routing table
	 * given bidirectional connections. 
	 */
	public boolean wantUnkeyedReference() {
	    PeerHandler[] ph = getPeerHandlers();
	    // If no limit, ok
	    if(maxConnections <= 0) return true;
	    // If we can accept without dropping anything, go for it
	    if(ph.length < maxConnections) return true;
	    // If more than 20% of the open connections are newbie, return false
	    int newbieCount=0;
	    for(int i=0;i<ph.length;i++) {
	        PeerHandler p = ph[i];
	        if(sorter.isNewbie(p.getIdentity(), p.isConnected()))
	            newbieCount++;
	    }
	    return !(newbieCount > 
	            (maxConnections * maxNewbieFraction)); // FIXME: make configurable
    }
    
    /**
     * Calculate the total length of the send queues on all the PeerHandlers.
     * This takes both the connectionhandler and messages locks on each PH, so
     * try to avoid calling it outside stuff like writeHtmlContents.
     */
	long totalSendQueueSize() {
		long total = 0;
		Hashtable peers = getPeerHandlersHashSnapshot();
		for (Iterator i = peers.values().iterator(); i.hasNext();) {
			PeerHandler ph = (PeerHandler) (i.next());
			total += ph.queuedBytes();
		}
		return total;
	}
    
    /**
     * Writes an HTML table with information about the open connections.
     * 
     * @param pw
     *            A destination to write the HTML output to
     * @param req
     *            The http request, optionally including the following options:
	 * <dl><dt>?setSorting=</dt><dd>
     *            An optional column to sort on (0 -> natural LRU ordering of
	 *            elements)</dd>
	 * <dt>&setMode=</dt>
	 *            <dd>"new"|"old" Switches this OCM's web interface between text
	 *            only mode and graphical mode</dd>
	 * <dt>&setLevel=</dt>
	 *            <dd>In "new" mode, choose one of three detail levels.</dd></dl>
     */
    public void writeHtmlContents(PrintWriter pw,HttpServletRequest req) {
        if(req.getParameter("setMode") != null) //Check for modeswitch
            if(req.getParameter("setMode").compareTo("Peer") ==0)
                peerHandlerHTMLMode = true;
            else
                peerHandlerHTMLMode = false;
        if(peerHandlerHTMLMode)
            peerHTMLRenderer.render(pw,req);
        else
            connectionsHTMLRenderer.render(pw,req);
    }
    
    protected class PeerHandlerDataSnapshot{

		long idleTime,
			lifeTime,
			messagesQueued,
			messagesSent,
			messagesSendFailed,
			messagesReceived,
			messagesReceiveFailed,
			sendQueue,
			dataSent,
			dataReceived,
			receiveQueue,
			inboundConnectionsCount,
			outboundConnectionsCount,
			connectionAttempts,
			connectionSuccesses,
			requestsSent,
                requestsReceived;

        float outboundConnectionSuccessRatio;

        double requestInterval;

        String version,address;

        Identity identity;

        long sendingTrailers, receivingTrailers;

        PeerHandler.MessageAccounter acc;

        PeerHandlerDataSnapshot() {
        }

        void copyLargest(PeerHandlerDataSnapshot other) {
            idleTime = Math.max(idleTime,other.idleTime);
            lifeTime = Math.max(lifeTime,other.lifeTime);
            messagesQueued = Math.max(messagesQueued, other.messagesQueued);
            messagesSent = Math.max(messagesSent,other.messagesSent);
			messagesSendFailed =
				Math.max(messagesSendFailed, other.messagesSendFailed);
			messagesReceived =
				Math.max(messagesReceived, other.messagesReceived);
			messagesReceiveFailed =
				Math.max(messagesReceiveFailed, other.messagesReceiveFailed);
			requestsSent = Math.max(requestsSent,other.requestsSent);
			requestsReceived =
				Math.max(requestsReceived, other.requestsReceived);
            sendQueue = Math.max(sendQueue,other.sendQueue);
            dataSent = Math.max(dataSent,other.dataSent);
            dataReceived = Math.max(dataReceived,other.dataReceived);
			inboundConnectionsCount =
				Math.max(
					inboundConnectionsCount,
					other.inboundConnectionsCount);
			outboundConnectionsCount =
				Math.max(
					outboundConnectionsCount,
					other.outboundConnectionsCount);
			connectionAttempts =
				Math.max(connectionAttempts, other.connectionAttempts);
			connectionSuccesses =
				Math.max(connectionSuccesses, other.connectionSuccesses);
			outboundConnectionSuccessRatio =
				Math.max(
					outboundConnectionSuccessRatio,
					other.outboundConnectionSuccessRatio);
            sendingTrailers = Math.max(sendingTrailers, other.sendingTrailers);
			receivingTrailers =
				Math.max(receivingTrailers, other.receivingTrailers);
            requestInterval = Math.max(requestInterval, other.requestInterval);
        }

        PeerHandlerDataSnapshot(PeerHandler p) {
            idleTime = p.getIdleTime();
            lifeTime = p.getLifeTime();
            messagesQueued = p.getQueuedMessagesCount();
            messagesSent = p.getMessageAccounter().getMessgesSent();
            messagesSendFailed = p.getMessageAccounter().getMessgeSendsFailed();
            messagesReceived = p.getMessageAccounter().getMessagesReceived();
			messagesReceiveFailed =
				p.getMessageAccounter().getMessagesReceiveFailed();
			requestsSent =
				p.getMessageAccounter().getMessagesSentByType(
					DataRequest.messageName);
			requestsReceived =
				p.getMessageAccounter().getMessagesReceivedByType(
					DataRequest.messageName);
            version = p.ref == null ?"":p.ref.getVersion();
            address = p.ref == null ? "" : p.ref.firstPhysicalToString();
            identity = p.id;
            sendQueue = p.queuedBytes();
            dataSent = p.getMessageAccounter().getDataSent();
            dataReceived = p.getMessageAccounter().getDataReceived();
            inboundConnectionsCount = p.getInboundConnectionsCount();
            outboundConnectionsCount = p.getOutboundConnectionsCount();
            connectionAttempts = p.getOutboundConnectionAttempts();
            connectionSuccesses = p.getOutboundConnectionSuccesses();
			outboundConnectionSuccessRatio =
				p.getOutboundConnectionSuccessRatio();
            acc = p.getMessageAccounter();
            sendingTrailers = p.countSendingTrailers();
            receivingTrailers = p.countReceivingTrailers();
            requestInterval = p.getRequestInterval();
        }
    }
    
    class PeerHandlersSnapshot {

        public int totalTrailersSending;

        public int totalTrailersReceiving;

        Hashtable pPeerHandlers;

        PeerHandlerDataSnapshot maxValues = new PeerHandlerDataSnapshot();

        LinkedList lPeerHandlers = new LinkedList();

        List lPHData = new LinkedList();

		public PeerHandler.MessageAccounter allMessagesTransfered =
			new PeerHandler.MessageAccounter();
        
        PeerHandlersSnapshot(int iSortingMode) {
            pPeerHandlers = getPeerHandlersHashSnapshot();
            Enumeration e = pPeerHandlers.elements();
            while(e.hasMoreElements())
                lPeerHandlers.add(e.nextElement());
            //Build a sorter and sort. We should do this on the snapshot we
            // are producing further down really
            //but this method has worked just fine for the old OCM so we'll
            // leave it be until proven bad... /Iakin
			PeerHandler.PeerHandlerComparator sorter =
				new PeerHandler.PeerHandlerComparator(iSortingMode);
            Collections.sort(lPeerHandlers, sorter);
            Iterator it = lPeerHandlers.iterator();
            while(it.hasNext()) {
                PeerHandler ph = (PeerHandler)it.next();
                PeerHandlerDataSnapshot p = new PeerHandlerDataSnapshot(ph);
                totalTrailersReceiving += ph.countReceivingTrailers();
                totalTrailersSending += ph.countSendingTrailers();
                lPHData.add(p);
                maxValues.copyLargest(p);
				allMessagesTransfered.add(p.acc);
            }
        }
    }
    
	public boolean writeHtmlFile(
		String file,
		HttpServletRequest req,
		HttpServletResponse resp)
		throws IOException {
		if (req.getParameter("setMode") != null) //Check for modeswitch
			peerHandlerHTMLMode =
				(req.getParameter("setMode").compareTo("Peer") == 0);
		if (peerHandlerHTMLMode)
			return peerHTMLRenderer.renderFile(file, req, resp);
		else
			return connectionsHTMLRenderer.renderFile(file, req, resp);
        }
        
	/**
	 * @return an array containing all PeerHandlers known to this instance
	 */
	public PeerHandler[] getPeerHandlers() {
		Object[] objs = peerHandlers.toArray();
		PeerHandler[] p = new PeerHandler[objs.length];
		System.arraycopy(objs,0,p,0,objs.length);
		return p;
        }

	/**
	 * @param id
	 * @return
	 */
	public boolean isOpen(Identity id) {
		PeerHandler ph = getPeerHandler(id);
		if (ph == null)
			return false;
		return ph.isConnected();
        }

	public String getCheckpointName() {
		return "Expiring messages";
        }

	public long nextCheckpoint() {
		return System.currentTimeMillis() + 30000;
        }

	public void checkpoint() {
	    // Update logDEBUG
	    logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		long now = System.currentTimeMillis();
		Hashtable h = peerHandlers.snapshot();
		int count = 0;
		for (Enumeration e = h.elements(); e.hasMoreElements();) {
			PeerHandler ph = (PeerHandler) (e.nextElement());
			count += ph.expireMessages();
            }
		long end = System.currentTimeMillis();
		long diff = end - now;
		// Don't explicitly expire PHs, however we will do a sanity
		// check...
		int totalPHs = peerHandlers.size();
		int rtNodes = Main.node.rt.countNodes();
		int maxRTNodes = Node.rtMaxNodes;
            
		String report = "Expired " + count + " messages in " + (end - now) + "ms; "
			+ totalPHs + " total PeerHandlers, " + rtNodes + "/" + maxRTNodes + " in routing table"; 
			
		int severity = diff > 1000 ? Logger.NORMAL : Logger.MINOR;
            
		if(totalPHs > Math.max(rtNodes, maxRTNodes) * 1.25) {
		    report = "Too many PeerHandlers!: "+report;
		    severity = Logger.ERROR;
                }
		if(Core.logger.shouldLog(severity, this))
		Core.logger.log(this, report, severity);
		}
		
    /**
     * @param sortingMode
     * @return
     */
    PeerHandlersSnapshot getPeerHandlersSnapshot(int sortingMode) {
        return new PeerHandlersSnapshot(sortingMode);
	}

    public boolean getPeerHandlerHTMLMode() {
        return peerHandlerHTMLMode;
	}

	/**
	 * @return the highest build number that at least 3 connected nodes
	 * of different IP addresses have.
	 */
	public int getHighestSeenBuild(Node n) {
		Hashtable h = peerHandlers.snapshot();
		Hashtable builds = new Hashtable();
		tcpTransport tcp = (tcpTransport)n.transports.get("tcp");
		int count = 0;
		for(Enumeration e = h.elements(); e.hasMoreElements();) {
			PeerHandler ph = (PeerHandler) e.nextElement();
			NodeReference ref = ph.getReference();
			if(ref == null) continue;
			String version = ref.getVersion();
			String[] v = Fields.commaList(version);
			if(!Version.protocolVersion.equals(v[2])) continue;
			if(!Version.sameVersion(v)) continue;
			int build;
			try {
				build = Integer.parseInt(v[3]);
			} catch (NumberFormatException ex) {
				Core.logger.log(this, "Caught NFE parsing "+v[3]+" from "+version,
						ex, Logger.NORMAL);
				continue;
			}
			if(build <= Version.buildNumber) continue;
			Integer i = new Integer(build);
			BuildTag bt = (BuildTag) builds.get(i);
			if(bt == null) {
				builds.put(i, bt = new BuildTag(build));
			}
			tcpAddress addr;
			try {
				addr = (tcpAddress) ref.getAddress(tcp);
			} catch (BadAddressException e1) {
				Core.logger.log(this, "Caught "+e1+" getting address for "+ref,
						Logger.MINOR);
				continue;
			}
			if(addr == null) continue;
			Inet4Address ia;
			try {
				ia = (Inet4Address)addr.getHost();
			} catch (UnknownHostException e2) {
				Core.logger.log(this, "Caught "+e2+" getting address for "+addr,
						Logger.MINOR);
				continue;
			}
			if(ia == null) continue;
			count++;
			if(logDEBUG)
				Core.logger.log(this, "Got "+ia+" on build "+build,
						Logger.DEBUG);
			if(!bt.ip4addresses.contains(ia))
				bt.ip4addresses.add(ia);
		}
		// Now scan the list of builds for the most recent one
		//int highestBuild = Version.buildNumber;
		int highestBuild = 0; // FIXME: testing!
		for(Enumeration e=builds.elements(); e.hasMoreElements();) {
			BuildTag bt = (BuildTag) e.nextElement();
			if(bt.build < highestBuild) continue;
			int size = bt.ip4addresses.size();
			if(logDEBUG)
				Core.logger.log(this, "Build: "+bt.build+", "+size+
						" addresses", Logger.DEBUG);
			if(size > Math.max(3, count/20)) {
				// FIXME arbitrary and probably wrong threshold
				highestBuild = bt.build;
			}
		}
		Core.logger.log(this, "Highest seen build now: "+highestBuild,
				Logger.MINOR);
		return highestBuild;
	}
	
	class BuildTag {
		int build;
		// REDFLAG: transport specific, IPv4 specific
		LinkedList ip4addresses;
		BuildTag(int b) {
			build = b;
			ip4addresses = new LinkedList();
		}
	}

	NetworkAddressDetectionTracker addressTracker =
	    new NetworkAddressDetectionTracker();
	
    /**
     * Notify the OCM that an address has been detected.
     * @param ip4addr IP address detected
     */
    public void detected(Inet4Address ip4addr) {
        addressTracker.detected(ip4addr);
        Main.redetectAddress();
    }

    /**
     * Notify the OCM that an address is no longer detected
     * by an active link.
     * @param ip4addr the IP address to undetect.
     */
    public void undetected(Inet4Address ip4addr) {
        addressTracker.undetected(ip4addr);
        Main.redetectAddress();
    }

    /**
     * @return an HTML representation of the currently detected
     * IP addresses from the network. "" if none.
     */
    public String detectedAddressesToHTML() {
        return addressTracker.toHTMLString();
    }

    /**
     * @return the most frequently detected address, or null
     * if there are no detections.
     */
    public Inet4Address topDetectedValidAddress() {
        return addressTracker.topAddress(true);
    }

    /**
     * @return a list of all the current receiving transfers.
     */
    public String dumpTransfers() {
        PeerHandler[] ph = this.getPeerHandlers();
        StringBuffer sb = new StringBuffer(1000);
        for(int i=0;i<ph.length;i++) {
            PeerHandler p = ph[i];
            String s = p.trailerReadManager.dumpReaders();
            if(s != null) {
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /**
     * @param id
     * @return
     */
    public boolean canSendRequests(Identity id) {
		PeerHandler ph = getPeerHandler(id);
		if (ph == null)
			return false;
		return ph.canSendRequests();
    }
}
