package freenet;

import freenet.presentation.MuxProtocol;
import freenet.support.Logger;

/**
 * Low level message that carries an MRI. MUCH smaller than an FNP Void!
 */
public class MRIPacketMessage extends AbstractNonTrailerPeerPacketMessage {

    double requestInterval;
    
    /**
     * Constructor used mainly for incoming messages.
     * @param peerHandler
     * @param mri the minimum request interval to include on the packet
     */
    public MRIPacketMessage(PeerHandler peerHandler, double mri) {
        super(peerHandler, 60 * 1000);
        requestInterval = mri;
        if(Double.isInfinite(mri) || Double.isNaN(mri)) {
            Core.logger.log(this, "Illegal MRI: "+mri+" for "+peerHandler,
                    Logger.ERROR);
            throw new IllegalArgumentException("Illegal MRI: "+mri);
        }
    }

    public String toString() {
        return "MRIPacketMessage: "+requestInterval+" for "+peerHandler;
    }
    
    /** Constructor used for outgoing messages */
    public MRIPacketMessage(PeerHandler peerHandler) {
        super(peerHandler, 60 * 1000);
        Core.diagnostics.occurrenceCounting("outputBytesMRI", length());
        requestInterval = peerHandler.getRequestInterval();
        if(Double.isInfinite(requestInterval) || Double.isNaN(requestInterval)) {
            Core.logger.log(this, "Illegal MRI: "+requestInterval+" for "+peerHandler,
                    Logger.ERROR);
            throw new IllegalArgumentException("Illegal MRI: "+requestInterval);
        }
    }
    
    public void resolve(Presentation p, boolean onlyIfNeeded) {
		if(!(p instanceof MuxProtocol))
			throw new IllegalArgumentException();
    }

    public void notifyFailure(SendFailedException sfe) {
        // GRRRR.
    }

    public int getPriorityDelta() {
        return 0;
    }

    public int getLength() {
        return super.headerLength()+8;
    }

    public byte[] getContent() {
        long v = Double.doubleToRawLongBits(requestInterval);
        byte[] b = new byte[8];
        b[0] = (byte)(0xff & (v >> 56));
        b[1] = (byte)(0xff & (v >> 48));
        b[2] = (byte)(0xff & (v >> 40));
        b[3] = (byte)(0xff & (v >> 32));
        b[4] = (byte)(0xff & (v >> 24));
        b[5] = (byte)(0xff & (v >> 16));
        b[6] = (byte)(0xff & (v >>  8));
        b[7] = (byte)(0xff & v);
        return super.constructMessage(b, 0, 8);
    }

    public void notifySuccess(TrailerWriter tw) {
        if(logDEBUG)
            Core.logger.log(this, "Sent "+this+" on "+peerHandler, Logger.DEBUG);
        peerHandler.sentRequestInterval(requestInterval);
    }

    public void execute() {
    	if(Core.logger.shouldLog(Logger.MINOR,this))
    		Core.logger.log(this, "Got "+this, Logger.MINOR);
    }

    public int getTypeCode() {
        return PeerPacketMessage.TYPE_REQUEST_INTERVAL;
    }

    public boolean isRequest() {
        return false;
    }

    public boolean hasMRI() {
        return true;
    }

    public double sendingMRI() {
        return requestInterval;
    }

    /**
     * @return total length of any MRIPacketMessage
     */
    public static int length() {
        return 12;
    }
}
