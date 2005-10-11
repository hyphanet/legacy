package freenet.node.states.request;
import freenet.node.EventMessageObject;

/**
 * Interface of generic non-message events in a Request chain.
 * 
 * @author oskar
 */

abstract class RequestObject extends EventMessageObject {
    /**
     * Create a RequestObject (states/request's version of EventMessageObject)
     * @param id the chain ID
     * @param external whether THE CHAIN is external
     */
    RequestObject(long id, boolean external) {
        super(id, external);
    }
}
