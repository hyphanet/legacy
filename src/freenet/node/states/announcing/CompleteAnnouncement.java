package freenet.node.states.announcing;
import java.io.IOException;
import java.util.Enumeration;

import freenet.Core;
import freenet.Key;
import freenet.Message;
import freenet.MessageObject;
import freenet.message.AnnouncementComplete;
import freenet.message.AnnouncementFailed;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.states.announcement.NoComplete;
import freenet.support.KeyList;
import freenet.support.Logger;
import freenet.support.io.ParseIOException;
/**
 * Completes the announcement when it receives the final reply.
 *
 * @author oskar
 */

public class CompleteAnnouncement extends AnnouncingState {

    private Key result;
    private NoComplete nc;

    CompleteAnnouncement(ExecuteAnnouncement as, Key result, NoComplete nc) {
        super(as);
        this.result = result;
        this.nc = nc;
    }

    public String getName() {
        return "Complete My Announcement";
    }

    public boolean receives(MessageObject mo) {
        if (mo instanceof NoComplete)
            return nc == mo;
        else if (mo instanceof Message)
            return target.getIdentity().equals(((Message) mo).peerIdentity());
        else
            return false;
    }
    
    public State receivedMessage(Node n, NoComplete noComplete) throws BadStateException {
        if (noComplete != this.nc)
            throw new BadStateException("Not my NoComplete");
        Core.logger.log(this,
            "Announcement attempt failed, no reply from: "+target,
            Logger.MINOR);
        signalFailed(n, false, nc.toString());
	return null;
    }

    public State receivedMessage(Node n, AnnouncementFailed af) {
        nc.cancel();
        Core.logger.log(this, "Announcement attempt failed: "
		     +af.reasonName(), new Exception("debug"),
		     Logger.NORMAL);
	
        signalFailed(n, false, af.reasonName());
	return null;
    }

    public State receivedMessage(Node n, AnnouncementComplete ac) {

        nc.cancel();
        
        Core.diagnostics.occurrenceCounting("announcedTo", hopsToLive);
        signalSuccess(n);

        try {
            ac.readKeys(hopsToLive);
        }
        catch (ParseIOException e) {
            Core.logger.log(this, "Error parsing key list, attempting to continue",
                         e, Logger.MINOR);
        }
        catch (IOException e) {
            Core.logger.log(this, "I/O error reading key list", e, Logger.MINOR);
            return null;
        }
        
        KeyList keys = ac.getKeys();


        /*
        System.err.println("CompleteAnnouncement - received this key list:");
        try {
            keys.writeTo(System.err);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        */
        
        keys.setCompareBase(result);
        keys.sort();
        keys.prune();

        AnnouncementRequestToken ft = new AnnouncementRequestToken(keys.size());

        int i = 0;
        for (Enumeration e = keys.keys() ; 
             e.hasMoreElements() && i < Node.initialRequests ; i++) {
            Key k = (Key) e.nextElement();
            NewInitialRequest.schedule(n, k, ft);
        }

        return null;
    }
}


