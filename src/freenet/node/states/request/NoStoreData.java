package freenet.node.states.request;

/**
 * A message to schedule in case the previous node does not 
 * produce the StoreData message.
 */

class NoStoreData extends RequestObject {

    NoStoreData(long id) {
	super(id, true);
    }

    NoStoreData(RequestState rs) {
        super(rs.id(), true);
    }

}
