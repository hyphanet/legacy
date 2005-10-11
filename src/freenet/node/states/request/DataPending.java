package freenet.node.states.request;

import freenet.CommunicationException;
import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.message.Accepted;
import freenet.message.DataNotFound;
import freenet.message.DataReply;
import freenet.message.DataRequest;
import freenet.message.QueryRejected;
import freenet.message.QueryRestarted;
import freenet.message.Request;
import freenet.message.StoreData;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.support.Logger;

/**
 * This is the state pertaining to DataRequests that are pending.
 */

public class DataPending extends Pending {

    final boolean wasOnceInsert;
    
	/** For new requests.
	  */
	public DataPending(
		long id,
		int htl,
		Key key,
		Identity orig,
		FeedbackToken ft,
		RequestInitiator ri,
		boolean ignoreDS,
		boolean wasOnceInsert) {
		super(id, htl, key, orig, ft, ri, Node.shouldRouteByNewness(), ignoreDS);
		this.wasOnceInsert = wasOnceInsert;
	}

	/** For restarts.
	  */
	DataPending(RequestState ancestor) {
		super(ancestor);
		wasOnceInsert = ancestor.wasInsert();
	}

	public String getName() {
		return "DataRequest Pending";
	}

	final Request createRequest(FieldSet otherFields, Identity identity) {
		return new DataRequest(id, hopsToLive, searchKey, identity, otherFields);
	}

	//=== message handling ====================================================

	// these two are special because they cause the state to be recreated
	// (restart condition)

	public State receivedMessage(Node n, QueryRejected qr)
		throws StateException {
		try {
			super.receivedQueryRejected(n, qr);
		} catch (EndOfRouteException e) {
			dataNotFound(n, System.currentTimeMillis(), true);
			terminateRouting(false, origHopsToLive > 0, true);
			// because routing produced the QR
			return new RequestDone(this);
		} catch (RequestAbortException rae) {
			// Might not be a RequestDone - terminate WHEN THROWING
			return rae.state;
		}
		return new DataPending(this);
	}

	public State receivedMessage(Node n, QueueSendFailed qsf) {
	    // FIXME: can we assume it's always fatal and forego the exception?
	    try {
            super.receivedQueueSendFailed(n, qsf);
        } catch (RequestAbortException rae) {
            return rae.state;
        }
        return this;
	}
	
	public State receivedMessage(Node n, QueueSendFinished qsf) {
	    super.receivedQueueSendFinished(n, qsf);
	    return this;
	}
	
	public State receivedMessage(Node n, RequestInitiator ri)
		throws StateException {
		if (this.ri == null || ri != this.ri) {
		    if(ri.scheduled())
			throw new BadStateException(
				"Not my request initiator: " + ri + " for " + this);
		    else {
		        Core.logger.log(this, "Got cancelled request initiator: "+ri, 
		                Logger.MINOR);
		        return this;
		    }
		}
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"Running "
					+ ri
					+ " at "
					+ System.currentTimeMillis()
					+ " for "
					+ this,
				ri.initException,
				Logger.DEBUG);
		try {
			super.receivedRequestInitiator(n, ri);
		} catch (EndOfRouteException e) {
			dataNotFound(n, System.currentTimeMillis(), true);
			terminateRouting(false, origHopsToLive > 0, true);
			return new RequestDone(this);
		} catch (RequestAbortException rae) {
			return rae.state;
		}
		return new DataPending(this);
	}

	// the rest don't...

	public State receivedMessage(Node n, DataRequest dr) {
		super.receivedRequest(n, dr);
        return this;
	}

	public State receivedMessage(Node n, QueryRestarted qr)
		throws StateException {
		try {
			super.receivedQueryRestarted(n, qr);
		} catch (RequestAbortException rae) {
			return rae.state;
		}
		return this;
	}

	public State receivedMessage(Node n, Accepted a) throws StateException {
		super.receivedAccepted(n, a);
		return this;
	}

	public State receivedMessage(Node n, DataReply dr) throws StateException {
		return super.receivedDataReply(n, dr);
	}

	public State receivedMessage(Node n, DataNotFound dnf)
		throws StateException {
		if(!shouldAcceptFromRoutee(dnf)) return this;
		if (routedTime >= 0) {
			Core.diagnostics.occurrenceContinuous(
				"hopTime",
				(System.currentTimeMillis() - routedTime) / hopsToLive);
		}
		Core.diagnostics.occurrenceCounting("requestDataNotFound", 1);
		cancelRestart();

			long toq = Math.min(dnf.getTimeOfQuery(), // not stupid...
	System.currentTimeMillis());

		routes.dataNotFound(hopsToLive); // is terminal
		dataNotFound(n, toq, true);
		// Add this key to the FailureTable
		if (!n.ds.contains(searchKey)) // that sort of sucks...
			n.ft.failedToFind(searchKey, hopsToLive);
		return new RequestDone(this);
	}

	public State receivedMessage(Node n, SendFinished sf) {
		try {
			super.receivedSendFinished(n, sf);
		} catch (RequestAbortException rae) {
		    cancelRestart();
			return rae.state;
		} catch (EndOfRouteException e) {
			dataNotFound(n, System.currentTimeMillis(), true);
			// We don't care if it works

			terminateRouting(false, origHopsToLive > 0, true);
			return new RequestDone(this);
		}
		return this;
	}

	public State receivedMessage(Node n, StoreData sd) throws StateException {
		super.receivedStoreData(n, sd);
		return this;
	}
	
//	private final void checkFailureTable(Node n) throws RequestAbortException {
//		long toq = n.ft.shouldFail(searchKey, hopsToLive);
//		if ((origPeer != null) && toq > 0) {
//			dataNotFound(n, toq, true);
//			terminateRouting(false, false, false);
//			// It certainly wasn't *THIS* Routing's fault
//
//			throw new RequestAbortException(new RequestDone(this));
//		}
//		if (routes != null && n.ft.statsShouldIgnoreDNF(searchKey, hopsToLive))
//			routes.setShouldIgnoreDNF();
//	}
//
	private final void dataNotFound(Node n, long toq, boolean sendAsync) {
	    cancelRestart(); // is terminal, can't be restarted
		try {
			RequestSendCallback cb = null;
			if (sendAsync)
				cb = new RequestSendCallback("DataNotFound", n, this);
			ft.dataNotFound(n, toq, cb);
		} catch (CommunicationException e) {
			Core.logger.log(
				this,
				"Failed to reply with DataNotFound: " + e + " for " + this,
				Logger.MINOR);
		}
	}

	protected boolean isInsert() {
		return false;
	}

    public boolean wasInsert() {
        return wasOnceInsert;
    }
}
