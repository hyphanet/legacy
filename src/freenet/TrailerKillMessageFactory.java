/*
 * Created on Jan 1, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet;

import freenet.support.Logger;

/**
 * @author amphibian
 */
public class TrailerKillMessageFactory implements PeerPacketMessageFactory {

    public int getMessageTypeCode() {
        return PeerPacketMessage.TYPE_TRAILER_KILL;
    }

    public PeerPacketMessage create(MuxConnectionHandler mch, byte[] buf,
            int offset, int length, boolean needsCopy) {
        PeerHandler ph = mch.peerHandler;
        // 2 bytes: tid
        if(length < 2) return null;
        if(length > 2) {
            Core.logger.log(this, "Trailer credit message more than 2 bytes on "+
                    ph, Logger.NORMAL);
        }
        int tid = ((buf[offset] & 0xff) << 8) + 
        	(buf[offset + 1] & 0xff);
        return new TrailerKillMessage(ph, tid);
    }

}
