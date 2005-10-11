package freenet;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.presentation.MuxProtocol;
import freenet.support.Logger;

/**
 * Diagnostic message sent when we violate rate limiting. Intended primarily for
 * debugging the network.
 * FIXME: debatable whether this message has long term implications; consider
 * getting rid of it one day. For example, DoS attacks via filling the logfile.
 * @author amphibian
 */
public class RateLimitingViolationMessage extends AbstractNonTrailerPeerPacketMessage {

    /** This particular request interval i.e. the time in millis between the
     * request that caused this message and the previous one.
     */
    private final long thisInterval;
    /** The average interval for recently received messages */
    private final double averageInterval;
    /** FIXME: This field should perhaps be removed eventually */
    /** Generous estimate of the minRequestInterval, which we enforce */
    private final double generousMinRequestInterval;
    /** The current requestInterval as sent on FNP messages */
    private final double currentRequestInterval;
    private static final int MESSAGE_LIFETIME=20*1000;
    /**
     * @param diff
     * @param average
     * @param minRequestInterval
     * @param actual
     */
    public RateLimitingViolationMessage(long diff, double average, 
            double minRequestInterval, double actual, PeerHandler source) {
    	super(source,MESSAGE_LIFETIME);
        thisInterval = diff;
        averageInterval = average;
        generousMinRequestInterval = minRequestInterval;
        currentRequestInterval = actual;
    }

    public void resolve(Presentation p, boolean onlyIfNeeded) {
        if(!(p instanceof MuxProtocol)) 
            throw new IllegalArgumentException("Should be mux protocol, was "+p);
    }

    public void notifyFailure(SendFailedException sfe) {
        Core.logger.log(this, "AARGH: couldn't send "+this+" on "+peerHandler, 
                Logger.NORMAL);
    }

    public int getPriorityDelta() {
        return -40; 
        // send it quick or not at all... max priority but 20 second expiry
    }

    public int getLength() {
    	int length = 32+headerLength();
    	if(logDEBUG){ //Do an extra sanity check when on debug loglevel
    		if(getContent().length != length)
    			Core.logger.log(this,"Invalid message-length calculated, "+getContent().length+" != "+length,Logger.ERROR);
    }
        return length;
    }

    public byte[] getContent() {
        ByteArrayOutputStream bais = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bais);
        try {
            dos.writeLong(thisInterval);
            dos.writeDouble(averageInterval);
            dos.writeDouble(generousMinRequestInterval);
            dos.writeDouble(currentRequestInterval);
        } catch (IOException e) {
            Core.logger.log(this, "AAAAARGH!: "+e, e, Logger.ERROR);
        }
        byte[] buf = bais.toByteArray();
        return constructMessage(buf,0,buf.length);
    }

    public void notifySuccess(TrailerWriter tw) {
        // Cool
    }

    public void execute() {
        Core.logger.log(this, "Other side ("+peerHandler+
                ") said: Violated request interval!: this interval: "+
                thisInterval+", average interval: "+averageInterval+
                ", generous minimum request interval: "+generousMinRequestInterval+
                ", current minimum request interval: "+currentRequestInterval,
                Logger.NORMAL); // FIXME :)
    }

    public int getTypeCode() {
        return TYPE_RATE_LIMITING_VIOLATED;
    }

	public boolean isRequest() {
		return false;
	}

    public boolean hasMRI() {
        // REDFLAG: False because we don't USE it.
        return false;
    }

    public double sendingMRI() {
        return -1; // see hasMRI().
    }
}
