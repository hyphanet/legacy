package freenet.node;

import freenet.MessageObject;

public interface AggregatedState {
    /**
     * @return  true, if this State can receive the MO
     */
    boolean receives(MessageObject mo);
}

