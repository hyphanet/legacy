package freenet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.diagnostics.ExternalContinuous;
import freenet.message.DataRequest;
import freenet.message.QueryRejected;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.rt.ExtrapolatingTimeDecayingEventCounter;
import freenet.node.rt.RunningAverage;
import freenet.node.rt.TimeDecayingRunningAverage;
import freenet.support.Logger;
import freenet.support.backoff.ExponentialBackoffManager;
import freenet.support.backoff.ResettableBackoffManager;
import freenet.support.servlet.HtmlTemplate;

/**
 * Class managing and representing our communications with a remote peer.
 * Contains message queue, connection and backoff logic, etc. Despite the name,
 * a PeerHandler is unique by its Identity, and it keeps a NodeReference for
 * purposes of opening connections.
 */
public class PeerHandler {
    
    /** The node's identity - never changes */
	final Identity id;
     
    /** Can we send requests at the moment? */
    boolean canSendRequests = false;
    
	/**
     * The current reference, can be null, meaning we can only talk back over
	 * open connections.
     */
	NodeReference ref;

    private final ExternalContinuous messageSendInterarrivalTime = Core.diagnostics
            .getExternalContinuousVariable("messageSendInterarrivalTime");

    private final ExternalContinuous messageSendInterarrivalTimeNoQR = Core.diagnostics
            .getExternalContinuousVariable("messageSendInterarrivalTimeNoQR");

    private final ExternalContinuous messageSendQueueSize = Core.diagnostics.getExternalContinuousVariable("messageSendQueueSize");

    final PeerMessageQueue list=new PeerMessageQueue();
	
	private TrailerFlowCreditMessage queuedCreditMessage;

	public static class MessageAccounter {
        
		private Presentation defaultPresentation;
		
        private static class messageTypeAndStatus {

			public static String toString(Message msg,boolean success){
                // Disabled for now: probable space leak
//            	if(msg instanceof QueryRejected) //Somewhat ugly hack for QR:s, they come in a couple of different flavors that we might be interested in..
//            		return msg.getMessageName()+"/"+((QueryRejected)msg).getReason()+":"+String.valueOf(success);
//            	else
				return msg.getMessageName()+":"+String.valueOf(success);
            }
        }
		
        static private HtmlTemplate titleBoxTemplateTemplate;
        static final private Object titleBoxTemplateTemplateLock = new Object();

        private final HtmlTemplate titleBoxTemplate;

        MessageAccounter() {
			synchronized (titleBoxTemplateTemplateLock) {
				if (titleBoxTemplateTemplate == null) {
            try {
						titleBoxTemplateTemplate = HtmlTemplate.createTemplate("titleBox.tpl");
						titleBoxTemplateTemplate.set("TITLE", "Messages transferred");
            } catch (IOException e) {
                Core.logger.log(this, "Couldn't load titleBox.tpl", e, Logger.NORMAL);
            }
        }
				titleBoxTemplate = new HtmlTemplate(titleBoxTemplateTemplate);
			}
		}
		
		/**
		 * Maps from String containing message name and status to number of
		 * that messagetype received (class myInt).
		 */
		private Hashtable messagesSentByTypeAndStatus = new Hashtable();

		/**
		 * Maps from String containing message name and status to number of
		 * that messagetype received (class myInt).
		 */
		private Hashtable messagesReceivedByTypeAndStatus = new Hashtable();

		/** 
		 * Can be figured out from the hash table above but kept here as a
		 * cache for fast access.
		 */
		private long messagesSent = 0;
		
		/** 
		 * Can be figured out from the hash table above but kept here as a
		 * cache for fast access.
		 */
		private long messagesSendsFailed = 0;
		
		private long messagesReceived = 0;
        
		/** The number of bytes sent to this Peer */
		protected long dataSent = 0;
        
		/** The number of bytes received from this Peer */
		protected long dataReceived = 0;

        /** Stupid support class for mapping to an int. */
		private static class myInt {

			int intValue = 0;
		}

        protected void registerMessageSent(PeerPacketMessage m, boolean success) {
			if(m instanceof HighLevelPeerPacketMessage) {
				HighLevelPeerPacketMessage hm = (HighLevelPeerPacketMessage)m;
                if (hm.peerHandler.id == null) return; // Presentation would be
                // incompatible with Message
				m.resolve(defaultPresentation, true);
				registerMessageSent(hm.msg,hm.getLength(),success);
			}
		}

        private void registerMessageSent(Message m, int size, boolean success) {
			registerMessageSent(messageTypeAndStatus.toString(m, success));
			if (success) { //Update the message count cache
				messagesSent++;
				dataSent += size; //Should we count failed data too?
			} else {
				messagesSendsFailed++;
			}
		}

        private void registerMessageSent(String key) {
			_registerMultipleMessagesSent(key,1);
		}

		//For internal use only..
		private void _registerMultipleMessagesSent(String key, int messageCount) {
			synchronized (messagesSentByTypeAndStatus) {
				myInt count = (myInt) messagesSentByTypeAndStatus.get(key);
				if (count == null) {
					count = new myInt();
					messagesSentByTypeAndStatus.put(key, count);
				}
				count.intValue+= messageCount;
			}
		}

		protected void registerMessageReceived(Message m) {
			registerMessageReceived(m, true);
			//Keep this worthless boolean to let the user worry about ONE type
			// of HashMap key instead of two
		}

		private void registerMessageReceived(Message m, boolean success) {
			registerMessageReceived(messageTypeAndStatus.toString(m, success));
		}
		
		private void registerMessageReceived(String key) {
			_registerMultipleMessagesReceived(key,1);
		}
		
		//For internal use only..
		private void _registerMultipleMessagesReceived(String key, int messageCount) {
			synchronized (messagesReceivedByTypeAndStatus) {
				myInt count = (myInt) messagesReceivedByTypeAndStatus.get(key);
				if (count == null) {
					count = new myInt();
					messagesReceivedByTypeAndStatus.put(key, count);
				}
				count.intValue += messageCount;
			}
			//Update the message count cache
			messagesReceived += messageCount;
			//dataReceived += m.getLength(); //TODO:Fix this
		}

		public long getMessgesSent() {
			return messagesSent;
		}

		public long getMessgeSendsFailed() {
			return messagesSendsFailed;
		}

		public long getMessagesReceived() {
			return messagesReceived;
		}

		public long getMessagesReceiveFailed() {
			return 0; //TODO: Fix this or removed it?
		}

        public long getMessagesSentByType(String messageType) {
			myInt count = (myInt) messagesSentByTypeAndStatus.get(messageType+":"+String.valueOf(true));
			if(count == null)
				return 0;
			else
				return count.intValue;
		}

        public long getMessagesReceivedByType(String messageType) {
			myInt count = (myInt) messagesReceivedByTypeAndStatus.get(messageType+":"+String.valueOf(true));
			if(count == null)
				return 0;
			else
				return count.intValue;
		}
		
		public long getDataSent() {
			return dataSent;
		}

		public long getDataReceived() {
			return dataReceived;
		}

		public void add(MessageAccounter other) {
			dataReceived += other.dataReceived;
			dataSent += other.dataSent;
			messagesReceived += other.messagesReceived;
			messagesSent += other.messagesSent;
			synchronized (messagesReceivedByTypeAndStatus) {
				synchronized (other.messagesReceivedByTypeAndStatus) {
                    Enumeration e = other.messagesReceivedByTypeAndStatus.keys();
					while (e.hasMoreElements()) {
						String key = (String) e.nextElement();
						_registerMultipleMessagesReceived(key,((myInt)other.messagesReceivedByTypeAndStatus.get(key)).intValue);
					}
				}
			}
			synchronized (messagesSentByTypeAndStatus) {
				synchronized (other.messagesSentByTypeAndStatus) {
					Enumeration e = other.messagesSentByTypeAndStatus.keys();
					while (e.hasMoreElements()) {
						String key = (String) e.nextElement();
						_registerMultipleMessagesSent(key,((myInt)other.messagesSentByTypeAndStatus.get(key)).intValue);
					}
				}
			}

		}

		private static class MessageCountByType {

			String messageType;

			int sent = 0;

			int sendFailed = 0;

			int received = 0;

			int receiveFailed = 0;
		}

		public String toHTML(boolean renderFailCount) {
            StringWriter sw = new StringWriter();
            PrintWriter psw = new PrintWriter(sw);
            StringBuffer content = new StringBuffer();

			Hashtable h = new Hashtable();

			synchronized (messagesSentByTypeAndStatus) {
				Enumeration eSent = messagesSentByTypeAndStatus.keys();
				while (eSent.hasMoreElements()) {
					String key = (String) eSent.nextElement();
					String messageType = key.substring(0, key.indexOf(':'));
                    String messageStatus = key.substring(key.indexOf(':') + 1, key.length());
                    int sentCount = ((myInt) messagesSentByTypeAndStatus.get(key)).intValue;
                    MessageCountByType val = (MessageCountByType) h.get(messageType);
					if (val == null) {
						val = new MessageCountByType();
						val.messageType = messageType;
						h.put(val.messageType, val);
					}
					if (messageStatus.equalsIgnoreCase(String.valueOf(true)))
						val.sent += sentCount;
					else
						val.sendFailed += sentCount;

				}
			}
			synchronized (messagesReceivedByTypeAndStatus) {
				Enumeration eSent = messagesReceivedByTypeAndStatus.keys();
				while (eSent.hasMoreElements()) {
					String key = (String) eSent.nextElement();
					String messageType = key.substring(0, key.indexOf(':'));
                    String messageStatus = key.substring(key.indexOf(':') + 1, key.length());

                    int receivedCount = ((myInt) messagesReceivedByTypeAndStatus.get(key)).intValue;
                    MessageCountByType val = (MessageCountByType) h.get(messageType);
					if (val == null) {
						val = new MessageCountByType();
						val.messageType = messageType;
						h.put(val.messageType, val);
					}
					if (messageStatus.equalsIgnoreCase(String.valueOf(true)))
						val.received += receivedCount;
					else
						val.receiveFailed += receivedCount;
				}
			}
			if (h.size() > 0) {
                content.append("<table>");
				if (renderFailCount)
                    content
                            .append("<tr><th class=\"ocmPeerMessageTypeTitle\">Type</th><th class=\".ocmPeerMessageSentReceivedTitle\">Sent&nbsp;(failed)/Received&nbsp;(failed)</th></tr>");
				else
                    content
                            .append("<tr><th class=\"ocmPeerMessageTypeTitle\">Type</th><th class=\".ocmPeerMessageSentReceivedTitle\">Sent/Received</th>");

                LinkedList l = new LinkedList();
				l.addAll(h.keySet());
                Collections.sort(l);
                Iterator it = l.iterator();

                if (it.hasNext()) {
                    while (it.hasNext()) {
                        MessageCountByType val = (MessageCountByType) h.get(it.next());
					if (renderFailCount)
                            content.append("<tr><td class=\"ocmPeerMessageType\">" + val.messageType
                                    + "</td><td class=\"ocmPeerMessageSentReceived\">" + val.sent + "&nbsp;(" + val.sendFailed + ")/" + val.received
                                    + "&nbsp;(" + val.receiveFailed + ")</td></tr>");
					else
                            content.append("<tr><td class=\"ocmPeerMessageType\">" + val.messageType
                                    + "</td><td class=\"ocmPeerMessageSentReceived\">" + val.sent + "/" + val.received + "</td></tr>");
                    }
				}
                content.append("</table>");
			} else
                content.append("No&nbsp;messages&nbsp;transferred&nbsp;yet");

            titleBoxTemplate.set("CONTENT", content.toString());
            titleBoxTemplate.toHtml(psw);
            return sw.toString();
		}

	}
	
	// Trailers (muxed)
	final MuxTrailerWriteManager trailerWriteManager;

	final MuxTrailerReadManager trailerReadManager;
	
    private final MessageAccounter messageAccounter = new MessageAccounter();

    private final CopyOnWriteArrayList connectionHandlers = new CopyOnWriteArrayList();

    private final Node node;
    
	private long trailerChunkQueuedBytes;

    // OCM is OpenConnectionManager
	private boolean removingFromOCM = false;

    private volatile boolean removedFromOCM = true;

	private Object removedFromOCMLock = new Object();

	private long lastMessageQueuedTime = -1;

    private long lastMessageQueuedTimeNoQR = -1;

    private long lastMessageSentTime = -1;
    
    private Object rejectOldVersionLock = new Object();

    private final long initialRejectOldVersionTime = 250;

    private long rejectOldVersionTime = initialRejectOldVersionTime;

    long lastRegisterTime = 0;

    private final long creationTime = System.currentTimeMillis();

    private Object outboundDiagnosticsSync = new Object();

    private long lastOutboundAttemptTime = -1;

    private long lastOutboundSuccessTime = -1;

    private long lastOutboundFailureTime = -1;

    private long timeConnected = -1;

    private int totalOutboundSuccesses = 0;

    private int totalOutboundAttempts = 0;

    private int totalOutboundFailures = 0;

    private int consecutiveOutboundFailures = 0;

    private boolean logDEBUG;

    private int maxPacketSize;

    private long lastHadOpenConnection = -1;

	// Packet priorities
	public static final int EXPENDABLE = 0;

	public static final int NORMAL = 1;

    /*** Connection opener backoff ***/
    //Initial connection backoff time used after both connection success and connection failure
    private final static int INITIAL_BACKOFF_LENGTH = 10*1000;
    
    private final ResettableBackoffManager connectionFailureBackoffManager = new ExponentialBackoffManager(INITIAL_BACKOFF_LENGTH);
    
	// Requests sent to this node over the last X millis
    private final ExtrapolatingTimeDecayingEventCounter recentlyReceivedRequests;
	
	public void actuallyReceivedRequest() {
	    recentlyReceivedRequests.logEvent();
	}
	
	public String toString() {
        return super.toString() + " (" + id + "," + ref + "): outbound attempts=" + totalOutboundSuccesses + ":" + totalOutboundFailures + "/"
			+ totalOutboundAttempts;
	}

	public long queuedBytes() {
		long queuedBytes = list.queuedBytes(messageAccounter.defaultPresentation);
		Iterator i = connectionHandlers.iterator();
			while(i.hasNext()) {
				BaseConnectionHandler ch = (BaseConnectionHandler)(i.next());
				long a = ch.trailerLengthAvailable();
			if (a > 0)
				queuedBytes += a;
		}
		return queuedBytes;
		// We can't find out the message length without resolving the messages
	}

	public int getInboundConnectionsCount() {
		int retval = 0;
		Iterator i = connectionHandlers.iterator();
			while(i.hasNext()) {
				BaseConnectionHandler ch = (BaseConnectionHandler)(i.next());
			if (!ch.isOutbound())
				retval++;
		}
		return retval;
	}
	public int getOutboundConnectionsCount() {
		int retval = 0;
		Iterator i = connectionHandlers.iterator();
			while(i.hasNext()) {
				BaseConnectionHandler ch = (BaseConnectionHandler)(i.next());
			if (ch.isOutbound())
				retval++;
		}
		return retval;
	}

	public long getIdleTime() {
        if(!list.isEmpty()) return 0; // sending...
		if (lastMessageSentTime > 0)
			return System.currentTimeMillis() - lastMessageSentTime;
		else
            return System.currentTimeMillis() - creationTime;
    }
    
    public long getRealIdleTime() {
        if (lastMessageSentTime > 0)
            return System.currentTimeMillis() - lastMessageSentTime;
        else
            return System.currentTimeMillis() - creationTime;
	}

	public MessageAccounter getMessageAccounter() {
		return messageAccounter;
	}

	public long getLifeTime() {
		return System.currentTimeMillis() - creationTime;
	}

	public int getConnectionsCount() {
		return connectionHandlers.size();
	}

	/**
	 * Register a ConnectionHandler to us. This should be called as soon as it
	 * has completed negotiations, not waiting for the Identify.
	 */
    public void registerConnectionHandler(BaseConnectionHandler ch) throws RemovingPeerHandlerException {
        if (removingFromOCM) throw new RemovingPeerHandlerException();
		lastRegisterTime = System.currentTimeMillis();
        if (Core.logger.shouldLog(Logger.MINOR, this)) Core.logger.log(this, "Registering " + ch + " on " + this, Logger.MINOR);
        Iterator it = null;
        if (ch instanceof MuxConnectionHandler) {
             it = connectionHandlers.iterator(); //Take a snapshot of the old list
			}
			connectionHandlers.add(ch);
        setResetSendPacket();

        int counter = 0;
        
        if (it != null) { //If we added a MuxConnectionHandler, remove all other/old connectionHandlers
            while(it.hasNext()){
            	BaseConnectionHandler bch = ((BaseConnectionHandler) (it.next()));
            	if(bch.isOpen()) counter++;
                bch.terminate();
			}
		}
        if(counter <= 1) timeConnected = System.currentTimeMillis();
        node.queueManager.runQueue();
	}

	/**
     * Unregister a BaseConnectionHandler. This should be called after we have
     * lost the ability to send and receive messages on the CH.
	 */
    public void unregisterConnectionHandler(BaseConnectionHandler ch) {
		if (Core.logger.shouldLog(Logger.MINOR, this))
			Core.logger.log(this, "Unregistering " + ch + " on " + this, Logger.MINOR);
		if(!connectionHandlers.remove(ch))
			Core.logger.log(this, "Failed to remove " + ch + " on " + this+", CH not registered with this PH",new Exception("debug"), Logger.ERROR);
		Iterator it = connectionHandlers.iterator();
		boolean empty = false;
		if(connectionHandlers.isEmpty()) empty = true;
		else {
			while(it.hasNext()) {
				if(((BaseConnectionHandler)it.next()).isOpen()) empty = false;
			}
		}
		if (empty) {
			canSendRequests = false;
			timeConnected = -1;
			if (ch.messagesReceived() > 0)
				lastHadOpenConnection = System.currentTimeMillis();
		}
		node.queueManager.runQueue();
	}
	
	public void removeFromOCM() {
		removingFromOCM = true;
        if (logDEBUG) Core.logger.log(this, "Removing from OCM... " + this, Logger.DEBUG);
			if(!list.isEmpty())
                Core.logger.log(this, "Lost all connections for " + id + ", but " + list.messageCount() + " messages left when removing from OCM",
                        Logger.MINOR);
		list.failAllMessages(new SendFailedException(null, id, "Removing from OCM", true));
		
        if (id != null) node.connections.removePeerHandler(id);
		synchronized (removedFromOCMLock) {
			removedFromOCM = true;
			removedFromOCMLock.notifyAll();
		}
        if (logDEBUG) Core.logger.log(this, "Removed from OCM: " + this, Logger.DEBUG);
        node.queueManager.runQueue();
	}

	public void waitForRemovedFromOCM() {
		while (!removedFromOCM) {
			synchronized (removedFromOCMLock) {
                if (removedFromOCM) return;
				try {
					removedFromOCMLock.wait(200);
				} catch (InterruptedException e) {
                    // Keep waiting
				}
			}
		}
	}

	/**
	 * Set the NodeReference. If the new NodeReference supersedes the old one,
     * it will be set. This is the same logic used in the routing table. Should
     * be called amonst other occasions by BaseConnectionHandler when we
	 * receive an Identify message.
     * 
	 * @return the current NodeReference
	 */
	public NodeReference updateReference(NodeReference nr) {
        if (logDEBUG) Core.logger.log(this, "updateReference(" + nr + ") on " + this, new Exception("debug"), Logger.DEBUG);
		if (nr.supersedes(ref)) {
            if (logDEBUG) Core.logger.log(this, "superceded old ref " + ref + " on " + this, Logger.DEBUG);
			ref = nr;
		}
		return ref;
	}

	public NodeReference getReference() {
		return ref;
	}

	/**
     * @return true if we are connected
	 */
	public boolean isConnected() {
		//Process outline:
		//1. Do a couple of quick, low-overhead, tests that catch a few common cases
		//2. If the above tests didn't work out, fall back to query all of our CHs for their openess status

		//Take a peek at the first connectionhandler we have available
		BaseConnectionHandler first = (BaseConnectionHandler) connectionHandlers.first();
		if(first == null) //If we didn't even have a CH we definitely aren't connected
			return false;
		else{
			if(first.isOpen()) //If we have at least one CH, and it is open, we are connected 
				return true;
		}
		
		//No need to iterate over out CHs if the first one is our only one
		//(This is a very common situation since we are mostly using MuxConnectionHandlers nowadays)
		if(connectionHandlers.size()<2)
			return false;

		//Fall back to the slowest test of them all..iterate over all CHs
		Iterator it = connectionHandlers.iterator();
		while (it.hasNext()) {
			BaseConnectionHandler ch = (BaseConnectionHandler) it.next();
				if(!ch.isOpen()) {
					continue;
			}
				return true;
		}
		return false;
	}

	public void unsendMessage(PeerPacketMessage pm) {
		unsendMessage(pm, null);
	}

	public void unsendMessage(MessageSendCallback cb) {
		unsendMessage(null, cb);
	}

	/**
	 * Remove a message by either PeerPacketMessage == or by
	 * MessageSendCallback == Note that they have to be exactly the same
	 * object. Note also that it will remove the first object found then
	 * return.
	 */
	public void unsendMessage(PeerPacketMessage pm, MessageSendCallback cb) {
		PeerPacketMessage msg = list.remove(pm, cb);
		if(msg != null) {
            if (msg instanceof TrailerChunkPacketMessage) trailerChunkQueuedBytes -= msg.getLength();
		}
	}

    /**
     * Create a PeerHandler
     * 
     * @param id
     *            the identity of the peer. Null for an FCP connection.
     * @param ref
     *            the initial NodeReference of the peer - can be null, meaning
     *            we don't know yet, but we can reply over currently open
     *            conns.
     * @param n
     *            the Node.
     * @param maxPacketSize
     *            the maximum length allowable for sending packets.
     * @param defaultPresentationForStats
     *            the default Presentation - purely for purposes of stats.
     */
    public PeerHandler(Identity id, NodeReference ref, Node n, int maxBufferSize, int maxPacketSize, Presentation defaultPresentationForStats) {
		this.id = id;
		this.ref = ref;
		this.node = n;
        this.maxPacketSize = Node.padPacketSize(maxPacketSize);
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        messageAccounter.defaultPresentation = defaultPresentationForStats;
		trailerReadManager = new MuxTrailerReadManager(this, maxBufferSize);
		trailerWriteManager = new MuxTrailerWriteManager(this);
        recentlyReceivedRequests = new ExtrapolatingTimeDecayingEventCounter(Node.rateLimitingInterval, 100);
        receivedRequestAverage = new TimeDecayingRunningAverage(-1, Node.rateLimitingInterval/2, 0, 1000*3600*24*7); // initial
    }
	
	public long timeSinceLastMessageSent() {
		return System.currentTimeMillis() - lastMessageSentTime;
	}

	public long rejectOldVersion(boolean reset) {
		synchronized (rejectOldVersionLock) {
			if (reset) {
				rejectOldVersionTime = initialRejectOldVersionTime;
			} else {
				rejectOldVersionTime <<= 1;
			}
			return rejectOldVersionTime;
		}
	}

	/**
	 * Start an asynchronous message send Call the callback provided when it is
	 * finished
	 */
    public void sendMessageAsync(Message r, MessageSendCallback cb, long timeout, int msgPrio) {
		PeerPacketMessage pm;
		try {
            pm = new HighLevelPeerPacketMessage(r, cb, msgPrio, timeout, this);
			innerSendMessageAsync(pm);
		} catch (NoMoreTrailerIDsException e) {
            Core.logger.log(this, "Cannot send " + r + " - too many trailers already being sent!: " + e, Logger.NORMAL);
            if (cb != null)
                cb.thrown(e);
        }
		}

    public boolean removingFromOCM() {
        return removingFromOCM;
	}
	
    /**
     * Try to send a message immediately.
     * @param pm the message to send, null if we want to try to send
     * any queued message without any specific message to send, e.g. if
     * we've been disabled for a while.
     */
	void innerSendMessageAsync(PeerPacketMessage pm) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);

        ConnectionSearchResult findResult;

        while (true) {
         // Possibilities:
            //	1. Idle BaseConnectionHandlers Strategy: pick one, make it send a
            // single
         // message packet
            // 2. No Idle BaseConnectionHandlers Strategy: enqueue message and
            // wait for
         // one to ask us for a packet
		if (removingFromOCM) {
        	if(pm != null) {
                    SendFailedException sfe = new SendFailedException(null, id,
                            "Removing from OCM", true);
			pm.notifyFailure(sfe);
                    if (logDEBUG)
                            Core.logger.log(this, "Dumped message " + pm
                                    + ": removing from OCM (" + this + ")",
                                    Logger.DEBUG);
        	}
            return;
		}
        if(pm != null && pm.expiryTime() <= 0)
                    Core.logger.log(this, "No timeout set on message!: " + pm,
                            new Exception("debug"), Logger.ERROR);
            if (logDEBUG)
                    Core.logger.log(this, "Sending " + pm + " on " + this,
                            new Exception("debug"), Logger.DEBUG);
		lastMessageSentTime = System.currentTimeMillis();
            findResult = findFirstFreeConnection();
        	if(canSendRequests || !pm.isRequest()) {
    	    	// Either we can send requests, or it's not a request.
    	    	// Try to send it NOW.
            while (findResult.conn != null) {//Did we find a candidate for
                                             // sending the message on?
			if (logDEBUG)
                        Core.logger.log(this, "About to send packet " + pm
                                + " over " + findResult.conn
                                + " in peerhandler " + this, Logger.DEBUG);
			if (findResult.conn.forceSendPacket(pm)) {
				return; //Sent it!
			} else {
				//Urk.. that was a quite rotten candidate
                    //(probably due to us running without synchronization
                    // here)..
				//..try locating another one instead
				findResult =findFirstFreeConnection();
						continue;
					}
			}
		
		// If no message to send, we're done.
		if (pm == null) return;
		
        	}else{
        		//Warn about the fact that routing doesn't seem do do its job perfectly.. We shouldn't yet receive any requests for sending
                Core.logger.log(this, "Received a message '"+pm+
                        "' which cannot be sent right now (cannot send requests yet) on " + this+", enqueueing it", Logger.ERROR);
        	}

            // No suitable, free, BaseConnectionHandler found. Enqueue the
            // message
            if (logDEBUG)
                    Core.logger.log(this, "Queueing " + pm + " on " + this,
                            Logger.DEBUG);
        
            //Special case.. will we probably never be able to send the message..
        if (findResult.sendingConnCount == 0 && probablyNotConnectable()) {
                // If it's FCP, or if it's not got a return address, and we
                // can't send now or wait for a packet send to finish, then 
                // dump the message
            String sfeMsg = (ref == null || ref.noPhysical()) ? "No open connections and no way to contact node"
					: "No open connections and probably can't contact node";
                SendFailedException sfe = new SendFailedException(null, id,
                        sfeMsg, true);
			pm.notifyFailure(sfe);
                if (Core.logger.shouldLog(id == null ? Logger.NORMAL
                        : Logger.MINOR, this))
                        Core.logger.log(this,
                                "Failed to send packet, no more conns, "
                                        + sfeMsg + ": DISCARDING " + pm
                                        + " on " + this,
					id == null ? Logger.NORMAL : Logger.MINOR);
			// id == null => FCP
			return;
		} else {
			if (logDEBUG)
                        Core.logger.log(this, "handlersSendingPacket = "
                                + findResult.sendingConnCount
                                + ", probablyNotContactable() = "
                            + probablyNotConnectable(), Logger.DEBUG);
		}
        
        //If it was a message of expendable prio and we were unable
        //to send it directly, just drop it
            //TODO: Why??? Wouldn't it be better to enqueue it and let it time
            // out instead?
        //btw.. no packets are of prio EXPENDABLE as of now...
		if (pm.getPriorityClass() == PeerPacketMessage.EXPENDABLE) {
            if (Core.logger.shouldLog(Logger.MINOR, this))
                        Core.logger.log(this,
                                "Discarding low priority message " + pm
                                        + " on " + this, Logger.MINOR);
			pm.notifyFailure(null);
			return;
		}

            if (lastMessageQueuedTime == -1)
                    lastMessageQueuedTime = System.currentTimeMillis();
            messageSendInterarrivalTime.count(System.currentTimeMillis()
                    - lastMessageQueuedTime);
            boolean isQR = pm instanceof HighLevelPeerPacketMessage
                    && ((HighLevelPeerPacketMessage) pm).msg instanceof QueryRejected;
		if (!isQR) {
                if (lastMessageQueuedTimeNoQR == -1)
                        lastMessageQueuedTimeNoQR = System.currentTimeMillis();
                messageSendInterarrivalTimeNoQR.count(System
                        .currentTimeMillis()
                        - lastMessageQueuedTimeNoQR);
		}

            synchronized (resetSendPacketSync) {
                if (resetSendPacket) {
                    resetSendPacket = false;
                    continue;
                }
            }
        doEnqueueMessage(pm);
            synchronized (resetSendPacketSync) {
                if (resetSendPacket) {
                    resetSendPacket = false;
                    pm = null;
                    continue;
                }
            }
            break;
        }
        if (findResult.sendingConnCount > 0) {
            // One of them will call us when they are free
        } else {
            // :(
            // Just better wait I suppose
        }
    }

    //Adds another message to the queue of messages to send
    //Handles accounting of flow control credits
	private void doEnqueueMessage(PeerPacketMessage pm) {
	    if(logDEBUG)
	        Core.logger.log(this, "Enqueueing "+pm+" on "+this,
	                Logger.DEBUG);
		synchronized(list) {
			if(pm instanceof TrailerFlowCreditMessage) {
				TrailerFlowCreditMessage fm = (TrailerFlowCreditMessage)pm;
				if(queuedCreditMessage == null)
					queuedCreditMessage = (TrailerFlowCreditMessage)pm;
				else {
					int totalCredit = queuedCreditMessage.creditLength + fm.creditLength;
					if(totalCredit > 65535) {
						list.addLast(fm); // send it the old fashioned way..
						queuedCreditMessage.creditLength = totalCredit - 65535;
					}
				}
			} else {
				if (pm instanceof TrailerChunkPacketMessage)
					trailerChunkQueuedBytes += pm.getLength();
			    list.addLast(pm);
			}
		}
		messageSendQueueSize.count(list.messageCount());
			}

	private static class ConnectionSearchResult{
		final BaseConnectionHandler conn;
		final int sendingConnCount;
		ConnectionSearchResult(BaseConnectionHandler conn,int sendingConnCount){
			this.conn = conn;
			this.sendingConnCount = sendingConnCount;
		}
	}
	private ConnectionSearchResult findFirstFreeConnection() {
		int sendingCount = 0;
		Iterator it = connectionHandlers.iterator(); //Take a snapshot of the current contents...
		while (it.hasNext()) { //And start looking for a suitable one..
			BaseConnectionHandler ch = (BaseConnectionHandler) it.next();
			if (!ch.isOpen()) {
				// Can't send messages
				if (logDEBUG)
					Core.logger.log(this, ch + " in " + this + " is closed", Logger.DEBUG);
				//e.remove(); Keep the connection in the PH for better
				// by-the-peer accouting in OCM PH HTML. It will not be
				// used since it is closed anyways
				continue;
			}
			if (ch.blockedSendingTrailer()) {
				// Will return false if mux
				if (logDEBUG)
					Core.logger.log(this, ch + " in " + this + " is already sending a trailer", Logger.DEBUG);
				continue;
			}
			if (ch.isSendingPacket()) {
				if (logDEBUG)
					Core.logger.log(this, ch + " in " + this + " is already sending a packet", Logger.DEBUG);
				sendingCount++;
				continue;
			}
			//Finally.. one that is not busy, return it!
			return new ConnectionSearchResult(ch, sendingCount);
		}
		return new ConnectionSearchResult(null, sendingCount); //Too bad, all busy or otherwise unavailable
	}

	public boolean probablyNotConnectable() {
        return id == null || ref == null || ref.noPhysical()
        	|| (!Version.checkGoodVersion(ref.getVersion()))
                || (totalOutboundAttempts > 1 && totalOutboundSuccesses == 0 && totalOutboundFailures > 1);
	}
	
    public boolean notContactable() {
        // Give it a chance if we haven't talked to it at all or we haven't talked to it in 24 hours.
        return id == null || ref == null || ref.noPhysical() ||
        	(ref.badVersion() && System.currentTimeMillis() - lastHadOpenConnection < 24 * 60 * 60 * 1000);
	}

    public final PeerPacket getPacket(Presentation p) {
        return getPacket(p, null, (PeerPacketMessage) null, null, false, false);
	}

    /**
     * Get a packet to send on a connection for this PeerHandler.
     * @param p
     * @param i
     * @param m
     * @param timeout
     * @param onlyGivenMsg
     * @return
     * @throws IOException
     */
    public PeerPacket getPacket(Presentation p, Identity i, Message m, long timeout, boolean onlyGivenMsg) throws IOException {
		PeerPacketMessage pm = null;
        //TODO: Is there a reason we take an Identity in? We should already know it..? Remove?!
        //Previously we where delivering this id to the HighLevelPeerPacketMessage below
        //.. due to refactoring we dont do that anymore..
        if(i != null && (i.toString() != id.toString()))
    		Core.logger.log(this, "Not my ID!", Logger.ERROR);
        
		if(m != null) {
			try {
                pm = new HighLevelPeerPacketMessage(m, null, PeerPacketMessage.NORMAL, timeout, this);
			} catch (NoMoreTrailerIDsException e) {
                Core.logger.log(this, "Could not send " + m + " - too many trailers already in flight to node!: " + e, Logger.NORMAL);
				throw new IOException(e.toString());
			}
		}
        return getPacket(p, i, pm, null, onlyGivenMsg, false);
	}
	
	/**
	 * Get a packet to send. Called by a ConnectionHandler. It will then send
	 * it. synchronized so as to avoid isSendingPacket() race with
	 * sendMessageAsync. Returns null if nothing to send.
	 * 
     * NOTE: very important that the message 'm' is included in the resulting packet, 
	 * if not it will probably be lost!
     * 
	 * @param m
	 *            link specific message to prepend to the packet, or null if
	 *            not needed. Used for such hacks as the Identify message.
	 * @param m2  another message to send. Null if not needed.
	 * @param i
	 *            identity for the message - not needed unless we m != null.
	 * @param onlyGivenMsg
	 *            if true, only include the given message, used for starting a
	 *            close dialog.
	 * @param mux
	 *            if true, the packet should be set up for multiplexing
	 */
    public PeerPacket getPacket(Presentation p, Identity i, PeerPacketMessage m, PeerPacketMessage m2, boolean onlyGivenMsg, boolean mux) {
		
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);  
        messageSendQueueSize.count(list.messageCount());
		LinkedList packetMessages = new LinkedList();
		if(logDEBUG)
                Core.logger.log(this, "getPacket(" + p + "," + i + "," + m + "," + onlyGivenMsg + " on " + this + " ("
                        + list.messageCount() + " messages)", Logger.DEBUG);
        onlyGivenMsg |= innerQueue(packetMessages, p, m);
        onlyGivenMsg |= innerQueue(packetMessages, p, m2);
		if(queuedCreditMessage != null) {
			queuedCreditMessage.resolve(p, false);
			packetMessages.add(queuedCreditMessage);
			queuedCreditMessage = null;
            if(logDEBUG)
                Core.logger.log(this, "Queued "+queuedCreditMessage, Logger.DEBUG);
		}
		if (!onlyGivenMsg) {
            if(logDEBUG)
                Core.logger.log(this, "Picking messages", Logger.DEBUG);
            PeerMessageQueue.MessagePicker picker = list.getPicker(p, id, canSendRequests);
            if(logDEBUG)
                Core.logger.log(this, "Got Picker "+picker, Logger.DEBUG);
            PeerMessageQueue.MessagePicker.PickResult r = picker.pick(maxPacketSize, !mux); 
            if(logDEBUG)
                Core.logger.log(this, "Picked: "+r, Logger.DEBUG);
	    // Pick up to the maximum size (stop at trailermessages if we aren't multiplexing)
			this.trailerChunkQueuedBytes -= r.trailerChunkBytes;
			packetMessages.addAll(r.pickedMessages);
            if(logDEBUG)
                Core.logger.log(this, "Picked messages", Logger.DEBUG);
		}

		if (packetMessages.isEmpty()) {
            if (logDEBUG) Core.logger.log(this, "Returning null from getPacket (" + this + ")", Logger.DEBUG);
			return null;
		} else {
            PeerPacketMessage[] msgs = new PeerPacketMessage[packetMessages.size()];
			packetMessages.toArray(msgs);
            if(logDEBUG)
                Core.logger.log(this, "Creating PeerPacket with "+msgs.length+" messages", Logger.DEBUG);
            PeerPacket packet = new PeerPacket(msgs, p, this, mux);
            if (logDEBUG) Core.logger.log(this, "Returning " + packet + " from getPacket (" + this + ")", new Exception("debug"), Logger.DEBUG);
			return packet;
		}
	}

	/**
     * @param packetMessages
     * @param p
     * @param m2
     */
    private boolean innerQueue(LinkedList packetMessages, Presentation p, PeerPacketMessage m) {
        boolean onlyGivenMsg = false;
        if (m != null) {
        		packetMessages.add(m);
        		m.resolve(p, false);
        		if (m.hasTrailer() || m.getLength() > maxPacketSize) onlyGivenMsg = true;
            if(logDEBUG)
                Core.logger.log(this, "Done message "+m, Logger.DEBUG);
        }
        return onlyGivenMsg;
    }

	//Messages sent accounting.
	protected void registerMessageSent(PeerPacketMessage m, boolean success) {
		messageAccounter.registerMessageSent(m, success);
	}

	//Messages received accounting.
	protected void registerMessageReceived(Message m) {
		messageAccounter.registerMessageReceived(m);
	}

    public TrailerWriter sendMessage(Message m, long timeout) throws SendFailedException {
        return sendMessage(m, timeout, PeerPacketMessage.NORMAL);
	}

	/**
	 * Send a message synchronously
	 * 
	 * @return a stream to write the trailing message to, or null if the
	 *         message does not have a trailing field.
	 */
    public TrailerWriter sendMessage(Message r, long timeout, int msgPrio) throws SendFailedException {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		MyMessageSendCallback cb = new MyMessageSendCallback();
		long startAt = System.currentTimeMillis();
		PeerPacketMessage pm;
		try {
            pm = new HighLevelPeerPacketMessage(r, cb, msgPrio, timeout, this);
		} catch (NoMoreTrailerIDsException e1) {
            Core.logger.log(this, "Could not send " + r + ": " + e1, Logger.NORMAL);
            throw new SendFailedException(null, id, e1.toString(), true);
		}
		innerSendMessageAsync(pm);
        if (logDEBUG) Core.logger.log(this, "Sent " + pm + " async", Logger.DEBUG);
		long timeoutAt = startAt + timeout;
		long maxTimeoutAt = startAt + 300 * 1000;
		// 5 mins hardcoded FIXME specific to fast protocols
        if (maxTimeoutAt < timeoutAt) maxTimeoutAt = -1;
		synchronized (cb) {
            if (logDEBUG) Core.logger.log(this, "Synced", Logger.DEBUG);
			while (!cb.finished) {
                if (logDEBUG) Core.logger.log(this, "Waiting...", Logger.DEBUG);
                if (maxTimeoutAt != -1 && System.currentTimeMillis() > maxTimeoutAt) {
                    Core.logger.log(this, "Took more than 5 minutes to send " + pm + "!!", Logger.NORMAL);
					break;
				}
				try {
					long waitTime;
					if (timeout > 0) {
						waitTime = timeoutAt - System.currentTimeMillis();
                        if (waitTime < 0) break;
					} else
						waitTime = 0;
                    if (waitTime <= 0) waitTime = 2000;
					cb.wait(waitTime);
				} catch (InterruptedException e) {
                    // Keep waiting
				}
			}
		}
		if (!cb.finished) {
            if (logDEBUG) Core.logger.log(this, "Not finished", Logger.DEBUG);
			// Couldn't send message
			unsendMessage(pm);
			throw new SendFailedException(null, // FIXME?
			id, "Timed out sending message", false);
		}
		if (cb.e != null) {
			if (cb.e instanceof SendFailedException)
				throw (SendFailedException) (cb.e);
			else {
                Core.logger.log(this, "Got unexpected exception: " + cb.e + " sending " + r, Logger.ERROR);
                SendFailedException e = new SendFailedException(null, // FIXME?
	id, "Unexpected exception " + cb.e, false);
				e.initCause(cb.e);
				throw e;
			}
		} else
			return cb.trailer;
	}

	/**
	 * Helper class used to emulate blocking sends.
	 */
	class MyMessageSendCallback implements MessageSendCallback {

		boolean finished = false;

		Exception e = null;

		TrailerWriter trailer = null;

		public void succeeded() {
            if (logDEBUG) Core.logger.log(this, "Succeeded " + this, new Exception("debug"), Logger.DEBUG);
			finished = true;
			synchronized (this) {
				this.notify();
			}
		}

        public void thrown(Exception ex) {
            if (logDEBUG) Core.logger.log(this, "Failed: " + ex + " (" + this + ")", ex, Logger.DEBUG);
            this.e = ex;
			finished = true;
			synchronized (this) {
				this.notify();
			}
		}

		public void setTrailerWriter(TrailerWriter tw) {
            Core.logger.log(this, "setTrailerWriter(" + tw + ")", new Exception("debug"), Logger.DEBUG);
			trailer = tw;
		}
	}

	// Try to determine contactability
	public void attemptingOutboundConnection() {
		synchronized (outboundDiagnosticsSync) {
			lastOutboundAttemptTime = System.currentTimeMillis();
			totalOutboundAttempts++;
		}
	}

	public void succeededOutboundConnection() {
		synchronized (outboundDiagnosticsSync) {
			lastOutboundSuccessTime = System.currentTimeMillis();
			totalOutboundSuccesses++;
            consecutiveOutboundFailures = 0;
		}
        connectionFailureBackoffManager.resetBackoff();
	}

	public void failedOutboundConnection() {
		synchronized (outboundDiagnosticsSync) {
			lastOutboundFailureTime = System.currentTimeMillis();
			totalOutboundFailures++;
            consecutiveOutboundFailures++;
		}
        connectionFailureBackoffManager.backoff();
	}

	public long getOutboundConnectionAttempts() {
		return totalOutboundAttempts;
	}

	public long getOutboundConnectionSuccesses() {
		return totalOutboundSuccesses;
	}

	public float getOutboundConnectionSuccessRatio() {
		return (float) totalOutboundSuccesses / (float) totalOutboundAttempts;
	}

	/**
	 * @author Iakin Utility for sorting PeerHandlers
	 */
	public static class PeerHandlerComparator implements java.util.Comparator {

		public static final int UNORDERED = 0; //DONE

		public static final int PEER_ADDRESS = 1; //DONE

		public static final int PEER_IDENTITY = 2; //DONE

		public static final int SENDING_COUNT = 3;

		public static final int SENDQUEUE = 4; //DONE

		public static final int RECEIVING_COUNT = 5;

		public static final int IDLETIME = 7; //DONE

		public static final int LIFETIME = 8; //DONE

		//public static final int SENDING = 10;
		public static final int ROUTING_ADDRESS = 11; //DONE

		public static final int DATASENT = 12; //DONE

		public static final int RECEIVEQUEUE = 13;

		public static final int DATARECEIVED = 14; //DONE

		public static final int COMBINEDQUEUE = 15;

		public static final int COMBINED_DATA_TRANSFERED = 16; //DONE

		public static final int PEER_NODE_VERSION = 17; //DONE

		public static final int PEER_ARK_REVISION = 18; //DONE

		public static final int CONNECTIONS_OPEN_OUTBOUND = 19; //DONE

		public static final int CONNECTIONS_OPEN_INBOUND = 20; //DONE

		public static final int CONNECTIONS_OPEN_COMBINED = 21; //DONE

		public static final int QUEUED_MESSAGES = 22; //DONE

		public static final int QUEUED_TRAILERMESSAGES = 23; //DONE

		public static final int QUEUED_MESSAGES_COMBINED = 24; //DONE

		public static final int CONNECTION_ATTEMPTS = 25; //DONE

		public static final int CONNECTION_SUCCESSES = 26; //DONE

		public static final int CONNECTION_SUCCESS_RATIO = 27; //DONE

		public static final int MESSAGES_SENT_SUCCESSFULLY = 28; //DONE

		public static final int MESSAGES_SENDFAILURE = 29; //DONE

		public static final int MESSAGES_SENT_COMBINED = 30; //DONE

		public static final int MESSAGES_RECEIVED_SUCCESSFULLY = 31; //DONE

		public static final int MESSAGES_RECEIVEFAILURE = 32; //DONE

		public static final int MESSAGES_RECEIVED_COMBINED = 33; //DONE

		public static final int MESSAGES_HANDLED_COMBINED = 34; //DONE

		public static final int REQUESTS_SENT = 35; //DONE

		public static final int REQUESTS_RECEIVED = 36; //DONE

		private int iCompareMode = UNORDERED;

		private PeerHandlerComparator secondary;

		public PeerHandlerComparator(int iCompareMode) {
			this(iCompareMode, null);
		}

        public PeerHandlerComparator(int iCompareMode, PeerHandlerComparator secondarySorting) {
			this.iCompareMode = iCompareMode;
			this.secondary = secondarySorting;
		}

		public int compare(Object o1, Object o2) {
			return compare((PeerHandler) o1, (PeerHandler) o2);
		}

        private int secondaryCompare(int iSign, int primaryResult, PeerHandler ph1, PeerHandler ph2) {
			if (primaryResult == 0 && secondary != null)
				return secondary.compare(ph1, ph2);
			else
				return iSign * primaryResult;
		}

		public int compare(PeerHandler ph1, PeerHandler ph2) {
			int iSign = iCompareMode < 0 ? -1 : 1;
			switch (Math.abs(iCompareMode)) {
            case UNORDERED:
                //No sorting
					return 0;
				case PEER_ADDRESS :
					{
                    String s1 = (ph1.ref == null ? "" : ph1.ref.firstPhysicalToString());
                    String s2 = (ph2.ref == null ? "" : ph2.ref.firstPhysicalToString());
                    return secondaryCompare(iSign, s2.compareTo(s1), ph1, ph2);
						//Reverse order on s1 and s2 is intended. Better
						//to place PH:s without ref at the end
					}
				case PEER_IDENTITY :
                return secondaryCompare(iSign, ph1.id.toString().compareTo(ph2.id.toString()), ph1, ph2);
					//case SENDING_COUNT:
					//	return secondaryCompare(iSign,new Integer(ph1.sending()
					// ? 1 : 0).compareTo(new Integer(ph2.sending() ? 1 :
					// 0)),ph1,ph2);
					//case RECEIVING_COUNT:
					//	return secondaryCompare(iSign,new
					// Integer(ph1.receiving() ? 1 : 0).compareTo(new
					// Integer(ph2.receiving() ? 1 : 0)),ph1,ph2);
				case SENDQUEUE :
                return secondaryCompare(iSign, new Long(ph1.queuedBytes()).compareTo(new Long(ph2.queuedBytes())), ph1, ph2);
				case ROUTING_ADDRESS :
					{
                    NodeReference n1 = freenet.node.Main.node.rt.getNodeReference(ph1.id);
                    NodeReference n2 = freenet.node.Main.node.rt.getNodeReference(ph2.id);
                    String s1 = (n1 == null ? "" : n1.firstPhysicalToString());
                    String s2 = (n2 == null ? "" : n2.firstPhysicalToString());
                    return secondaryCompare(iSign, s2.compareTo(s1), ph1, ph2);
						//Reverse order on s1 and s2 is intended. Better to
						// place non-routable
						//PH:s at the end
					}
				case DATASENT :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getDataSent()).compareTo(new Long(ph2.getMessageAccounter()
                        .getDataSent())), ph1, ph2);
					//case RECEIVEQUEUE:
					//	return secondaryCompare(iSign,new
					// Long(ph1.receiveQueueSize()).compareTo(new
					// Long(ph2.receiveQueueSize())),ph1,ph2);
				case DATARECEIVED :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getDataReceived()).compareTo(new Long(ph2.getMessageAccounter()
                        .getDataReceived())), ph1, ph2);
					//case COMBINEDQUEUE:
					//		return secondaryCompare(iSign,new
					// Long(ph1.receiveQueueSize()+ph1.queuedBytes()).compareTo(new
					// Long(ph2.receiveQueueSize()+ph2.queuedBytes())),ph1,ph2);
				case COMBINED_DATA_TRANSFERED :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getDataSent() + ph1.getMessageAccounter().getDataReceived())
                        .compareTo(new Long(ph2.getMessageAccounter().getDataSent() + ph2.getMessageAccounter().getDataReceived())), ph1, ph2);
				case PEER_NODE_VERSION :
					{
                    String s1 = (ph1.ref == null ? "" : ph1.ref.getVersion());
                    String s2 = (ph2.ref == null ? "" : ph2.ref.getVersion());
                    return secondaryCompare(iSign, s1.compareTo(s2), ph1, ph2);
					}
				case PEER_ARK_REVISION :
					{
                    Long l1 = (ph1.ref == null ? new Long(-1) : new Long(ph1.ref.revision()));
                    Long l2 = (ph2.ref == null ? new Long(-1) : new Long(ph2.ref.revision()));
                    return secondaryCompare(iSign, l1.compareTo(l2), ph1, ph2);
					}
				case IDLETIME :
                return secondaryCompare(iSign, new Long(ph1.getIdleTime()).compareTo(new Long(ph2.getIdleTime())), ph1, ph2);
				case LIFETIME :
                return secondaryCompare(iSign, new Long(ph1.getLifeTime()).compareTo(new Long(ph2.getLifeTime())), ph1, ph2);
				case CONNECTIONS_OPEN_OUTBOUND :
                return secondaryCompare(iSign, new Long(ph1.getOutboundConnectionsCount()).compareTo(new Long(ph2.getOutboundConnectionsCount())),
                        ph1, ph2);
				case CONNECTIONS_OPEN_INBOUND :
                return secondaryCompare(iSign, new Long(ph1.getInboundConnectionsCount()).compareTo(new Long(ph2.getInboundConnectionsCount())), ph1,
						ph2);
				case CONNECTIONS_OPEN_COMBINED :
                return secondaryCompare(iSign, new Long(ph1.connectionHandlers.size()).compareTo(new Long(ph2.connectionHandlers.size())), ph1, ph2);
				case QUEUED_MESSAGES :
                return secondaryCompare(iSign, new Long(ph1.list.messageCount()).compareTo(new Long(ph2.list.messageCount())), ph1, ph2);
				case QUEUED_TRAILERMESSAGES :
                return secondaryCompare(iSign, new Long(ph1.list.messageCount()).compareTo(new Long(ph2.list.messageCount())), ph1, ph2);
				case QUEUED_MESSAGES_COMBINED :
					return secondaryCompare(iSign, new Long(ph1.list.messageCount()).compareTo(new Long(ph2.list.messageCount())), ph1, ph2);
				case CONNECTION_ATTEMPTS :
                return secondaryCompare(iSign, new Long(ph1.totalOutboundAttempts).compareTo(new Long(ph2.totalOutboundAttempts)), ph1, ph2);
				case CONNECTION_SUCCESSES :
                return secondaryCompare(iSign, new Long(ph1.totalOutboundSuccesses).compareTo(new Long(ph2.totalOutboundSuccesses)), ph1, ph2);
				case CONNECTION_SUCCESS_RATIO :
                return secondaryCompare(iSign, new Float(ph1.getOutboundConnectionSuccessRatio()).compareTo(new Float(ph2
                        .getOutboundConnectionSuccessRatio())), ph1, ph2);
				case MESSAGES_SENT_SUCCESSFULLY :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getMessgesSent()).compareTo(new Long(ph2.getMessageAccounter()
                        .getMessgesSent())), ph1, ph2);
				case MESSAGES_SENDFAILURE :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getMessgeSendsFailed()).compareTo(new Long(ph2
                        .getMessageAccounter().getMessgeSendsFailed())), ph1, ph2);
				case MESSAGES_SENT_COMBINED :
					{
						MessageAccounter m1 = ph1.getMessageAccounter();
						MessageAccounter m2 = ph2.getMessageAccounter();
                    return secondaryCompare(iSign, new Long(m1.getMessgesSent() + m1.getMessgeSendsFailed()).compareTo(new Long(m2.getMessgesSent()
                            + m2.getMessgeSendsFailed())), ph1, ph2);
					}
				case MESSAGES_RECEIVED_SUCCESSFULLY :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getMessagesReceived()).compareTo(new Long(ph2.getMessageAccounter()
                        .getMessagesReceived())), ph1, ph2);
				case MESSAGES_RECEIVEFAILURE :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getMessagesReceiveFailed()).compareTo(new Long(ph2
                        .getMessageAccounter().getMessagesReceiveFailed())), ph1, ph2);
				case MESSAGES_RECEIVED_COMBINED :
					{
						MessageAccounter m1 = ph1.getMessageAccounter();
						MessageAccounter m2 = ph2.getMessageAccounter();
                    return secondaryCompare(iSign, new Long(m1.getMessagesReceived() + m1.getMessagesReceiveFailed()).compareTo(new Long(m2
                            .getMessagesReceived()
                            + m2.getMessagesReceiveFailed())), ph1, ph2);
					}
				case MESSAGES_HANDLED_COMBINED :
					{
						MessageAccounter m1 = ph1.getMessageAccounter();
						MessageAccounter m2 = ph2.getMessageAccounter();
                    return secondaryCompare(iSign, new Long(m1.getMessagesReceived() + m1.getMessagesReceiveFailed() + m1.getMessgesSent()
                            + m1.getMessgeSendsFailed()).compareTo(new Long(m2.getMessagesReceived() + m2.getMessagesReceiveFailed()
                            + m2.getMessgesSent() + m2.getMessgeSendsFailed())), ph1, ph2);
					}
				case REQUESTS_SENT :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getMessagesSentByType(DataRequest.messageName)).compareTo(new Long(
                        ph2.getMessageAccounter().getMessagesSentByType(DataRequest.messageName))), ph1, ph2);
				case REQUESTS_RECEIVED :
                return secondaryCompare(iSign, new Long(ph1.getMessageAccounter().getMessagesReceivedByType(DataRequest.messageName))
                        .compareTo(new Long(ph2.getMessageAccounter().getMessagesReceivedByType(DataRequest.messageName))), ph1, ph2);
				default :
					return 0;
			}
		}
	}

	/**
	 * @return
	 */
	public long trailerChunkQueuedBytes() {
		return trailerChunkQueuedBytes;
	}

	// FIXME: separate CHs and MCHs, would greatly speed up the following:
	
	/**
	 * @return the total number of trailing fields being sent
	 */
	public long countSendingTrailers() {
		long sendingCount = 0;
			for(Iterator i=connectionHandlers.iterator();i.hasNext();) {
                BaseConnectionHandler ch = (BaseConnectionHandler) (i.next());
				if(ch instanceof ConnectionHandler) {
				if (ch.blockedSendingTrailer())
					sendingCount++;
			}
		}
		sendingCount += trailerWriteManager.writers.size();
		return sendingCount;
	}
	
	/**
	 * @return the total number of trailing fields being sent
	 */
	public long countReceivingTrailers() {
		long receivingCount = 0;
			for(Iterator i=connectionHandlers.iterator();i.hasNext();) {
                BaseConnectionHandler ch = (BaseConnectionHandler) (i.next());
				if(ch instanceof ConnectionHandler) {
				if (ch.receiving())
					receivingCount++;
			}
		}
		receivingCount += trailerReadManager.readers.size();
		return receivingCount;
	}

    /**
     * @return
     */
    public long getQueuedMessagesCount() {
        return list.messageCount();
    }
    
    long lastReceivedRequest = 0;

    // Use a longer average than on StandardNodeEstimator
    // Intentionally, to reduce false positives
    final RunningAverage receivedRequestAverage;

    // value
    // 20
    // mins
    // -
    // double
    // the
    // maximum...
    
    /**
     * Update stats when we receive a request from this node. This is used for
     * rate limiting enforcement
     */
    public void receivedRequest() {
        synchronized(receivedRequestAverage) {
        	// Put inside loop to avoid negative values
            long now = System.currentTimeMillis();
            if(lastReceivedRequest >= 0) {
                long diff = now - lastReceivedRequest;
                if (lastReceivedRequest > 0) receivedRequestAverage.report(diff);
                double average = receivedRequestAverage.currentValue();
                double minRequestInterval = getLowestSentRequestInterval();
                double actual = getRequestInterval();
                if(actual < minRequestInterval) minRequestInterval = actual;
                requestCounter++;
                if(lastReceivedRequest > 0) {
                    double higherOfAverageAndInterval = Math.max(average, diff);
                    // In case of restart artefacts
                    higherOfAverageAndInterval *= 1.1;
                    if(Core.logger.shouldLog(Logger.MINOR,this)) Core.logger.log(this, "Request interval: " + diff + ", average: " + average + ", minRequestInterval (2 minutes minimum): "
                            + minRequestInterval + ", (current): " + actual + ", requestSentIntervalValidity=" + requestSentIntervalValidity
                            + ", requestCounter=" + requestCounter + ", sentIntervalCounter="+sentIntervalCounter+" for " + this, Logger.MINOR);
                    if (requestSentIntervalValidity >= 2 && sentIntervalCounter >= 5 && requestCounter >= 5 &&
                            higherOfAverageAndInterval < minRequestInterval && timeConnected > 0 && 
							(now - timeConnected) > minRequestInterval) {
                        Core.logger.log(this, "Minimum request interval violated!" + ": Average interval = " + average + "ms, actual=" + diff
                                + ", but limit is " + minRequestInterval + "ms (" + this + ")", Logger.NORMAL);
                        RateLimitingViolationMessage rlvm = new RateLimitingViolationMessage(diff, average, minRequestInterval, actual, this);
                        innerSendMessageAsync(rlvm);
                    }
                }
            }
            lastReceivedRequest = now;
        }
    }
    
    // This could be more efficient: FIXME
    
    int requestSentIntervalValidity = 0; // when this reaches 2, we are valid

    double lowestRequestIntervalLastMinute = 1000.0;

    double lowestRequestIntervalThisMinute = -1;

    Object requestIntervalItemsSync = new Object();

    long nextMinuteStart = 0;

    double[] mostRecent = new double[5];

    long sentIntervalCounter = 0;
    long requestCounter = 0;

    int pos = 0;

    /**
     * Get the lowest request rate interval sent over the last 2 minutes,
     * approximately.
     * 
     * @return
     */
    public double getLowestSentRequestInterval() {
        synchronized(requestIntervalItemsSync) {
            double min = Double.MAX_VALUE;
            if(lowestRequestIntervalThisMinute < 0)
                min = lowestRequestIntervalLastMinute;
            else
                min = Math.min(lowestRequestIntervalLastMinute, lowestRequestIntervalThisMinute);
            int l = (int)sentIntervalCounter;
            if(l > 5) l = 5;
            for(int i=0;i<l;i++) {
                if(mostRecent[i] < min) min = mostRecent[i];
            }
            if(Core.logger.shouldLog(Logger.DEBUG, this))
                    Core.logger.log(this, "getLowestSentRequestInterval: min=" + min + ", lastMinute=" + lowestRequestIntervalLastMinute
                            + ", thisMinute=" + lowestRequestIntervalThisMinute + " (" + this + ")", Logger.DEBUG);
            return min;
        }
    }
    
    long timeLastSentRequestInterval = -1;
    double lastSentMRI = -1;

    public long lastSentRequestIntervalTime() {
        synchronized(requestIntervalItemsSync) {
            return timeLastSentRequestInterval;
        }
    }

    public double lastSentMRI() {
        return lastSentMRI;
    }
    
    public void sentRequestInterval(double mri) {
        if(mri < 0) return;
        if(Core.logger.shouldLog(Logger.MINOR, this)) {
            String logMessage = "sentRequestInterval(" + mri + ") on " + this + ": nextMinuteStart=" + nextMinuteStart
                    + ", lowestRequestIntervalThisMinute=" + lowestRequestIntervalThisMinute + ", lowestRequestIntervalLastMinute="
                    + lowestRequestIntervalLastMinute + ", requestSentIntervalValidity=" + requestSentIntervalValidity;
            if (Core.logger.shouldLog(Logger.DEBUG, this)) Core.logger.log(this, logMessage, new Exception("debug"), Logger.DEBUG);
            Core.logger.log(this, logMessage, Logger.MINOR);
        }
        Core.diagnostics.occurrenceContinuous("outgoingRequestInterval", mri);
        synchronized(requestIntervalItemsSync) {
            long now = System.currentTimeMillis();
            timeLastSentRequestInterval = now;
            lastSentMRI = mri;
            if(pos >= mostRecent.length) {
                pos = 0;
            }
            mostRecent[pos] = mri;
            pos++;
            sentIntervalCounter++;
            if(nextMinuteStart < now) {
                // Update nextMinuteStart
                while(nextMinuteStart < now) {
                    if(nextMinuteStart == 0)
                        nextMinuteStart = now + 60*1000;
                    else
                        nextMinuteStart = nextMinuteStart + 60*1000;
                }
                if(lowestRequestIntervalThisMinute > 0) {
                    lowestRequestIntervalLastMinute = lowestRequestIntervalThisMinute;
                    requestSentIntervalValidity++;
                }
                lowestRequestIntervalThisMinute = -1;
            }
            // Report rate
            if(lowestRequestIntervalThisMinute < 0)
                lowestRequestIntervalThisMinute = mri;
            else {
                if (lowestRequestIntervalThisMinute > mri) lowestRequestIntervalThisMinute = mri;
            }
            if(Core.logger.shouldLog(Logger.MINOR, this)) {
                String logMessage = "sentRequestInterval(" + mri + ") on " + this + ": nextMinuteStart=" + nextMinuteStart
                        + ", lowestRequestIntervalThisMinute=" + lowestRequestIntervalThisMinute + ", lowestRequestIntervalLastMinute="
                        + lowestRequestIntervalLastMinute + ", requestSentIntervalValidity=" + requestSentIntervalValidity;
                Core.logger.log(this, logMessage, Logger.MINOR);
            }
        }
    }

    /**
     * Don't update getRequestInterval() more than every 5 seconds.
     * This makes queueing etc easier, and prevents some nasty attacks.
     * Hopefully it won't have too nasty an effect on rate limiting...
     */
    private Object requestIntervalSync = new Object(); // too many sync objects? :|
    private double lastRequestInterval = -1;
    long lastRequestIntervalCalculated = -1;
    
    /**
     * @return the current request interval to send to this node. We send
     *         different request intervals to different nodes, because of the
     *         difficulty in accurately calculating an overall value.
     */
    public double getRequestInterval() {
        long now = System.currentTimeMillis();
        synchronized(requestIntervalSync) {
            if(now - lastRequestIntervalCalculated < 5000)
                return lastRequestInterval;
        }
        /**
         * quota(n) = Math.max(globalQuota-totalRequests+totalRequests(n)),
         * (globalQuota*2-totalRequests)/totalNodes)
         */
        double globalQuota = node.getGlobalQuota(); // in qph
        double totalRequests = node.getActualRequestsPerHour(); // in qph
        
        double totalRequestsThisNode = recentlyReceivedRequests.getExtrapolatedEventsPerHour();
        double thisNodeQuota = globalQuota - totalRequests + totalRequestsThisNode;
        double thisNodeMinQuota = (globalQuota*2 - totalRequests);
        int conns = node.connections.getNumberOfOpenConnections();
        if(conns <= 0) conns = 1;
        thisNodeMinQuota /= conns;
        thisNodeQuota = Math.max(thisNodeQuota, thisNodeMinQuota);
        double minRequestInterval;
        if(thisNodeQuota == 0) {
            Core.logger.log(this, "Quota of 0 for "+this+": "+globalQuota + ", totalRequests: " + 
                    totalRequests + ", totalRequestsThisNode: "+ totalRequestsThisNode + 
                    ", thisNodeMinQuota: " + thisNodeMinQuota + ", thisNodeQuota: " + thisNodeQuota,
                    Logger.NORMAL);
            minRequestInterval = 600 * 1000;
        } else {
        // Now convert to a requestInterval
            minRequestInterval = (3600*1000) / thisNodeQuota;
        // Don't impose an upper bound as we will update the other side soon enough if it changes
            if (minRequestInterval <= 0) minRequestInterval = 600 * 1000; // 10 minutes
        }
        if(Core.logger.shouldLog(Logger.DEBUG, this))
                Core.logger.log(this, "globalQuota: " + globalQuota + ", totalRequests: " + totalRequests + ", totalRequestsThisNode: "
                        + totalRequestsThisNode + ", thisNodeMinQuota: " + thisNodeMinQuota + ", thisNodeQuota: " + thisNodeQuota
                        + ", minRequestInterval: " + minRequestInterval + " (" + this + ")", Logger.DEBUG);
        synchronized(requestIntervalSync) {
            lastRequestInterval = minRequestInterval;
            lastRequestIntervalCalculated = now;
        }
        return minRequestInterval;
    }
    
    /**
     * @return
     */
    public Identity getIdentity() {
        return id;
    }

    /**
     * Backoff the connection
     */
    public void connectionBackoff() {
    	connectionFailureBackoffManager.backoff();
    }

    /**
     * Revoke the recent success of a connection's effect on our
     * backoff stats.
     */
    public void revokeConnectionSuccess() {
        connectionFailureBackoffManager.revokeReset();
        connectionBackoff();
    }
    
    /**
     * @return true if we are currently backed off for connection attempts
     */
    public boolean isBackedOff() {
    	//Consider the peer backed off if either is true
    	//1. Connections to it are failing
    	//2. We recently managed to connect to it (short-lived connections anti DOS facility)
        return connectionFailureBackoffManager.isBackedOff();
    }

    /**
     * @return the time in millis until we can try another connection attempt.
     */
    public long backoffRemaining() {
        return connectionFailureBackoffManager.backoffRemaining();
    }

    /**
     * Expire all messages that can be expired
     * @return the number of messages expired.
     */
    public int expireMessages() {
        return list.expireAll();
    }

    /**
     * 
     */
    public void terminateAll() {
        Iterator it= connectionHandlers.iterator(); //Take a snapshot of the list
        while(it.hasNext()) { //Terminate all CH's in the snapshot
            BaseConnectionHandler ch = (BaseConnectionHandler)it.next();
            ch.terminate();
        }
    }

    /**
     * @return a BaseConnectionHandler currently registered to this PH, null if none present
     */
    public BaseConnectionHandler getConnectionHandler() {
    	Iterator it = connectionHandlers.iterator(); //Snapshot the list
    	if(it.hasNext()) //If the snapshot contains anything..
    		return (BaseConnectionHandler)it.next(); //Then return the first element in the snapshot
        else
        	return null; //Else return null
    }

	/**
	 * 
	 */
	public void enableSendingRequests() {
		canSendRequests = true;
		// Try to send something
		innerSendMessageAsync(null);
		node.queueManager.runQueue();
	}

	public boolean canSendRequests() {
	    return canSendRequests;
	}

    public boolean queuedMessagesWithMRI() {
        return list.messagesWithMRI();
    }

    public boolean inFlightMessagesWithMRI() {
		Iterator i = connectionHandlers.iterator();
		while (i.hasNext()) {
			BaseConnectionHandler ch = (BaseConnectionHandler) (i.next());
			if(ch.inFlightMessagesWithMRI()) return true;
		}
		return false;
    }

    /**
     * Report an MRI coming in from this node.
     * @param mri the Minimum Request Interval received from the node.
     */
    public void reportIncomingMRI(double mri) {
        node.rt.updateMinRequestInterval(id, mri);
        node.queueManager.runQueue();
    }

    long lastNoReferenceRequestTime = -1;
    
    public void reportRequestBeforeNodeRef() {
        long now = System.currentTimeMillis();
        if(now - lastNoReferenceRequestTime > 15*60*1000) {
            Core.logger.log(this, "Executing request before NodeRef set on "+this+
                    " for "+this, Logger.NORMAL);
            lastNoReferenceRequestTime = now;
        }
    }

    private volatile boolean resetSendPacket = false;
    private Object resetSendPacketSync = new Object();
    
    /**
     * Set a flag which indicates that the connections' availability
     * status may have changed, potentially during a packet send.
     */
    public void setResetSendPacket() {
        synchronized(resetSendPacketSync) {
            resetSendPacket = true;
        }
    }

    public MRIPacketMessage mriPacketMessage() {
        double requestInterval = getRequestInterval();
        if(requestInterval == lastSentMRI) return null;
        return new MRIPacketMessage(this, requestInterval);
    }

    public boolean badVersion() {
        if(ref == null) return false;
        return ref.badVersion();
    }

    public Node getNode() {
        return node;
    }
}
