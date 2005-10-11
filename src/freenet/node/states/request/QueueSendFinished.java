package freenet.node.states.request;

import freenet.Identity;
import freenet.MessageSendCallback;

/**
 * Internal message queued to indicate that the queueing subsystem
 * has found a route to send the request to. It will queue this message
 * before sending the actual message. The message is queued with a
 * MessageSendCallback that will be called when the message has actually
 * been sent.
 */
public class QueueSendFinished extends RequestObject {

    final Identity nodeSentTo;
    public MessageSendCallback cb;
    
    /**
     * @param id
     * @param identity
     */
    public QueueSendFinished(long id, Identity identity, MessageSendCallback cb) {
        super(id, true);
        nodeSentTo = identity;
        this.cb = cb;
    }
}
