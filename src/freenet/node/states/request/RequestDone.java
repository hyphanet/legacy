package freenet.node.states.request;

import freenet.*;
import freenet.node.*;
import freenet.message.*;
import freenet.support.Logger;

/**
 * This is the State pertaining to Insert and Data Requests that
 * are finished at this node.
 */

public class RequestDone extends State {
    
    /** this is in case we get a DataInsert we have to eat */
    final Identity origPeer;
    boolean logDEBUG;
    
    public RequestDone(RequestState ancestor) {
        super(ancestor.id());
        origPeer = ancestor.origPeer;
    }

    public final String getName() {
        return "Request Done";
    }

    // maybe we should have something to eat a DataInsert but by the time
    // we've made it to this state it is probably not needed in practice
    
    // ok, i was wrong, we do get here in practice before they got our
    // DataReply..

    public State received(Node n, MessageObject mo) throws BadStateException {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (mo instanceof Accepted) {
			return this;
		} else if (mo instanceof DataInsert && (origPeer == null || origPeer.equals(((DataInsert) mo).peerIdentity()))) {
			if (logDEBUG)
				Core.logger.log(this, "Eating DataInsert during RequestDone", Logger.DEBUG);
			((DataInsert) mo).eatData(n);
			return this;
		} else if (mo instanceof SendFinished) {
			if (logDEBUG)
				Core.logger.log(this, "Received " + mo + " during RequestDone", Logger.DEBUG);
			return this;
		} else if(mo instanceof Request) { // A looped request
			Request r = (Request) mo;
			if (r.getSource() != null) {
				QueryRejected qr = new QueryRejected(id, r.hopsToLive - 1, "Looped request", r.otherFields);
				RequestSendCallback cb = new RequestSendCallback("QueryRejected (looped request) for " + this, n, this);
				n.sendMessageAsync(qr, r.getSourceID(), Core.hopTime(1,0), cb);
			}
			return this;
		} else {
			/* Various messages we don't care about. Mostly due to
			 * nodes we have routed to sending back messages late.
			 * If something isn't on the list, it MIGHT be important.
			 * We don't even care about StoreData: if they managed to
			 * send us the whole file, but somebody else got there 
			 * first, bad luck!
			 */
			if (mo instanceof DataNotFound
				|| mo instanceof QueryRestarted
				|| mo instanceof QueryRejected
				|| mo instanceof StoreData
				|| mo instanceof InsertReply
				|| mo instanceof QueryAborted
				|| mo instanceof QueueSendFinished
				|| mo instanceof QueueSendFailed) {
			    return this;
			}
			if (mo instanceof DataSend) {
			    // DUH! MUST drop the trailer!
			    ((DataSend) mo).drop(n);
			    Core.logger.log(this, "Got ridiculously late "+mo.getClass().getName()+" on "+this,
			            Logger.NORMAL);
			    return this;
			}
		}
		throw new BadStateException("Received " + mo + " for Request that was already handled");
    }

    public final int priority() {
        return EXPENDABLE;
    }

    public final void lost(Node n) {
        // good!
    }
}

