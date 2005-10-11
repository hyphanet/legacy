package freenet.node;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

import freenet.Core;
import freenet.Identity;
import freenet.Key;
import freenet.MessageSendCallback;
import freenet.OpenConnectionManager;
import freenet.PeerHandler;
import freenet.PeerPacketMessage;
import freenet.SendFailedException;
import freenet.Ticker;
import freenet.diagnostics.ExternalContinuous;
import freenet.message.Request;
import freenet.node.rt.NGRouting;
import freenet.node.rt.NGRoutingTable;
import freenet.node.rt.NodeSortingRoutingTable;
import freenet.node.rt.Routing;
import freenet.node.states.request.QueueSendFailed;
import freenet.node.states.request.QueueSendFinished;
import freenet.support.Logger;

/**
 * QueueManager: the guts of request queueing. A request chain will
 * give us a request, we are responsible for either sending the 
 * request to a suitable node, probably after some delay, and sending
 * them a QueueSendFinished, or returning them a QueueSendFailed, in
 * which case they will typically die with an RNF.
 * Created on Aug 19, 2004
 * @author amphibian
 */
public final class QueueManager {

    public class QueueElement {

        /**
         * @param id2
         * @param searchKey
         * @param routes2
         * @param timeout
         */
        public QueueElement(long id2, Key searchKey, Routing routes2, long timeout,
                MessageSendCallback cb, Request req, int sendTimeout, Identity source) {
            this.id = id2;
            this.key = searchKey;
            this.routes = routes2;
            this.expiresAt = System.currentTimeMillis() + timeout;
            this.cb = cb;
            this.req = req;
            this.sendTimeout = sendTimeout;
            this.source = source;
        }
        
        final Key key;
        final long id;
        final long expiresAt;
        final Routing routes;
        final Identity source;
        final Request req;
        final MessageSendCallback cb;
        final int sendTimeout;

        public String toString() {
            return super.toString()+": id="+Long.toHexString(id)+", expiresAt="+expiresAt+
            	" ("+(expiresAt-System.currentTimeMillis())+" ms), request="+
            	req+", callback="+cb+", sendTimeout="+sendTimeout+", routes="+
            	routes;
        }
        
        /**
         * Calculate time remaining on this request's timeout
         */
        public final long calculateTimeLeft(long now) {
            return expiresAt - now;
        }

        /**
         * Send the request to the specified node.
         */
        public void sendOn(NodeElement ne) {
            Core.diagnostics.occurrenceBinomial("requestQueueingSuccess", 1, 1);
            // Now tell the chain
            t.add(0, new QueueSendFinished(id, ne.id, cb));
            // Send the message
            try {
                ne.ph.sendMessageAsync(req, cb, sendTimeout, PeerPacketMessage.NORMAL);
            } catch (Exception t) {
                Core.logger.log(this, "Could not send message: "+req+" on "+ne.ph+": "+t,
                        t, Logger.ERROR);
                cb.thrown(t);
            } catch (Throwable t) {
                Core.logger.log(this, "Could not send message: "+req+" on "+ne.ph+": "+t,
                        t, Logger.ERROR);
                SendFailedException e = new SendFailedException(null, ne.id, "Caught throwable: "+t, false);
                e.initCause(t);
                cb.thrown(e);
            }
            requests.remove(this);
        }

        public void expire() {
            Core.diagnostics.occurrenceBinomial("requestQueueingSuccess", 1, 0);
            if(logMINOR)
            	Core.logger.log(this, "Could not send message, expiring: "+this, Logger.MINOR);
            t.add(0, new QueueSendFailed(id, req));
            requests.remove(this);
        }

        /**
         * @param identity
         * @return
         */
        public boolean canRouteTo(Identity identity) {
            return routes.canRouteTo(identity);
        }
    }

    public class NodeElement {
        final Identity id;
        final PeerHandler ph;
        long timeToSendWindow;
        
        public String toString() {
            return super.toString()+": timeToSendWindow="+timeToSendWindow+
            	", ph="+ph;
        }
        
        public NodeElement(PeerHandler ph, long time) {
            id = ph.getIdentity();
            this.ph = ph;
            timeToSendWindow = time;
        }

        /**
         * @param now the current time. Supplied for consistency as
         * well as efficiency.
         * @return true if this node is not usable, even if we wait,
         * for sending a request with this timeout.
         */
        public boolean notUsable(long maxRequestTimeout, long now) {
            if(!ph.isConnected()) return true;
            if(!ph.canSendRequests()) return true;
            if(ph.badVersion()) return true;
            updateTimeToSendWindow(now);
            if(timeToSendWindow < 0) return true; // not available
            // Valid PH
            if(timeToSendWindow > maxRequestTimeout) {
                if(logDEBUG)
                    Core.logger.log(this, "Rejecting "+ph+": can't send request for another "+
                            timeToSendWindow+"ms", Logger.DEBUG);
                return true;
            }
            if(connections.getPeerHandler(id) == null)
                return true;
            return false;
        }

        public void updateTimeToSendWindow(long now) {
            timeToSendWindow = rt.timeTillCanSendRequest(ph.getIdentity(), now);
        }
    }

    private final Set requests = Collections.synchronizedSet(new HashSet());
    private static final int MIN_TIME_BETWEEN_RUN_QUEUE = 100;
    // Ideally this would be 1 second or so, but GC often causes 
    // pauses of 2-3 seconds.
    private static final int MAX_TIME_BETWEEN_RUN_QUEUE = 5000;
    private boolean logDEBUG;
    private boolean logMINOR;
    
    private final OpenConnectionManager connections;
    private final NodeSortingRoutingTable rt;
    private final Ticker t;
    private final ExternalContinuous requestsQueued= Core.diagnostics.getExternalContinuousVariable("requestsQueued");
    private final ExternalContinuous queueAvailableNodes= Core.diagnostics.getExternalContinuousVariable("queueAvailableNodes");
    
    /**
     * 
     */
    public QueueManager(OpenConnectionManager ocm, NGRoutingTable rt,
            Ticker t) {
        super();
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        logMINOR = Core.logger.shouldLog(Logger.MINOR, this);
        connections = ocm;
        this.rt = rt;
        this.t = t;
        if(connections == null)
            throw new NullPointerException();
        if(rt == null)
            throw new NullPointerException();
        if(t == null)
            throw new NullPointerException();
    }

    /**
     * Add a request to the queue.
     * @param id The ID of the state chain responsible.
     * @param searchKey The key we are searching for, provided because
     * we may send the request immediately when the window arises.
     * @param routes The routing object from which to draw estimates
     * @param timeout The time after which we should fail the request
     * if it is not already sent.
     */
    public void queue(long id, Key searchKey, Routing routes, long timeout,
            MessageSendCallback cb, Request req, int sendTimeout, Identity source) {
        QueueElement qe = new QueueElement(id, searchKey, routes, timeout, cb, req, sendTimeout, source);
        if(logDEBUG)
            Core.logger.log(this, "Queueing "+qe, Logger.DEBUG);
        requests.add(qe);
        runQueue();
    }

    /**
     * Execute the queueing algorithm.
     * We have N requests, and M nodes.
     * Each node will be available in X millis, or it may already be 
     * available. Each request has a timeout. Thus some requests cannot
     * be routed to some nodes even if we wait.
     * First we check all requests, and timeout any that have reached 
     * their expiry times.
     * Then we start the real work:
     * Determine the lowest estimate of all {node,request} pairs that are
     * usable i.e. where the node is available or will be available within
     * the timeout.
     * If the node is currently available, send the request immediately to
     * that node.
     * Whether or not it is available, ensure that neither that request nor
     * that node are part of the next iteration.
     * Repeat until we run out of nodes or requests.
     */
    public void runQueue() {
        // First, the guard code
        synchronized(runQueueSync) {
        	if(runningQueue) return;
        	runningQueue = true;
        }
        try {
            logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
            logMINOR = Core.logger.shouldLog(Logger.MINOR, this);
            long now = System.currentTimeMillis();
            long d = now - timeLastRanQueue;
            if(d < MIN_TIME_BETWEEN_RUN_QUEUE)
                return;
            Core.logger.log(this, "Last ran queue "+d+" ms ago", 
                    d > MAX_TIME_BETWEEN_RUN_QUEUE && timeLastRanQueue > 0 ? Logger.NORMAL : Logger.DEBUG);
            timeLastRanQueue = now;

            // Now, lets do some actual useful work
            LinkedList myRequests = new LinkedList();
            long maxRequestTimeout = 0;
            long minRequestTimeout = Long.MAX_VALUE;
            synchronized(requests) {
                for(Iterator it = requests.iterator(); it.hasNext();) {
                    QueueElement qe = (QueueElement) (it.next());
                    myRequests.add(qe);
                    long timeTillExpiry = qe.calculateTimeLeft(now);
                    if(maxRequestTimeout < timeTillExpiry)
                        maxRequestTimeout = timeTillExpiry;
                    if(minRequestTimeout > timeTillExpiry)
                        minRequestTimeout = timeTillExpiry;
                }
            }
            // Now we have the requests
            if(myRequests.size() == 0) return;
            if(logMINOR)
                Core.logger.log(this, "Running queue, "+myRequests.size()+
                        " requests,  maximum time to expiry: "+maxRequestTimeout, 
                        Logger.MINOR);
            requestsQueued.count(myRequests.size());
            // Now get the nodes
            // Since all connected nodes are in RT (hopefully!),
            // Get them from OCM.
            long maxNodeWindowTime = 0;
            long minNodeWindowTime = Long.MAX_VALUE;
            LinkedList nodes = new LinkedList();
            PeerHandler[] rawPHs = connections.getPeerHandlers();
            int rejectedNodesCount = 0;
            for(int i=0;i<rawPHs.length;i++) {
                PeerHandler ph = rawPHs[i];
                if(ph == null) continue;
                if(!ph.isConnected()) continue;
                if(!ph.canSendRequests()) continue;
                if(ph.badVersion()) continue;
                // Valid PH
                long timeToSendWindow = rt.timeTillCanSendRequest(ph.getIdentity(), now);
                if(timeToSendWindow < 0) {
                    if(logDEBUG)
                        Core.logger.log(this, "Not in RT: "+ph, Logger.NORMAL);
                }
                if(timeToSendWindow > maxRequestTimeout) {
                    if(logDEBUG)
                        Core.logger.log(this, "Rejecting "+ph+": can't send request for another "+
                                timeToSendWindow+"ms", Logger.DEBUG);
                    rejectedNodesCount++;
                    continue;
                }
                nodes.add(new NodeElement(ph, timeToSendWindow));
                maxNodeWindowTime = Math.max(timeToSendWindow, maxNodeWindowTime);
                minNodeWindowTime = Math.min(timeToSendWindow, minNodeWindowTime);
            }
            if(rejectedNodesCount > 0 && logMINOR)
                Core.logger.log(this, "Rejected "+rejectedNodesCount+" nodes", Logger.MINOR);

            queueAvailableNodes.count(nodes.size());
            if(nodes.size() == 0) {
                if(logDEBUG)
                    Core.logger.log(this, "Returning because 0 nodes", Logger.DEBUG);
                return;
            }
            if(logMINOR)
                Core.logger.log(this, Integer.toString(nodes.size())+" nodes, max time to send window: "+
                        maxNodeWindowTime+", min time to send window "+minNodeWindowTime, Logger.DEBUG);

            // Run the queue
            NodeElement lastWinningNode = null;
            QueueElement lastWinningRequest = null;
            int consecutiveSameWinner = 0;
            while(true) {
                double minimumEstimate = Double.MAX_VALUE;
                NodeElement winningNode = null;
                QueueElement winningRequest = null;
                int notIgnoredPairs = 0;
                int ignoredPairs = 0;
                for(Iterator i = nodes.listIterator(); i.hasNext();) {
                    NodeElement ne = (NodeElement) i.next();
                    Identity id = ne.id;
                    for(Iterator j = myRequests.listIterator(); j.hasNext();) {
                        QueueElement qe = (QueueElement) j.next();
                        if(qe.source != null && qe.source.equals(id)) continue;
                        NGRouting r = (NGRouting)(qe.routes);
                        if(ne.timeToSendWindow > 0 && 
                                (qe.expiresAt < (now + ne.timeToSendWindow))) {
                            // Ignore it
                            ignoredPairs++;
//                            if(logDEBUG)
//                                Core.logger.log(this, "Ignoring "+Long.toHexString(qe.id)+" with "+id, Logger.DEBUG);
                            continue;
                        }
                        if(!r.hasNode(id)) continue;
                        if(r.haveRoutedTo(id)) continue;
                        if(now - qe.expiresAt > 10000) {
                            Core.logger.log(this, "Removing "+qe+" in queue run core - timed out "+(qe.expiresAt-now)+"ms ago", Logger.ERROR);
                            // Infinite loop?
                            j.remove();
                            // Will get removed from requests later
                            continue;
                        }
                        double estimate = r.getEstimate(id);
                        // Slightly too verbose logging! :)
//                        if(logDEBUG)
//                            Core.logger.log(this, "Request "+Long.toHexString(qe.id)+", "+id+": "+estimate, Logger.DEBUG);
                        notIgnoredPairs++;
                        if(estimate < minimumEstimate) {
                            if(logDEBUG)
                                Core.logger.log(this, "New min estimate: "+estimate+" on "+id+" for "+
                                        Long.toHexString(qe.id), Logger.DEBUG);
                            minimumEstimate = estimate;
                            winningNode = ne;
                            winningRequest = qe;
                        }
                    }
                }
                if(winningNode == null) break;
                // Got one
                if(logMINOR)
                    Core.logger.log(this, "Request to send: "+winningNode+":"+winningRequest+
                            " estimate = "+minimumEstimate+", ignored pairs: "+ignoredPairs+
                            ", not ignored: "+notIgnoredPairs, Logger.MINOR);
                // Now try it
                if(lastWinningNode == winningNode &&
                        lastWinningRequest == winningRequest) {
                    consecutiveSameWinner++;
                } else {
                    consecutiveSameWinner = 0;
                }
                if(consecutiveSameWinner > 3) {
                    Core.logger.log(this, "Consecutive same winner: "+winningNode+" on "+
                            winningRequest+" "+consecutiveSameWinner+" times", Logger.ERROR);
                }
                lastWinningNode = winningNode;
                lastWinningRequest = winningRequest;
                if(winningNode.timeToSendWindow <= 0) {
                    // Can send now
                    if(winningRequest.canRouteTo(winningNode.id))
                        winningRequest.sendOn(winningNode);
                    else {
                    	if(logMINOR) Core.logger.log(this, "Cannot send to "+winningNode, Logger.MINOR);
                        if(winningNode.notUsable(maxRequestTimeout, now)) {
                            nodes.remove(winningNode);
                        } else
                            winningNode.updateTimeToSendWindow(now);
                        continue;
                    }
                }
                // Either way...
                nodes.remove(winningNode);
                myRequests.remove(winningRequest);
                if(notIgnoredPairs == 0) break;
            }
            
            expireTimedOutRequests(now);
            
            long endTime = System.currentTimeMillis();
            long time = endTime - now;
            if(logMINOR || time > MAX_TIME_BETWEEN_RUN_QUEUE)
                Core.logger.log(this, "Queue run took "+time, time > MAX_TIME_BETWEEN_RUN_QUEUE ? Logger.NORMAL : Logger.MINOR);
        } finally {
            synchronized(runQueueSync) {
                runningQueue = false;
            }
        }
    }
    
    /**
     * 
     */
    private void expireTimedOutRequests(long now) {
            // Now expire all requests whose timeouts have passed
            LinkedList toExpire = null; 
            synchronized(requests) {
                for(Iterator e=requests.iterator();e.hasNext();) {
                    QueueElement qe = (QueueElement)(e.next());
                    if(qe.expiresAt < now) {
                        if(toExpire == null) toExpire = new LinkedList();
                        toExpire.add(qe);
                    }
                }
            }
            if(toExpire != null)
                for(Iterator i = toExpire.iterator();i.hasNext();) {
                    QueueElement q = (QueueElement) (i.next());
                    q.expire();
                }
    }
    
    final Object runQueueSync = new Object();
    volatile boolean runningQueue = false;
    long timeLastRanQueue = -1;

}
