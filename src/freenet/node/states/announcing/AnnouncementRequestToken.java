package freenet.node.states.announcing;

import freenet.Core;
import freenet.FieldSet;
import freenet.MessageSendCallback;
import freenet.Storables;
import freenet.TrailerWriter;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.states.request.FeedbackToken;
import freenet.support.Logger;

/**
 * Logs the results of the initial request process after
 * announcing has completed.
 */
public class AnnouncementRequestToken implements FeedbackToken {

    private int attempts;
    private volatile int succeeded = 0,
                         failed = 0;

    AnnouncementRequestToken(int attempts) {
        this.attempts = attempts;
    }
    
    public void restarted(Node n, long millis, MessageSendCallback cb, String reason) {
        // Doesn't matter, only interested in success/failure
    }
    
    public void insertReply(Node n, long millis) {
        // Can't happen
        Core.logger.log(this, "WTF?!", new Exception("debug"), Logger.ERROR);
    }
    
    public TrailerWriter dataFound(Node n, Storables sto, long ctLength) {
        return null;
    }
    
    public void queryRejected(Node n, int htl, String reason, FieldSet fs,
                              int unreachable, int restarted, int rejected,
                              int backedOff, MessageSendCallback cb) {
        ++failed;
        Core.logger.log(this, "Failed "+failed+" of "+attempts
                           +" initial requests: "+reason, Logger.MINOR);
    }

    public void dataNotFound(Node n, long toq,
			     freenet.MessageSendCallback cb) {
        ++failed;
        Core.logger.log(this, "Failed "+failed+" of "+attempts
		     +" initial requests: data not found", Logger.MINOR);
    }
    
    public void storeData(Node n, NodeReference nr, FieldSet estimator, long rate, int hopsSinceReset, MessageSendCallback cb) {
        ++succeeded;
        Core.logger.log(this, "Succeeded with "+succeeded+" of "+attempts
                           +" initial requests", Logger.MINOR);
    }
}
    


