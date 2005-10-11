package freenet;

import freenet.node.Node;
import freenet.presentation.MuxProtocol;
import freenet.support.Logger;

/**
 * Low-level message sent to tell a node to close the connection
 * and remove us from their routing table.
 * @author amphibian
 */
public class GoAwayPacketMessage extends AbstractNonTrailerPeerPacketMessage {

    private final Node n;
    
    private static final int MESSAGE_LIFETIME=60*60*1000;
    
    public GoAwayPacketMessage(PeerHandler ph, Node n) {
        super(ph,MESSAGE_LIFETIME);
        this.n = n;
    }
    
    public void resolve(Presentation p, boolean onlyIfNeeded) {
        if(!(p instanceof MuxProtocol))
            throw new IllegalArgumentException();
    }

    public void notifyFailure(SendFailedException sfe) {
        if(!peerHandler.isConnected())
            return; // good!
        Core.logger.log(this, "Failed to send "+this,
                Logger.NORMAL);
    }

    public int getPriorityDelta() {
        // Very important message!
        return -100;
    }

    public int getLength() {
    	int length = headerLength();
//    	if(logDEBUG){ //Do an extra sanity check when on debug loglevel
//    		if(getContent().length != length)
//    			Core.logger.log(this,"Invalid message-length calculated, "+getContent().length+" != "+length,Logger.ERROR);
//    	}
        return length;
    }

    public boolean isCloseMessage() {
        return true;
    }

    public byte[] getContent() {
        return super.constructMessage(null, 0, 0);
    }

    public void notifySuccess(TrailerWriter tw) {
        Core.logger.log(this, "Sent "+this, Logger.MINOR);
    }

    public void execute() {
        peerHandler.terminateAll();
        peerHandler.removeFromOCM();
        n.rt.remove(peerHandler.id);
    }

    public int getTypeCode() {
        return PeerPacketMessage.TYPE_GO_AWAY;
    }

	public boolean isRequest() {
		return false;
	}

    public boolean hasMRI() {
        return false;
    }

    public double sendingMRI() {
        return -1;
    }
}
