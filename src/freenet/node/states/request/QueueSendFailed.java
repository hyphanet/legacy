package freenet.node.states.request;

import freenet.message.Request;

/**
 * Internal message queued to a chain when queueing can't send the
 * request anywhere.
 */
public class QueueSendFailed extends RequestObject {

    Request r;
    
    /**
     * @param id
     */
    public QueueSendFailed(long id, Request r) {
        super(id, true);
        this.r = r;
    }

}
