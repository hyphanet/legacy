package freenet.node.states.data;

/**
 * Message object generated after the data has been sent from this node.
 *
 * @author Oskar
 */
public class DataSent extends DataStateReply {
    DataSent(SendData ds) {
        super(ds);
    }
}
