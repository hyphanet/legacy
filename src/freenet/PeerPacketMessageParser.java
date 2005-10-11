package freenet;

import java.util.Hashtable;

import freenet.support.Logger;

/**
 * A class that keeps a registry of short identifiers for 
 * PeerPacketMessageFactory's, and turns id, byte[] into 
 * PeerPacketMessage's.
 * Could probably be better named.
 * @author amphibian
 */
public class PeerPacketMessageParser {

	Hashtable factories;
	
	PeerPacketMessageParser() {
		factories = new Hashtable();
	}
	
	public void register(PeerPacketMessageFactory factory) {
		factories.put(new Integer(factory.getMessageTypeCode()), factory);
	}
	
	/**
	 * Take a complete message in serialized form, and a type ID,
	 * and turn it into a PeerPacketMessage by calling the appropriate 
	 * PeerPacketMessageFactory.
	 * @param type the message type ID
	 * @param buf the buffer we are to read from
	 * @param offset the offset within the buffer to start from
	 * @param length the length of bytes to read.
	 * @param needsCopy if true, we need to copy any bytes which are kept long term.
	 * If false, we don't need to copy because they have already been copied.
	 * @return the message, or null if we cannot parse it.
	 */
	public PeerPacketMessage parse(MuxConnectionHandler mch, int type, byte[] buf, int offset, int length, boolean needsCopy) {
		if(type == PeerPacketMessage.TYPE_VOID) return null; // void!
		Integer iType = new Integer(type);
		PeerPacketMessageFactory parser = 
			(PeerPacketMessageFactory)(factories.get(iType));
		if(parser == null) {
			Core.logger.log(this, "Unrecognized type ID: "+type+" from "+mch,
					new Exception("debug"), Logger.NORMAL);
			return null;
		}
		PeerPacketMessage ppm = parser.create(mch, buf, offset, length, needsCopy);
		if(ppm == null) {
		    // Sub-parser should log error if necessary
			Core.logger.log(this, "Recognized type "+type+" but parse returned null for message from "+
					mch+" of length "+length, Logger.MINOR);
		}
		return ppm;
	}

	/**
	 * @return a PeerPacketMessageParser, with all known MessageFactory's
	 * registered.
	 */
	public static PeerPacketMessageParser create() {
		PeerPacketMessageParser pmp = new PeerPacketMessageParser();
		// Put more MessageFactory's here
		pmp.register(new IdentifyMessageFactory());
		pmp.register(new HighLevelMessageFactory());
		pmp.register(new TrailerChunkPacketMessageFactory());
		pmp.register(new TrailerFlowCreditMessageFactory());
		pmp.register(new TrailerKillMessageFactory());
		pmp.register(new RateLimitingViolationMessageFactory());
		pmp.register(new GoAwayPacketMessageFactory());
		pmp.register(new AddressDetectedPacketMessageFactory());
		pmp.register(new MRIPacketMessageFactory());
		return pmp;
	}
}
