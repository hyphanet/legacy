package freenet.node.states.FCP;

import freenet.FieldSet;
import freenet.MessageSendCallback;
import freenet.PeerHandler;
import freenet.SendFailedException;
import freenet.TrailerWriter;
import freenet.message.client.ClientMessage;
import freenet.message.client.DataNotFound;
import freenet.message.client.Failed;
import freenet.message.client.Restarted;
import freenet.message.client.RouteNotFound;
import freenet.node.Node;
import freenet.node.states.request.FeedbackToken;

/** FeedbackToken to translate the results from the node's request states
  * to FCP responses.
  */
abstract class FCPFeedbackToken implements FeedbackToken {

    // the id of the associated state chain
    long id;
    // the active connection (message queue)
    protected PeerHandler source;

    protected boolean waiting = true;

    FCPFeedbackToken(long id, PeerHandler source) {
        this.id     = id;
        this.source = source;
    }

    public void queryRejected(Node n, int htl, String reason, FieldSet fs,
                              int unreachable, int restarted, int rejected,
                              int backedOff, MessageSendCallback cb)
	throws SendFailedException {
        if (waiting)
            sendMessage(new RouteNotFound(id, reason, unreachable,
                                          restarted, rejected, backedOff));
        else
            sendMessage(new Failed(id, reason));
    }

    public void restarted(Node n, long millis,
			  MessageSendCallback cb, String reason) throws SendFailedException {
        waiting = true;
        sendMessage(new Restarted(id, millis, reason));
    }
    
    public void dataNotFound(Node n, long timeOfQuery, 
			     MessageSendCallback cb) 
	throws SendFailedException {
        sendMessage(new DataNotFound(id));
    }
    
    // FIXME: implement async sending here
    protected TrailerWriter sendMessage(ClientMessage cm) throws SendFailedException {
        return source.sendMessage(cm, 300*1000);
    }
}



