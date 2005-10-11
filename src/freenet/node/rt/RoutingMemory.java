package freenet.node.rt;

import freenet.Identity;
import freenet.node.NodeReference;
import freenet.support.PropertyStore;


/**
 * An entry in the RoutingStore.
 */
public interface RoutingMemory extends PropertyStore {

    Identity getIdentity();

    NodeReference getNodeReference();
    
}





