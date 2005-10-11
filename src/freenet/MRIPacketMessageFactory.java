package freenet;

import freenet.support.Logger;

/**
 * Packet message factory for MRIMessage's.
 */
public class MRIPacketMessageFactory implements PeerPacketMessageFactory {

    public int getMessageTypeCode() {
        return PeerPacketMessage.TYPE_REQUEST_INTERVAL;
    }

    public PeerPacketMessage create(MuxConnectionHandler mch, byte[] buf,
            int offset, int length, boolean needsCopy) {
        if(length < 8) return null; // too short!
        long l = (((long)(buf[offset] & 0xff) << 56) |
                ((long)(buf[offset+1] & 0xff) << 48) |
                ((long)(buf[offset+2] & 0xff) << 40) |
                ((long)(buf[offset+3] & 0xff) << 32) |
                ((long)(buf[offset+4] & 0xff) << 24) |
                ((long)(buf[offset+5] & 0xff) << 16) |
                ((long)(buf[offset+6] & 0xff) <<  8) |
                (buf[offset+7] & 0xff));
        double d = Double.longBitsToDouble(l);
        if (Core.logger.shouldLog(Logger.MINOR, this))
			Core.logger.log(this, "Got MRIPacketMessage: " + d + "ms from " + mch, Logger.MINOR);
        return new MRIPacketMessage(mch.peerHandler, d);
    }
}
