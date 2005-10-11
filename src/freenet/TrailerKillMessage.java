package freenet;

import freenet.presentation.MuxProtocol;
import freenet.support.Logger;

/**
 * TRAILER_KILL message. Sent when we receive chunks of an unrecognized
 * trailer ID.
 * @author amphibian
 */
public class TrailerKillMessage extends AbstractNonTrailerPeerPacketMessage {

    private final int tid;
    private static final int MESSAGE_LIFETIME=300*1000;
    
    public String toString() {
    	return super.toString()+":"+tid;
    }
    
    public TrailerKillMessage(PeerHandler ph, int id) {
        super(ph,MESSAGE_LIFETIME);
        this.tid = id;
        if(tid < 0 || tid > 65535)
            throw new IllegalArgumentException("Invalid trailer id: "+tid);
    }
    
    public void resolve(Presentation p, boolean onlyIfNeeded) {
        if(!(p instanceof MuxProtocol))
            throw new IllegalArgumentException("Message resolved using wrong Presentation "+p+"!");
    }

    public void notifyFailure(SendFailedException sfe) {
        // Who cares?
    }

    public int getPriorityDelta() {
        return -20;
    }

    public int getLength() {
        // Nice simple fixed size message...
    	int l = 2+headerLength();
    	if(logDEBUG){ //Do an extra sanity check when on debug loglevel
    		if(getContent().length != l)
    			Core.logger.log(this,"Invalid message-length calculated, "+getContent().length+" != "+l,Logger.ERROR);
    }
        return l;
    }

    public byte[] getContent() {
        /** Format:
         * HEADER:
         * 2 bytes - length
         * 2 bytes - type
         * CONTENT:
         * 2 bytes - muxid
         */
    	byte[] buf = {
    			(byte) (tid >> 8) ,
    			(byte) (tid & 0xff)
    	};
        return constructMessage(buf,0,buf.length);
    }

    public void notifySuccess(TrailerWriter tw) {
        // Cool!
    }

    public void execute() {
        peerHandler.trailerWriteManager.terminateWriter(tid);
    }

    public int getTypeCode() {
        return TYPE_TRAILER_KILL;
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
