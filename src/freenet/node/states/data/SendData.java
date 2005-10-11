package freenet.node.states.data;

import java.io.IOException;
import java.util.Random;

import freenet.AlreadySendingTrailerChunkException;
import freenet.Core;
import freenet.Key;
import freenet.MRIPacketMessage;
import freenet.MessageObject;
import freenet.Presentation;
import freenet.TrailerChunkPacketMessage;
import freenet.TrailerException;
import freenet.TrailerSendFinishedException;
import freenet.TrailerWriter;
import freenet.UnknownTrailerSendIDException;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.ds.KeyInputStream;
import freenet.support.Irreversible;
import freenet.support.Logger;

/**
 * Sends data from the store. If the reading from the store fails, then
 * the sent data will be padded until the end of the next part, where
 * CB_RESTARTED will be written as the control byte. When the send is finished
 * (for better or worse) a DataSent message will be returned to the parent
 * chain indicating the state with a control byte.
 *
 * ^^^^^^  This is wrong. No padding.
 *
 * @author oskar 
 */

public class SendData extends DataState {

	private final TrailerWriter send;
	private final KeyInputStream in;
	private final TrailerWriteCallbackMessage myTWCM;
	// Use a message because of blocking I/O from store
	private final Irreversible closedSend = new Irreversible(false);
	private final Irreversible closedIn = new Irreversible(false);
	private final long length, partSize;
	private Exception abortedException = null;
	private volatile int result = -1;
	private boolean silent = false;
	private boolean inPaddingMode = false;
	private long paddingLength = 0;
	private long sentPadding = 0;
	private int lastPacketLength = 0;
	private final Node n;
	boolean inWrite = false;
	long moved = 0;
	byte[] buffer = null;
	int bufferEndPtr = 0;
	int m = 0;
	private boolean hadDSI = false;
	private boolean waitingForWriteNotify = false;
	private boolean lastNonPaddingChunk = false;
	private final boolean logStats;
	private final boolean insertRelated;
	private static final int PACKET_SIZE = Node.minPaddingChunkSize() - 
		(TrailerChunkPacketMessage.headerSize() + MRIPacketMessage.length()); 

	public SendData(long id, long parent, TrailerWriter send, KeyInputStream in, 
	        long length, long partSize, boolean insertRelated, Node n) {
		super(id, parent);
		if(send == null)
			throw new NullPointerException();
		this.send = send;
		this.in = in;
		this.insertRelated = insertRelated;
		if (in == null)
			throw new IllegalArgumentException("null in");
		this.length = length;
		this.partSize = partSize;
		this.n = n;
		myTWCM = new TrailerWriteCallbackMessage(id, n, this);
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(this, "Creating SendData(" + this +")", Logger.DEBUG);
		if(send != null)
		    this.logStats = send.isExternal();
		else
		    logStats = false;
	}

	public String toString() {
		String s = super.toString()
			+ ": send="
			+ send
			+ ", in="
			+ in
			+ ", moved="
			+ moved
			+ "/"
			+ length
			+ ", partSize="
			+ partSize
			+ ",result="
			+ Presentation.getCBdescription(result)
			+ ",lastPacketLength="
			+ lastPacketLength
			+ ", inPaddingMode="+inPaddingMode;
		s += (paddingLength != 0
				? (",sentPadding=" + sentPadding + "/" + paddingLength)
				: "");
		return s;
	}

	public final long length() {
		return length;
	}

	public final long bytesAvailable() {
		if (inPaddingMode)
			return paddingLength - sentPadding;
		else
			return in.realLength() - moved;
	}

	public final String getName() {
		return "Sending Data";
	}

	/** If sending upstream, you want CB_ABORTED.
	  * If sending downstream, you
	  * want CB_RESTARTED.
	  * @param setSilent if true, silence the DataSent.
	  */
	public final void abort(int cb, boolean setSilent) {
	    // Don't clear silent, only set it
		if(setSilent)
		    silent = setSilent;
		if(result == -1) {
			result = cb;
		} else {
			// If we allow it to be reset, we risk all sorts of horrible race conditions
			// FIXME: will this send the wrong codes if we read CB_RECV_CONN_DIED (for example)?
			// AFAICS they are handled similarly anyway...
			if(result != cb)
				Core.logger.log(this, "Ignoring abort("+Presentation.getCBname(cb)+
						") because result already "+Presentation.getCBname(result)+" for "+this,
						new Exception("debug"), Logger.MINOR);
			return;
		}
		if(!(cb == Presentation.CB_ABORTED ||
						cb == Presentation.CB_RESTARTED)) {
			Core.logger.log(this, "Aborting "+this+" with non-local CB: "+
					Presentation.getCBname(cb), new Exception("debug"), Logger.NORMAL);
		}
		if(logDEBUG) {
			abortedException = new Exception("debug");
			Core.logger.log(
				this,
				"Aborted send for "
					+ this
					+ " with cb="
					+ Integer.toHexString(cb),
				abortedException,
				Logger.DEBUG);
		}
	}

	public final int result() {
		return result;
	}

	public void finalize() {
		try {
			if (closedIn.tryChange())
				in.close();
		} catch (IOException e) {
		    Core.logger.log(this, "Caught "+e+" closing "+in+
		            " in "+this, Logger.NORMAL);
		}
		if (closedSend.tryChange())
			send.close();
		if (logStats && !loggedStats.state())
		    Core.logger.log(this, "Finalized but haven't logged stats and do want to!: "+
		            this, Logger.NORMAL);
	}

	private final void closeSend() {
		if(closedSend.tryChange())
			send.close();
	}
	
	/**
	 * Sheesh! We're too overworked to even try to write CB_ABORTED.
	 */
	public final void lost(Node node) {
		try {
			if(closedIn.tryChange())
				in.close();
		} catch (IOException e) {
			Core.logger.log(this, "I/O error closing KeyInputStream", e, Logger.ERROR);
		}
		closeSend();
	}

	/*
	 * Async sending
	 * 
	 * received(n, DataStateInitiator) -> Reset buffer pointer Read into buffer
	 * Start write
	 * 
	 * closed() -> Set CB to CB_SEND_CONN_DIED Run the block that used to be
	 * finally in received(..) to finish properly
	 * 
	 * written() -> Reset buffer pointer Read into buffer Start write
	 */
	public State received(Node node, MessageObject mo) throws BadStateException {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		
		boolean isDSI = (mo instanceof DataStateInitiator);
		if (hadDSI)
			isDSI = false;
		boolean isTWCM = (mo == myTWCM);
		if (!(isDSI || isTWCM))
			throw new BadStateException("expecting DataStateInitiator");

		if (isDSI) {
			hadDSI = true;
			moved = 0;
			m = 0;
			buffer = new byte[PACKET_SIZE];
		}

		if (isTWCM) {
			waitingForWriteNotify = false;
			if (logDEBUG)
				Core.logger.log(this, "Got " + myTWCM + " for " + this, Logger.DEBUG);
			if (!myTWCM.isFinished()) {
				Core.logger.log(this, "Got a TWCM that was not finished!: " + myTWCM + " for " + this, Logger.ERROR);
				return this;
			}
			if (!myTWCM.isSuccess()) {
				result = Presentation.CB_SEND_CONN_DIED;
				if (logDEBUG)
				    Core.logger.log(this, "Chunk send failed, set result for "+this,
				            Logger.DEBUG);
				if (handleThrowable(null, true) == null)
					return null;
				else
				    Core.logger.log(this, "Huh, did I just waste a state?",Logger.ERROR);
			} else {
				if (inPaddingMode && !lastNonPaddingChunk)
					sentPadding += lastPacketLength;
				else
					moved += lastPacketLength;
				lastNonPaddingChunk = false;
			}
		}

		if (!finished()) {
		    if (!inPaddingMode) {
		        try {
		            bufferEndPtr = doRead();
		            if (bufferEndPtr == -1)
		                throw new IOException("read failed: " + this);
		        } catch (Throwable t) {
		            return handleThrowable(t, false);
		            // will do termination and failure code
		        }
		    }
	    	try {
				if (inPaddingMode)
					sendWritePadding();
				else
					startWrite(bufferEndPtr);
	    	} catch (Throwable t) {
	    	    return handleThrowable(t, true);
	    	}
		}
		return finish(); // Must check whether we are actually finished!
	}

	/**
	 * Read bytes from the store into the buffer, starting at the beginning.
	 * Return the number read.
	 * 
	 * @throws IOException
	 *                   if something breaks
	 */
	protected int doRead() throws IOException {
	    int readLength = (int)Math.min(length - moved, buffer.length);
	    if(readLength == 0) return 0;
	    int countRead = 0;
	    while(countRead < readLength) {
	        int x = in.read(buffer, countRead, readLength - countRead);
	        if(x == -1) break;
	        countRead += x;
	    }
	    if(countRead == 0) countRead = -1;
	    return countRead;
	}

	/**
	 * Start to write some bytes to the connection
	 */
	protected void startWrite(int bytes) throws UnknownTrailerSendIDException, TrailerSendFinishedException, 
		AlreadySendingTrailerChunkException, IOException {
		if(logDEBUG) Core.logger.log(this, "Writing "+bytes+" on "+this, Logger.DEBUG);
		if (bytes <= 0){
			Core.logger.log(this, "Asked to write "+bytes+" bytes on "+this+", should not happen!", Logger.ERROR); //Should never happen
			return;
		}
		myTWCM.reset();
		lastPacketLength = bytes;
		waitingForWriteNotify = true;
		send.writeTrailing(buffer, 0, bytes, myTWCM);
		if(logDEBUG) Core.logger.log(this, "Started write of "+bytes+" on "+this, Logger.DEBUG);
	}

	/**
	 * Handle a throwable thrown during I/O. We are expected to close the
	 * connection, etc.
	 * 
	 * @param t
	 *                  the throwable causing the failure, can be null.
	 * @param wasWriting
	 *                  whether we were writing at the time. false means we were
	 *                  reading.
	 */
	protected State handleThrowable(Throwable t, boolean wasWriting) {
		if (logDEBUG) {
			if (t == null) {
				Core.logger.log(this, "SendData.handleThrowable(null," + wasWriting + ") on " + this, Logger.DEBUG);
				if(logStats)
				    Core.diagnostics.occurrenceCounting("sentDataFailedCancelled", 1);
			} else
				if (logDEBUG)
					Core.logger.log(this, "SendData.handleThrowable(" + t + "," + wasWriting + ") on " + this, t, Logger.DEBUG);
		}
		if (t == null) {
			if (result == -1)
				Core.logger.log(this, "handleThrowable caller must set result if passing null Throwable!", new Exception("grrr"), Logger.ERROR);
		} else if (t instanceof IOException) {
			if (wasWriting) {
			    if(send.wasTerminated()) {
			        if(logDEBUG)
			            Core.logger.log(this, "send terminated, set CB: "+this,
			                    Logger.DEBUG);
			        result = Presentation.CB_RECEIVER_KILLED;
			    } else if(send.wasClientTimeout()) {
			        if(logDEBUG)
			            Core.logger.log(this, "send timed out, set CB: "+this,
			                    Logger.DEBUG);
			        result = Presentation.CB_SEND_TIMEOUT;
			    } else {
			        if(logDEBUG)
			            Core.logger.log(this, "send died, set CB: "+this,
			                    Logger.DEBUG);
			        result = Presentation.CB_SEND_CONN_DIED;
			    }
			} else {
				int ifc = in.getFailureCode();
				if (ifc == -1) {
					if (logDEBUG)
						Core.logger.log(this, "Cache failed between writing " + "and reading for " + Long.toHexString(id) + ": " + t, t, Logger.DEBUG);
					result = Presentation.CB_CACHE_FAILED;
				} else {
				    if(logDEBUG)
				        Core.logger.log(this, "Set CB from input: "+this,
				                Logger.DEBUG);
				    if(ifc == Presentation.CB_OK) {
				        Core.logger.log(this, "Failed reading from "+in+
				                " but it returned CB_OK!! on "+this, Logger.NORMAL);
				        ifc = Presentation.CB_CACHE_FAILED;
				    }
					result = ifc;
				}
				if (result == Presentation.CB_CACHE_FAILED) {
					Core.logger.log(this, "Cache failed signalled after exception " + "after " + moved + " of " + length + " bytes: " + t + " for " + Long.toHexString(id) + " (" + Long.toHexString(parent) + ".", t, Logger.ERROR);
				}
			}
		} else {
		    if(logStats)
		        Core.diagnostics.occurrenceCounting("sentDataFailedWierdException", 1);
			Core.logger.log(this, "Unexpected exception " + t + " in SendData " + this + " (inWrite=" + wasWriting + ")", t, Logger.ERROR);
			result = Presentation.CB_CACHE_FAILED; // well, sorta
		}

		try {
			if(closedIn.tryChange())
				in.close();
		} catch (IOException e) {
			Core.logger.log(this, "I/O error closing KeyInputStream", e, Logger.ERROR);
		}
		Core.logger.log(this, "Send failed for " + Long.toHexString(id) + " (" + Long.toHexString(parent) + " - result=" + Presentation.getCBdescription(result) + ", cause: " + t, Logger.MINOR);
		// Send notification immediately
		if (!silent) {
			n.schedule(new DataSent(this));
			silent = true;
		}
		if (wasWriting) {
			closeSend();
		} else if (!inPaddingMode) {
			if (moved == length) {
				if (result == -1)
					Core.logger.log(this, "WTF? moved = length in handleThrowable " + "for " + this, new Exception("debug"), Logger.NORMAL);
			} else {
				try {
					startWritePadding();
					return this;
				} catch (IOException e) {
					// Failed
					t = e;
				} catch (TrailerException e) {
					Core.logger.log(this, "Got " + e + " starting writing padding for " + this, e, Logger.ERROR);
				}
			}
			// Failed or already at end
			closeSend();
		} else {
			// Padding failed
			closeSend();
		}

		buffer = null; // early GC

		// Won't go through finish(), so log the stat here
		if(logStats)
		    logPlainStats();
		return null;
	}

	private void updateSendFailureDetailedDiagnostics() {
		switch(result) {
			case Presentation.CB_OK:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_OK",1);
				break;
			case Presentation.CB_ABORTED:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_ABORTED",1);
				break;
			case Presentation.CB_RESTARTED:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_RESTARTED",1);
				break;
			case Presentation.CB_BAD_DATA:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_BAD_DATA",1);
				break;
			case Presentation.CB_SEND_CONN_DIED:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_SEND_CONN_DIED",1);
				break;
			case Presentation.CB_RECV_CONN_DIED:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_RECV_CONN_DIED",1);
				break;
			case Presentation.CB_BAD_KEY:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_BAD_KEY", 1);
				break;
			case Presentation.CB_CACHE_FAILED:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_CACHE_FAILED",1);
				break;
			case Presentation.CB_CANCELLED:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_CANCELLED",1);
				break;
			case Presentation.CB_RECEIVER_KILLED:
			    Core.diagnostics.occurrenceCounting("sentDataFailedCB_RECEIVER_KILLED",1);
			    break;
			case Presentation.CB_SEND_TIMEOUT:
			    Core.diagnostics.occurrenceCounting("sentDataFailedCB_SEND_TIMEOUT",1);
			    break;
			default:
				Core.diagnostics.occurrenceCounting("sentDataFailedCB_UNKNOWN", 1);
		}
	}

	protected void startWritePadding()
		throws
			UnknownTrailerSendIDException,
			TrailerSendFinishedException,
			AlreadySendingTrailerChunkException,
			IOException {
		// Pad until end of part
		inPaddingMode = true;
		int controlLength = Key.getControlLength();
		long tmpLen = partSize + controlLength;
		paddingLength = Math.min(tmpLen - moved % tmpLen, length - moved);
		// Either way, it includes the padding byte - and the part hash

		if (!waitingForWriteNotify) {
			if(logDEBUG) Core.logger.log(this, "Writing first padding chunk for " + this, Logger.DEBUG);
			sendWritePadding();
		} else {
			if(logDEBUG) Core.logger.log(this, "Deferring first padding chunk for " + this, Logger.DEBUG);
			lastNonPaddingChunk = true;
		}
	}

	protected void sendWritePadding()
		throws
			UnknownTrailerSendIDException,
			TrailerSendFinishedException,
			AlreadySendingTrailerChunkException,
			IOException {
		byte[] stuffToSend;
		long remainingPadding = paddingLength - sentPadding;
		if (logDEBUG)
			Core.logger.log(
				this,
				"sendWritePadding(): paddingLength="
					+ sentPadding
					+ "/"
					+ paddingLength
					+ " ("
					+ this
					+ ")",
				Logger.DEBUG);
		if (remainingPadding <= 0)
			return; // we will get finished
		if (remainingPadding < (buffer.length /* Key.getControlLength() */
			)) {
			// Last chunk, yay
			stuffToSend = new byte[(int) remainingPadding];
			Random r = new Random(Core.getRandSource().nextLong());
			r.nextBytes(stuffToSend);
			// is this necessary? it used to be 0 padded
			// FIXME: assumes getControlLength() == 1
			stuffToSend[stuffToSend.length - 1] =
				(byte) ((result == Presentation.CB_ABORTED)
					? Presentation.CB_ABORTED
					: Presentation.CB_RESTARTED);
			lastPacketLength = stuffToSend.length;
		} else {
			// Just another chunk
			stuffToSend = new byte[buffer.length];
			Random r = new Random(Core.getRandSource().nextLong());
			r.nextBytes(stuffToSend);
			// is this necessary? it used to be 0 padded
			lastPacketLength = buffer.length;
		}
		myTWCM.reset();
		waitingForWriteNotify = true;
		send.writeTrailing(stuffToSend, 0, stuffToSend.length, myTWCM);
	}

	protected boolean finished() {
		return moved == length
			|| (inPaddingMode && sentPadding >= paddingLength)
			|| (result != -1 && !inPaddingMode);
	}
	
	protected State finish() {
		if (!finished()) {
			if(logDEBUG) Core.logger.log(
				this,
				"Not finishing because moved="
					+ moved
					+ "/"
					+ length
					+ ", inPaddingMode="
					+ inPaddingMode
					+ ", sentPadding="
					+ sentPadding
					+ "/"
					+ paddingLength
					+ " ("
					+ this
					+ ")",
				Logger.DEBUG);
			return this;
		}
		if (inPaddingMode && sentPadding > paddingLength)
			Core.logger.log(
				this,
				"sentPadding="
					+ sentPadding
					+ "/"
					+ paddingLength
					+ " ("
					+ this
					+ ")",
				Logger.NORMAL);
		if (result != -1) {
			// We were aborted
			return handleThrowable(null, false);
		}
		closeSend();
		try {
			if(closedIn.tryChange())
				in.close();
		} catch (IOException e) {
			Core.logger.log(this, "Caught " + e + " closing input (successful): " + this, Logger.NORMAL);
		}
		if (moved == length)
			result = Presentation.CB_OK;
		if(logStats) {
		    logPlainStats();
		}
		if (!silent)
			n.schedule(new DataSent(this));
		return null;
	}

	final Irreversible loggedStats = new Irreversible(false);
	
    /**
     * Log sentData, sentDataNonInserts, sentDataInserts 
     */
    private void logPlainStats() {
        if(!loggedStats.tryChange()) return;
        int success = result == Presentation.CB_OK ? 1 : 0;
        Core.diagnostics.occurrenceBinomial("sentData", 1, success);
        if(insertRelated)
            Core.diagnostics.occurrenceBinomial("sentDataInserts", 1, success);
        else
            Core.diagnostics.occurrenceBinomial("sentDataNonInserts", 1, success);
        // record the full length to diagnostics:
        if(success>0)
            Core.diagnostics.occurrenceContinuous("sentTransferSuccessSize",length);
        else
            Core.diagnostics.occurrenceContinuous("sentTransferFailureSize",length);

        if(success == 0)
            updateSendFailureDetailedDiagnostics();
    }
}
