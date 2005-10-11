package freenet.node.states.data;

/** Sent back to the request chain after the data has been received.
  */
public class DataReceived extends DataStateReply {
    DataReceived(ReceiveData ds) {
        super(ds);
    }
}
