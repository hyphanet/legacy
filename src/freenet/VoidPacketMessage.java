package freenet;

import freenet.presentation.MuxProtocol;


/**
 * PeerPacketMessage to fill up space.
 * Used for padding.
 */
public class VoidPacketMessage extends AbstractNonTrailerPeerPacketMessage {

    final byte[] content;
    
    public VoidPacketMessage(int payloadLength, PeerHandler peerHandler) {
        super(peerHandler, 60000);
        byte[] buf = new byte[payloadLength];
        Core.getRandSource().nextBytes(buf);
        content = super.constructMessage(buf, 0, payloadLength);
    }

    public void resolve(Presentation p, boolean onlyIfNeeded) {
		if(!(p instanceof MuxProtocol))
			throw new IllegalArgumentException();
    }

    public void notifyFailure(SendFailedException sfe) {
        // Duh
    }

    public int getPriorityDelta() {
        // Makes no difference to packet priority
        return 0;
    }

    public int getLength() {
        return content.length;
    }

    public byte[] getContent() {
        return content;
    }

    public void notifySuccess(TrailerWriter tw) {
        // Cool!
    }

    public void execute() {
        // Do nothing
    }

    public int getTypeCode() {
        return PeerPacketMessage.TYPE_VOID;
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
