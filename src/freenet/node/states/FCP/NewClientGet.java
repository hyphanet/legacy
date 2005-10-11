package freenet.node.states.FCP;

import freenet.*;
import freenet.node.*;
import freenet.node.states.request.*;
import freenet.support.Logger;
import freenet.message.client.*;
import freenet.client.*;

/**
 * This is the state pertaining to ClientGets in their
 * initial state.
 */

public class NewClientGet extends NewClientRequest {

    // For redirect hint handling.
    private boolean sendHint = false;
    private FreenetURI uri = null;
    private long timeSec = -1;

    public NewClientGet(long id, PeerHandler source,
                        boolean sendHint, FreenetURI uri,
                        long timeSec) {
        super(id, source);
        this.sendHint = sendHint;
        this.uri = uri;
        this.timeSec = timeSec;
    }
    
    /**
     * Returns the name.
     * @return "New ClientGet"
     */
    public String getName() {
        return "New ClientGet";
    }

    public State received(Node n, MessageObject mo) throws StateException {

        if (!(mo instanceof ClientGet)) {
            throw new BadStateException("expecting ClientGet");
        }
        
        Core.diagnostics.occurrenceCounting("inboundClientRequests", 1);

        ClientGet cgmo = (ClientGet) mo;
        
        try {
            ClientKey ckey =
                AbstractClientKey.createFromRequestURI(cgmo.getURI());
            Key k = ckey.getKey();
            if (k == null) throw new KeyException("got null Key");
            Node.recentKeys.add(k);
			Core.diagnostics.occurrenceContinuous("startedRequestHTL",
								cgmo.getHTL());
			
            if(k.log2size() > Node.maxLog2DataSize) {
            	sendMessage(new URIError(id, "Key too big"));
            	return null;
            }
//            if (cgmo.getRemoveLocal()) {
//            	if (n.ds.contains(k)) {
//            		n.ds.remove(k, false);
//            	}
//            }
            FeedbackToken ft    = new ClientGetToken(id, source, ckey, sendHint, uri, timeSec,
                                                     n.bf);
            RequestInitiator ri = new RequestInitiator(id,
                                                       cgmo.getReceivedTime());
            n.logRequest(k);
            return (new DataPending(id, cgmo.getHTL(),
                                    k, null, ft, ri, cgmo.getRemoveLocal(), false)).received(n, ri);
        } catch (KeyException e) {
	    if(Core.logger.shouldLog(Logger.DEBUG,this))
		Core.logger.log(this, 
				"KeyException trying to serve FCP request",
				e, Logger.DEBUG);
            sendMessage(new URIError(id, e.getMessage()));
        }
        return null;
    }
}



