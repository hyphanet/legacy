/*
 * Created on Dec 13, 2003
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet;

import freenet.message.Request;
import freenet.support.Logger;

/**
 * Parser for high level i.e. FNP messages in muxing.
 * @author amphibian
 */
public class HighLevelMessageFactory implements PeerPacketMessageFactory {

	public int getMessageTypeCode() {
		return PeerPacketMessage.TYPE_MESSAGE;
	}

	public PeerPacketMessage create(MuxConnectionHandler mch,
		byte[] buf, int offset, int length, boolean needsCopy) {
		try {
		    if(Core.logger.shouldLog(Logger.DEBUG, this))
		        Core.logger.log(this, "Reading message from "+mch+":\n"+
		                new String(buf,offset,length), Logger.DEBUG);
			RawMessage raw = mch.p.readMessage(buf, offset, length);
			if(raw == null) {
				Core.logger.log(this, "Could not parse high level message ("+
						length+" bytes): "+new String(buf, offset, length), Logger.NORMAL);
				return null;
			}
			int id = 0;
			if(raw.trailingFieldLength > 0) {
				// Set the input stream
				// This would happen in CH.innerProcess, if not muxing
				id = raw.trailingFieldMuxID;
				if(id < 0 || id > 65535) {
					Core.logger.log(this, "Invalid message ID: "+id+" on "+raw+
							" from "+mch, Logger.NORMAL);
					return null; // dump the whole message, it makes no sense w/o it's trailer
				}
				TrailerReaderInputStream dis = new TrailerReaderInputStream();
				MuxTrailerReader tr = mch.peerHandler.trailerReadManager.makeMuxTrailerReader(id, dis);
				dis.setTrailer(tr);
				raw.trailingFieldStream = dis;
				if(Core.logger.shouldLog(Logger.DEBUG, this))
					Core.logger.log(this, "Packet: "+raw+", trailer ID: "+id+
					        ", trailer: "+tr, Logger.DEBUG);
			}
			Message msg = mch.t.getMessageHandler().getMessageFor(mch, raw);
			msg.setReceivedTime(System.currentTimeMillis());
			PeerPacketMessage ppm = 
				new HighLevelPeerPacketMessage(msg, null, 
					PeerPacketMessage.NORMAL,  0, mch.peerHandler, mch.t, id);
			if(msg instanceof Request) {
			    mch.peerHandler.actuallyReceivedRequest();
			    //mch.peerHandler.getNode().logRequest();
			    // We logRequest() everywhere where a request is started
			    // Whether internally or externally
			    // Mostly in node/states
			    // Therefore we log in states/FNP/NewRequest, not here.
			}
			return ppm;
		} catch (InvalidMessageException e) {
			Core.logger.log(this, "Caught "+e+" trying to parse FNP message:\n "+
					new String(buf, offset, length),e, Logger.ERROR);
			return null;
		}
	}
}
