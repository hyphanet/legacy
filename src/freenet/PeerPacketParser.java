package freenet;

import java.io.IOException;

import freenet.support.Logger;

/**
 * A class to parse a message stream, using multiplexing. Takes a ByteBuffer,
 * and produces PeerPacketMessages, which are then executed.
 * 
 * @author amphibian
 */
public class PeerPacketParser {
	int MESSAGE_LENGTH_BYTES = 2;
	byte[] lengthBuffer = new byte[MESSAGE_LENGTH_BYTES];
	int lengthBuffered = -1;
	byte[] tempBuffer = null; // allocated on demand
	int waitingMessageLength = -1;
	int waitingMessageCurrentBytes = -1;
	PeerPacketMessageParser messageParser;
	MuxConnectionHandler mch;
	boolean logDEBUG;

	public String toString() {
		return super.toString()
			+ " PeerPacketParser[lengthBuffered="
			+ lengthBuffered
			+ ", waitingMessageLength="
			+ waitingMessageLength
			+ ", waitingMessageCurrentBytes="
			+ waitingMessageCurrentBytes
			+ ", mch="
			+ mch + "]";
	}

	PeerPacketParser(PeerPacketMessageParser mp, MuxConnectionHandler mch) {
		messageParser = mp;
		this.mch = mch;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
	}

	/**
	 * Process some incoming bytes. We expect them to be already decrypted.
	 */
	public boolean process(byte[] buf, int start, int length) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"process(buf," + start + "," + length + ") (" + this +")",
				Logger.DEBUG);
		while (true) {
			if (length <= 0)
				return false;
			// Firstly, are we buffering some bytes looking for the rest of a
			// message?
			if (waitingMessageLength > 0) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"waitingMessageLength>0 branch: " + this,
						Logger.DEBUG);
				// 0...waitingMessageCurrentBytes = got already
				int copylen =
					Math.min(
						waitingMessageLength - waitingMessageCurrentBytes,
						length);
				System.arraycopy(
					buf,
					start,
					tempBuffer,
					waitingMessageCurrentBytes,
					copylen);
				waitingMessageCurrentBytes += copylen;
				if (waitingMessageCurrentBytes == waitingMessageLength) {
					if (logDEBUG)
						Core.logger.log(
							this,
							"Filled buffer: " + this,
							Logger.DEBUG);
					if(processMessage(tempBuffer, 0, waitingMessageLength, false))
					    return true;
					waitingMessageCurrentBytes = -1;
					waitingMessageLength = -1;
					tempBuffer = null;
					start += copylen;
					length -= copylen;
					continue;
				}
				if (logDEBUG)
					Core.logger.log(
						this,
						"Waiting for next part of message: " + this,
						Logger.DEBUG);
				return false; // Wait for full message
			} else if (lengthBuffered > 0) {
				// Previous message ended in the middle of the length bytes
				// Basically the same as the first option, but we never delete
				// them
				if (logDEBUG)
					Core.logger.log(
						this,
						"In middle of message length bytes: " + this,
						Logger.DEBUG);
				int copylen =
					Math.min(MESSAGE_LENGTH_BYTES - lengthBuffered, length);
				System.arraycopy(
					buf,
					start,
					lengthBuffer,
					lengthBuffered,
					copylen);
				lengthBuffered += copylen;
				if (lengthBuffered == MESSAGE_LENGTH_BYTES) {
					// Got the length bytes, at least
					int b1 = lengthBuffer[0] & 0xff;
					int b2 = lengthBuffer[1] & 0xff;
					int msgLength = (b1 << 8) + b2;
					Core.logger.log(
						this,
						"Reconstructed length: "
							+ msgLength
							+ " ("
							+ b1
							+ ","
							+ b2
							+ ") for "
							+ this
							+ ")",
						Logger.DEBUG);
					lengthBuffered = -1;
					if (length - copylen >= msgLength) {
						// Got a message!
						if (logDEBUG)
							Core.logger.log(
								this,
								"Got complete message length " + msgLength,
								Logger.DEBUG);
						if(processMessage(buf, start + copylen, msgLength, true))
						    return true;
						start += (copylen + msgLength);
						length -= (copylen + msgLength);
						continue;
					} else {
						// Set up for first branch next cycle
						if (logDEBUG)
							Core.logger.log(
								this,
								"Waiting for rest of message length "
									+ msgLength,
								Logger.DEBUG);
						waitingMessageLength = msgLength;
						tempBuffer = new byte[waitingMessageLength];
						waitingMessageCurrentBytes = length - copylen;
						if (waitingMessageCurrentBytes > 0)
							System.arraycopy(
								buf,
								start+copylen,
								tempBuffer,
								0,
								waitingMessageCurrentBytes);
						return false;
					}
				}
			} else if (length < MESSAGE_LENGTH_BYTES) {
				// We don't have full length bytes
				if (logDEBUG)
					Core.logger.log(
						this,
						"In middle of length bytes: " + this,
						Logger.DEBUG);
				lengthBuffered = length;
				System.arraycopy(buf, start, lengthBuffer, 0, length);
				return false;
			} else {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Got whole message... maybe: " + this,
						Logger.DEBUG);
				// We have full length bytes and hopefully a message
				if (logDEBUG && length > 3)
					Core.logger.log(
						this,
						"buf[start]="
							+ (buf[start] & 0xff)
							+ ", buf[start+1]="
							+ (buf[start + 1] & 0xff)
							+ ", buf[start+2]="
							+ (buf[start + 2] & 0xff)
							+ ", buf[start+3]="
							+ (buf[start + 3] & 0xff)
							+ ", start="
							+ start,
						Logger.DEBUG);
				int msgLength =
					((buf[start] & 0xff) << 8)
						+ (buf[start + 1] & 0xff);
				if (length - MESSAGE_LENGTH_BYTES >= msgLength) {
					// Have whole message
					if(processMessage(
						buf,
						start + MESSAGE_LENGTH_BYTES,
						msgLength,
						true))
					    return true;
					start += (MESSAGE_LENGTH_BYTES + msgLength);
					length -= (MESSAGE_LENGTH_BYTES + msgLength);
					continue; // Parse the next one
				} else {
					// Have a partial message
					int clen = length - MESSAGE_LENGTH_BYTES;
					if (logDEBUG)
						Core.logger.log(
							this,
							"Partial message: got " + clen + " of " + msgLength,
							Logger.DEBUG);
					tempBuffer = new byte[msgLength];
					System.arraycopy(
						buf,
						start + MESSAGE_LENGTH_BYTES,
						tempBuffer,
						0,
						clen);
					waitingMessageLength = msgLength;
					waitingMessageCurrentBytes = clen;
					return false;
				}
			}
		}
	}

	/**
	 * Process a single message
	 * 
	 * @param needsCopy
	 *            if true, any kept bytes from the buffer must be copied as the
	 *            buffer will be reused. This is a parameter because in many
	 *            cases we don't need to copy the bytes at all.
	 * @return true if the connection has become corrupt
	 */
	private boolean processMessage(
		byte[] buf,
		int start,
		int length,
		boolean needsCopy) {
		try {
			if (logDEBUG)
				Core.logger.log(
					this,
					"processMessage(buf,"
						+ start
						+ ","
						+ length
						+ ","
						+ needsCopy
						+ ")",
					Logger.DEBUG);
			// First create the message
			if (length < 2) { // no type bytes!
				throw new IOException("No type bytes!");
			}
			int type =
				((buf[start] & 0xff) << 8)
					+ (buf[start + 1] & 0xff);
			if(type > 32767) return true;
			// Got type
			PeerPacketMessage pm =
				messageParser.parse(mch, type, buf, start + 2, length - 2, needsCopy);
			if (pm == null) {
				// It could be void
				Core.logger.log(
					this,
					"Could not parse message type "
						+ type
						+ " of length "
						+ (length - 2),
					Logger.MINOR);
			} else {
				if (logDEBUG)
					Core.logger.log(this, "executing " + pm + " (" + 
							this +")", Logger.DEBUG);
				synchronized (mch.statsLock) {
					mch.messagesReceived++;
				}
				pm.execute();
				if(pm.hasMRI()) {
				    double mri = pm.sendingMRI();
				    if(Double.isNaN(mri) || Double.isInfinite(mri))
				        Core.logger.log(this, "Invalid MRI "+mri+" from "+pm, Logger.ERROR);
				    else {
				        mch.peerHandler.reportIncomingMRI(mri);
				    if(logDEBUG)
				        Core.logger.log(this, "Reporting MRI from "+pm, Logger.MINOR);
				}
			}
			}
		} catch (Throwable t) {
			Core.logger.log(this, "Caught " + t	+ " in " + this
					+ ".processMessage(buf," + start + "," + length
					+ "," + needsCopy + ")", t, Logger.ERROR);
		}
		return false;
	}
}
