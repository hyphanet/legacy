package freenet;

/**
 * PeerPacketMessageFactory for TrailerChunkPacketMessage's
 * @author amphibian
 */
public class TrailerChunkPacketMessageFactory
	implements PeerPacketMessageFactory {

	public int getMessageTypeCode() {
		return PeerPacketMessage.TYPE_TRAILER_CHUNK;
	}

	public PeerPacketMessage create(MuxConnectionHandler mch, byte[] buf,
		int offset, int length, boolean needsCopy) {
		// 2 bytes ID
		// 4 bytes offset
		if(length < 7) return null;
		int id = ((buf[offset] & 0xff) << 8) + 
			(buf[offset+1] & 0xff);
		int keyOffset = buf[offset+2] & 0xff;
		keyOffset = (keyOffset << 8) + (buf[offset+3] & 0xff);
		keyOffset = (keyOffset << 8) + (buf[offset+4] & 0xff);
		keyOffset = (keyOffset << 8) + (buf[offset+5] & 0xff);
		// The rest is data...
		byte[] newBuf = null;
		if(needsCopy) {
			newBuf = new byte[length-6];
			System.arraycopy(buf, offset+6, newBuf, 0, length-6);
			offset = 0;
			length = length-6;
		} else {
			newBuf = buf;
			offset = offset+6;
			length = length-6;
		}
		PeerHandler ph = mch.peerHandler;
		return new TrailerChunkPacketMessage(ph, id, newBuf, offset, length, keyOffset, false);
	}
}
