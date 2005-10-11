package freenet;

import freenet.presentation.MuxProtocol;
import freenet.support.Logger;

/**
 * A message that represents a credit for a given number of bytes. This tells us that
 * the other side will accept that many bytes, so we can send them. This is used for
 * flow control, which appears to be necessary in muxed trailer sends.
 * Loosely based on WebMux - http://www.w3.org/Protocols/MUX/WD-mux-980722.html
 * @author amphibian
 */
public class TrailerFlowCreditMessage extends AbstractNonTrailerPeerPacketMessage {

	int creditLength; //TODO:Fix so that we can declare as final
	private static final int MESSAGE_LIFETIME=600*1000;
	
	public String toString() {
		return super.toString()+": credit="+creditLength+" on "+peerHandler;
	}
	
	public TrailerFlowCreditMessage(PeerHandler ph, int creditLength) {
		super(ph,MESSAGE_LIFETIME);
		this.creditLength = creditLength;
		if(creditLength < 0 || creditLength > 65535)
			throw new IllegalArgumentException("creditLength must be between 0 and 65535");
	}
	
	public void resolve(Presentation p, boolean onlyIfNeeded) {
		if(!(p instanceof MuxProtocol))
			throw new IllegalArgumentException("Message resolved using wrong Presentation "+p+"!");
	}
	public void notifyFailure(SendFailedException sfe) {
		// Argh!
		Core.logger.log(this, "Failed to send "+this+" on "+peerHandler,
				Logger.MINOR);
		// What now?
		// Resend it.. unless there is a newer one
		resetStartTime(); //Reset starting time since we migh be resent.. (we dont like to get timed-out while enqueued)
		peerHandler.innerSendMessageAsync(this);
		// PH will coalesce credits if necessary, and only send one credit per packet,
		// unless absolutely necessary
	}
	public int getPriorityDelta() {
		// Important message!
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
		 * 2 bytes - length
		 * 2 bytes - type
		 * 2 bytes - credit
		 */
		byte[] buf = new byte[2];
		buf[0] = (byte)(creditLength >> 8);
		buf[1] = (byte)(creditLength >> 8);
        return constructMessage(buf,0,buf.length);
	}

	public void notifySuccess(TrailerWriter tw) {
		if(Core.logger.shouldLog(Logger.MINOR, this))
			Core.logger.log(this, "Succeeded sending "+this, Logger.MINOR);
	}

	public void execute() {
		peerHandler.trailerWriteManager.receivedCredit(this);
	}
	
	public int getTypeCode() {
		return TYPE_TRAILER_CREDIT;
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
