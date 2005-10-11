package freenet.node.states.announcing;

import freenet.Core;
import freenet.Key;
import freenet.MessageObject;
import freenet.node.BadStateException;
import freenet.node.EventMessageObject;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.StateException;
import freenet.node.states.request.DataPending;
import freenet.node.states.request.RequestInitiator;
import freenet.node.states.request.RequestSendCallback;
import freenet.support.Logger;

public class NewInitialRequest extends State {

    static void schedule(Node n, Key k, AnnouncementRequestToken ft) {
        long id = Core.getRandSource().nextLong();
        Core.logger.log(NewInitialRequest.class,
            "Scheduling post-announcement request on chain "
            +Long.toHexString(id)+", for key "+k,
            Logger.NORMAL);
        n.schedule(new MakeInitialRequest(id, Node.initialRequestHTL, k, ft));
    }

    private static class MakeInitialRequest extends EventMessageObject {
        private int htl;
        private Key key;
        private AnnouncementRequestToken ft;
        private MakeInitialRequest(long id, int htl, Key key,
                                   AnnouncementRequestToken ft) {
            super(id, true);
            this.htl = htl;
            this.key = key;
            this.ft  = ft;
        }
        public State getInitialState() {
            return new NewInitialRequest(this.id, htl, key, ft);
        }
    }

    
    private int htl;    
    private Key key;
    private AnnouncementRequestToken ft;

    private NewInitialRequest(long id, int htl, Key key,
                              AnnouncementRequestToken ft) {
        super(id);
        this.htl = htl;
        this.key = key;
        this.ft  = ft;
    }

    public String getName() {
        return "New Initial Request post announcement";
    }

    public State received(Node n, MessageObject mo) throws StateException { 
        if (!(mo instanceof MakeInitialRequest))
            throw new BadStateException("Expecting MakeInitialRequest");
        RequestInitiator ri = new RequestInitiator(id,
                                                   System.currentTimeMillis());
        n.logRequest(null);
        return (new DataPending(id, htl, key, null, ft, ri, false, false)).
        	received(n, ri);
    }

    public void lost(Node n) {
        ft.queryRejected(n, htl, "Node lost state", null, 0, 0, 0, 0,
			 new RequestSendCallback("QueryRejected (lost NewAnnouncement)", n, this));
    }
}


