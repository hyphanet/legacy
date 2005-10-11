package freenet.node.states.FNP;

import freenet.*;
import freenet.node.*;
import freenet.node.states.request.*;
import freenet.diagnostics.*;
import freenet.message.*;

/**
 * This is the state pertaining to DataRequests in their
 * initial state.
 */

public class NewDataRequest extends NewRequest {
	private static ExternalContinuous startedRequestHTL = Node.diagnostics.getExternalContinuousVariable("startedRequestHTL");
	private static ExternalCounting incomingRequests = Node.diagnostics.getExternalCountingVariable("incomingRequests");
    public NewDataRequest(long id) {
        super(id);
    }
    
    /**
     * Returns the name.
     * @return "New DataRequest"
     */
    public String getName() {
        return "New DataRequest";
    }

    public State received(Node n, MessageObject mo) throws StateException {
        if (!(mo instanceof DataRequest)) {
            throw new BadStateException("expecting DataRequest");
        }
        DataRequest drmo = (DataRequest) mo;
        try {
            genReceived(n, drmo, true);
	    // Run the request even if we cannot send the accepted back
	    // This is not an attack IMHO because of the overhead of connecting
            if (Core.requestDataDistribution != null) {
                Core.requestDataDistribution.add(drmo.searchKey.getVal());
            }

            FeedbackToken ft    = new FNPFeedbackToken(id, sourceID, 
						       drmo.hopsToLive, Core.queueTimeout((int)drmo.searchKey.getExpectedDataLength(), false, false));
            RequestInitiator ri = new RequestInitiator(id, 
                                                       drmo.getReceivedTime());
            Node.recentKeys.add(drmo.searchKey);
			startedRequestHTL.count(drmo.hopsToLive);
			Pending p = new DataPending(id, drmo.hopsToLive,
                                        drmo.searchKey, sourceID, ft, ri, 
                                        false, false);
            incomingRequests.count(1);
            return p.received(n, ri);
        } catch (RequestAbortException e) {
            return e.state;
        }
    }
}



