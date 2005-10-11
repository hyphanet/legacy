package freenet;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import freenet.node.BadReferenceException;
import freenet.node.NodeReference;
import freenet.support.Logger;
import freenet.support.io.EOFingReadInputStream;
import freenet.support.io.ReadInputStream;

/**
 * PeerPacketMessageFactory for low level Identify messages.
 * 
 * @author amphibian
 */
public class IdentifyMessageFactory implements PeerPacketMessageFactory {

	/*
	 * @see freenet.PeerPacketMessageFactory#create(freenet.MuxConnectionHandler,
	 *      byte[], int, int)
	 */
	public PeerPacketMessage create(
		MuxConnectionHandler mch,
		byte[] buf,
		int offset,
		int length,
		boolean needsCopy) {

		ByteArrayInputStream bais =
			new ByteArrayInputStream(buf, offset, length);
		// FIXME: we should really use the standard io classes if they are at
		// all usable...
		// Copied from FreenetProtocol
		ReadInputStream ris =
			new EOFingReadInputStream(new ReadInputStream(bais));

		FieldSet fs;
		try {
			fs = new FieldSet(ris);
		} catch (IOException e) {
			// Didn't work!
			Core.logger.log(
				this,
				"Caught " + e + " parsing Identify!",
				e,
				Logger.NORMAL);
			return null;
		}
		if(Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, "Identify message fieldset: \n"+fs.toString(), Logger.DEBUG);

		double requestInterval = -1;
		String requestIntervalString = fs.getString("RequestInterval");
		if(requestIntervalString != null) {
		    try {
		        requestInterval = Double.parseDouble(requestIntervalString);
		    } catch (NumberFormatException e) {
		        requestInterval = -1;
		        Core.logger.log(this, "Could not parse "+requestIntervalString+
		                ": "+this+": "+fs, Logger.NORMAL);
		    }
		    fs.remove("RequestInterval");
		}
		
		NodeReference ref;
		try {
			ref = new NodeReference(fs);
		} catch (BadReferenceException e1) {
			Core.logger.log(
				this,
				"Caught " + e1 + " parsing Identify!",
				e1,
				Logger.NORMAL);
			return null;
		}

		return new IdentifyPacketMessage(ref, mch.peerHandler,mch, requestInterval, 
		        freenet.node.Main.node);
	}

	public int getMessageTypeCode() {
		return PeerPacketMessage.TYPE_IDENTIFY;
	}

}
