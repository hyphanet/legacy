package freenet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import freenet.support.Logger;
import freenet.support.io.ReadInputStream;


/**
 * Packet factory for AddressDetectedPacketMessage's.
 */
public class AddressDetectedPacketMessageFactory implements
        PeerPacketMessageFactory {

    public int getMessageTypeCode() {
        return PeerPacketMessage.TYPE_DETECT;
    }

    public PeerPacketMessage create(MuxConnectionHandler mch, byte[] buf,
            int offset, int length, boolean needsCopy) {
        // TODO Auto-generated method stub
        // Just a big string containing a FieldSet
        if(length < 1) return null;
        InputStream s = new ByteArrayInputStream(buf, offset, length);
        try {
            FieldSet fs = new FieldSet(new ReadInputStream(s));
            return new AddressDetectedPacketMessage(fs, mch);
        } catch (IOException e) {
            Core.logger.log(this, "Could not parse: "+new String(buf, offset, length),
                    Logger.NORMAL);
            return null;
        }
    }

}
