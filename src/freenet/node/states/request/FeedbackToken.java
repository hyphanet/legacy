package freenet.node.states.request;

import freenet.CommunicationException;
import freenet.FieldSet;
import freenet.MessageSendCallback;
import freenet.Storables;
import freenet.TrailerWriter;
import freenet.node.Node;
import freenet.node.NodeReference;

/**
 * This is used to communicate back to the originator of a Request,
 * whether the originator is a node, or an internal or external client.
 */
public interface FeedbackToken {

    /**
     * Reject the query. This node will not process the query further.
     * If this is an FNPFeedbackToken, for example, it results in an FNP
     * QueryRejected message. If it is an FCPFeedbackToken, it results in
     * a RouteNotFound message (usually), or a Failed message.
     * @param htl          the HTL left at this node
     * @param reason       reason for failing
     * @param fs           other fields to put in message
     * @param unreachable  total no. of send failures
     * @param restarted    total no. of automatic restarts
     * @param rejected     total number of QueryRejected
     * @param backedOff    total number backed off (e.g. because of rate limiting)
     */
    void queryRejected(Node n, int htl, String reason, FieldSet fs,
                       int unreachable, int restarted, int rejected,
                       int backedOff, MessageSendCallback cb)
        throws CommunicationException;
    
    /**
     * @param millis  the maximum time until the next callback
     */
    void restarted(Node n, long millis,
		   MessageSendCallback cb, String reason)
        throws CommunicationException;
    
    void dataNotFound(Node n, long timeOfQuery,
		      MessageSendCallback cb)
        throws CommunicationException;
    
    TrailerWriter dataFound(Node n, Storables sto, long ctLength)
        throws CommunicationException;

    /**
     * @param millis  the maximum time until the next callback
     */
    void insertReply(Node n, long millis)
        throws CommunicationException;

    void storeData(Node n, NodeReference nr, FieldSet estimator, 
				   long rate, int hopsSinceReset, MessageSendCallback cb)
        throws CommunicationException;
}

