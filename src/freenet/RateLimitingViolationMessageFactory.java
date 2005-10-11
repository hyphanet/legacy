package freenet;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import freenet.support.Logger;

/**
 * Factory (from network) for RateLimitingViolationMessage 's. 
 * @author amphibian
 */
public class RateLimitingViolationMessageFactory implements
        PeerPacketMessageFactory {

    public int getMessageTypeCode() {
        return PeerPacketMessage.TYPE_RATE_LIMITING_VIOLATED;
    }

    public PeerPacketMessage create(MuxConnectionHandler mch, byte[] buf,
            int offset, int length, boolean needsCopy) {
        // FIXME: improve efficiency by messing around with []s and Double.longbits
        ByteArrayInputStream bais =
            new ByteArrayInputStream(buf, offset, length);
        DataInputStream dis = new DataInputStream(bais);
        try {
            long thisInterval = dis.readLong();
            double averageInterval = dis.readDouble();
            double generousMinInterval = dis.readDouble();
            double curRequestInterval = dis.readDouble();
            return new RateLimitingViolationMessage(thisInterval, averageInterval,
                    generousMinInterval, curRequestInterval, mch.peerHandler); 
        } catch (IOException e) {
            Core.logger.log(this, "Incomplete RateLimitingViolationMessage!: "+e,
                    e, Logger.NORMAL);
            return null;
        }
    }

}
