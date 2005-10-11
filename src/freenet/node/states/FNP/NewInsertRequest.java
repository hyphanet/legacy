package freenet.node.states.FNP;

import freenet.*;
import freenet.node.*;
import freenet.node.states.request.*;
import freenet.message.*;

/**
 * This is the state pertaining to InsertRequests in their
 * initial state.
 */

public class NewInsertRequest extends NewRequest {

    public NewInsertRequest(long id) {
        super(id);
    }
    
    /**
     * Returns the name.
     * @return "New InsertRequest"
     */
    public String getName() {
        return "New InsertRequest";
    }

    public State received(Node n, MessageObject mo) throws StateException {
        if (!(mo instanceof InsertRequest)) {
            throw new BadStateException("expecting DataRequest");
        }
        InsertRequest irmo = (InsertRequest) mo;
        try {
            genReceived(n, irmo, false); 
	    // no point continuing if can't send Accepted back
            if (Core.requestInsertDistribution != null) {
                Core.requestInsertDistribution.add(irmo.searchKey.getVal());
            }

            FeedbackToken ft    = new FNPFeedbackToken(id, sourceID, 
						       irmo.hopsToLive, Core.queueTimeout((int)irmo.searchKey.getExpectedDataLength(), true, false));
            RequestInitiator ri = new RequestInitiator(id,
                                                       irmo.getReceivedTime());
            Pending p = new InsertPending(id, irmo.hopsToLive,
                                          irmo.searchKey, sourceID, ft, ri, false, false);
            // n.logRequest() in genReceived
	    Core.diagnostics.occurrenceCounting("incomingInserts", 1);
            return p.received(n, ri);
        } catch (RequestAbortException rae) {
            return rae.state;
        }
    }
}


