/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedList;

import freenet.message.DataInsert;
import freenet.message.HTLMessage;
import freenet.message.Request;
import freenet.node.Node;
import freenet.presentation.MuxProtocol;
import freenet.support.Logger;

/**
 * PeerPacketMessage - a message to be sent to a particular peer node.
 * Unencrypted, not yet attached to any particular connection. Used in
 * PeerHandler's message queue, and as a part of a PeerPacket.
 */
class HighLevelPeerPacketMessage extends AbstractPeerPacketMessage {
	final Message msg;
	final MessageSendCallback cb;
	private final Ticker ticker; // for received messages, .execute()

	// Use a pool to avoid memory churn, no other reason
	private static BAOSPool baosPool = new BAOSPool();

	private boolean finished = false;
	private int trailerMuxCode;

	private final int priority;

	// These are created when we resolve(Presentation)
	private Presentation p = null;
	private RawMessage raw = null;
	private byte[] content = null; // unencrypted - encrypt at PeerPacket level

	public String toString() {
		String superStr = super.toString();
		String rawStr = (raw == null ? null : raw.toString());
		String msgStr = (msg == null ? null : msg.toString());
		String cbStr = (cb == null ? null : cb.toString());
		StringBuffer buf =
			new StringBuffer(
				superStr.length()
					+ (rawStr == null ? 0 : rawStr.length())
					+ (msgStr == null ? 0 : msgStr.length())
					+ (cbStr == null ? 0 : cbStr.length())
					+ 75);
		buf
			.append(super.toString())
			.append(':')
			.append(msgStr)
			.append(':')
			.append(rawStr)
			.append(':')
			.append(cbStr)
			.append(':')
			.append(finished)
			.append(", prio=")
			.append(priority)
			.append(", expiryTime=")
			.append(expiryTime())
			.append('(')
			.append(System.currentTimeMillis() - expiryTime())
			.append(" ms ago)");
		return buf.toString();
	}

	public HighLevelPeerPacketMessage(
		Message msg,
		MessageSendCallback cb,
		int priority,
		long expires,
		PeerHandler ph)
		throws NoMoreTrailerIDsException {
		this(msg, cb, priority, expires, ph, null, 0);
		if (msg.hasTrailer()) {
			//TODO: This wont work since we aren't allocating
			//a writer during the test.. due to this we
			//might very well end up with multiple writers using the same id
			for (int x = 0; x < 256; x++) {
				trailerMuxCode = Core.getRandSource().nextInt() & 0xffff;
				if (ph.trailerWriteManager.getWriter(trailerMuxCode) == null)
					return;
			}
			throw new NoMoreTrailerIDsException();
		}
	}

	public HighLevelPeerPacketMessage(
		Message msg,
		MessageSendCallback cb,
		int priority,
		long expires,
		PeerHandler ph,
		Ticker t,
		int muxID) {
		super(ph,expires);
		this.ticker = t;
		this.trailerMuxCode = muxID;
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"PeerPacketMessage("
					+ ","
					+ msg
					+ ","
					+ cb
					+ ","
					+ priority
					+ ","
					+ expires
					+ ","
					+ ph
					+ " at "
					+ super.toString(),
				Logger.DEBUG);
		this.msg = msg;
		if (msg == null)
			throw new NullPointerException();
		this.cb = cb;
		this.priority = priority;
	}

	public void resolve(Presentation pr) {
		resolve(pr, false);
	}

	/**
	 * Set the message up to send on a connection using a specific
	 * Presentation. Also resets message finished. Can be called multiple
	 * times.
	 * 
	 * @param pr
	 *            the presentation to use to transform the message into a
	 *            RawMessage and thence to a byte array. Can be null to clear
	 *            the cached message.
	 * @param onlyIfNeeded
	 *            if true, don't use the new Presentation if there is an old
	 *            one.
	 */
	public void resolve(Presentation pr, boolean onlyIfNeeded) {
		if (logDEBUG)
			Core.logger.log(
				this,
				"resolve(" + pr + ") for " + this,
				Logger.DEBUG);
		finished = false;
		if (this.p == pr)
			return;
		if (onlyIfNeeded && this.p != null)
			return;
		this.p = pr;
		if (p == null) {
			this.raw = null;
			this.content = null;
			return;
		}
		if(msg instanceof HTLMessage) {
		    if(((HTLMessage)msg).getHopsToLive() > Node.maxHopsToLive) {
		        Core.logger.log(this, "Trying to send "+msg+" - HTL was "+((HTLMessage)msg).getHopsToLive()+
		                "!! - clipping to "+Node.maxHopsToLive, new Exception("debug"), Logger.ERROR);
		        ((HTLMessage)msg).setHopsToLive(Node.maxHopsToLive);
		    }
		}
		this.raw = msg.toRawMessage(p, peerHandler);
		if (msg.hasTrailer()) {
			// Setup the trailer send ID
			raw.trailingFieldMuxID = trailerMuxCode;
		}
		if(logDEBUG)
		    Core.logger.log(this, "Message now: "+raw, Logger.DEBUG);
		try {
			ByteArrayOutputStream baos = baosPool.get();
			baos.reset();
			if(logDEBUG)
			    Core.logger.log(this, "Got BAOS: "+baos, Logger.DEBUG);
			raw.writeMessage(baos);
			if(logDEBUG)
			    Core.logger.log(this, "Written message", Logger.DEBUG);
			baos.flush();
			if(logDEBUG)
			    Core.logger.log(this, "Flushed", Logger.DEBUG);
			byte[] buf = baos.toByteArray();
			if (logDEBUG)
				Core.logger.log(
					this,
					"Content of message to send ("+this+"):\n" + 
					new String(buf),
					Logger.DEBUG);
			if (p instanceof MuxProtocol) {
				this.content = constructMessage(buf, 0, buf.length);
			} else
				this.content = buf;
			baosPool.release(baos);
		} catch (IOException e) {
			Core.logger.log(
				this,
				"Impossible exception: "
					+ e
					+ " writing message "
					+ raw
					+ ","
					+ cb
					+ " to BAIS",
				Logger.ERROR);
			throw new IllegalStateException("Impossible exception!: " + e);
		}
	}

	public byte[] getContent() {
		return content;
	}

	public int getLength() {
		if (content == null) {
			return 0; //FIXME this is a blind fix to an NPE
		}
		return content.length;
	}

	public int trailerMuxCode() {
		return trailerMuxCode;
	}

	public boolean hasTrailer() {
		return msg.hasTrailer();
	}

	public long trailerLength() {
		return msg.trailerLength();
	}

	public boolean isCloseMessage() {
		return raw.close;
	}

	/**
	 * Notify the callback that we successfully sent the message.
	 */
	public void notifySuccess(TrailerWriter tw) {
		peerHandler.registerMessageSent(this, true);
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"notifySuccess(" + tw + ") for " + this,
				Logger.DEBUG);
		if (finished) {
			if(Core.logger.shouldLog(Logger.MINOR, this)) Core.logger.log(
				this,
				"notifySuccess on " + this +" already finished!",
				new Exception("debug"),
				Logger.MINOR);
			return;
		}
		finished = true;
		Core.diagnostics.occurrenceBinomial("messageSuccessRatio", 1, 1);
		long sentTime = System.currentTimeMillis();
		long sendTime = sentTime - getStartTime();

		if (logDEBUG) Core.logger.log(
			this,
			"messageSendTime: " + sendTime + " for " + this,
			Logger.DEBUG);
		if (sendTime > 5 * 60 * 1000) {
			long seconds = sendTime / 1000;
			long secondsSinceConn = (sentTime - peerHandler.lastRegisterTime) / 1000;
			boolean important = peerHandler.probablyNotConnectable();
			if (sendTime > expiryTime())
				important = false;
			Core.logger.log(
				this,
				"Took "
					+ seconds
					+ " seconds to send "
					+ this
					+ "(notifySuccess("
					+ tw
					+ ")! (last connection registered "
					+ secondsSinceConn
					+ " seconds ago on "
					+ peerHandler,
				important ? Logger.NORMAL : Logger.MINOR);
		}
		Core.diagnostics.occurrenceContinuous("messageSendTime", sendTime);
		if (peerHandler.ref != null)
			Core.diagnostics.occurrenceContinuous(
				"messageSendTimeContactable",
				sendTime);
		else
			Core.diagnostics.occurrenceContinuous(
				"messageSendTimeNonContactable",
				sendTime);
		if (msg instanceof freenet.message.Request)
			Core.diagnostics.occurrenceContinuous(
				"messageSendTimeRequest",
				sendTime);
		else
			Core.diagnostics.occurrenceContinuous(
				"messageSendTimeNonRequest",
				sendTime);
		if (!(msg instanceof freenet.message.QueryRejected))
			Core.diagnostics.occurrenceContinuous(
				"messageSendTimeNoQR",
				sendTime);
		if (cb == null)
			return;
		try {
			if (tw != null)
				cb.setTrailerWriter(tw);
			cb.succeeded();
			msg.onSent(peerHandler);
		} catch (Throwable t) {
			Core.logger.log(
				this,
				toString() + ".notifySuccess() caught " + t,
				t,
				Logger.ERROR);
		}
	}

	public void notifyFailure(SendFailedException detail) {
		peerHandler.registerMessageSent(this, false);
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"notifyFailure(" + detail + ") for " + this,
				detail,
				Logger.DEBUG);
		if (logDEBUG)
			Core.logger.log(
				this,
				"notifyFailure() for " + this,
				new Exception("debug"),
				Logger.DEBUG);
		if (finished) {
			Core.logger.log(
				this,
				"notifyFailure on " + this +" already finished!",
				new Exception("debug"),
				Logger.MINOR);
			return;
		}
		finished = true;
		Core.diagnostics.occurrenceBinomial("messageSuccessRatio", 1, 0);
		if (cb == null)
			return;
		try {
			cb.thrown(detail);
			msg.onNotSent(peerHandler);
		} catch (Throwable t) {
			Core.logger.log(
				this,
				toString() + ".notifyFailure(" + detail + ") caught " + t,
				t,
				Logger.ERROR);
			Core.logger.log(
				this,
				toString() + ".notifyFailure: detail was " + detail,
				detail,
				Logger.ERROR);
		}
	}
	public static class BAOSPool {
		private LinkedList lPool = new LinkedList();
		synchronized ByteArrayOutputStream get() {
			if (lPool.size() > 0)
				return (ByteArrayOutputStream) lPool.removeFirst();
			else
				return new ByteArrayOutputStream(256);
		}
		synchronized void release(ByteArrayOutputStream s) {
			lPool.add(s);
		}
	}

	public void execute() {
		// Standard FNP message: add to ticker
		peerHandler.registerMessageReceived(msg);
		ticker.add(0, msg);
		if(peerHandler.ref == null) {
			peerHandler.reportRequestBeforeNodeRef();
		}
	}

	public int getPriorityClass() {
		return priority;
	}

	/*
	 * @see freenet.PeerPacketMessage#getPriorityDelta()
	 */
	public int getPriorityDelta() {
		return msg.getPriority();
	}

	public int getTypeCode() {
		return TYPE_MESSAGE;
	}

	public boolean isRequest() {
		return msg instanceof Request;
	}

    public boolean hasMRI() {
        return false;
    }

    public double sendingMRI() {
        return -1;
    }

    public boolean wasInsert() {
        return msg instanceof DataInsert;
    }
}
