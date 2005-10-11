package freenet;

import freenet.support.Logger;

/**
 * PeerPacketMessage impl for TrailerFlowCreditMessage's
 * @author amphibian
 */
public class TrailerFlowCreditMessageFactory
	implements PeerPacketMessageFactory {

	public int getMessageTypeCode() {
		return PeerPacketMessage.TYPE_TRAILER_CREDIT;
	}

	public PeerPacketMessage create(MuxConnectionHandler mch, byte[] buf,
		int offset, int length, boolean needsCopy) {
		PeerHandler ph = mch.peerHandler;
		// 2 bytes: credit
		if(length < 2) return null;
		if(length > 2) {
			Core.logger.log(this, "Trailer credit message more than 2 bytes on "+
					ph, Logger.NORMAL);
		}
		int credit = ((buf[offset] & 0xff) << 8) + 
			(buf[offset + 1] & 0xff);
		return new TrailerFlowCreditMessage(ph, credit);
	}
}
