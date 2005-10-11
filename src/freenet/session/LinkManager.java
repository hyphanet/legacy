package freenet.session;

import freenet.Authentity;
import freenet.CommunicationException;
import freenet.Connection;
import freenet.Identity;

/**
 * The LinkManager keeps track of inter-node cryptography. 
 *
 * This is now a general interface for such objects.
 *
 * @author Scott
 * @author oskar
 */
public interface LinkManager {

    /**
     * Returns the Designator number of the protocol represented
     * by this manager.
     */
    int designatorNum();
    
    
    Link createOutgoing(Authentity privMe,
                        Identity pubMe,
                        Identity bob,
                        Connection c) throws CommunicationException;

    Link acceptIncoming(Authentity privMe,
                        Identity pubMe,
                        Connection c) throws CommunicationException;

    
    /**
     * Called periodically by the node.
     */
    void cleanupLinks();
}


