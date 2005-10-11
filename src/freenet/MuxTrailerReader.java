package freenet;

import java.util.LinkedList;

import freenet.support.Irreversible;
import freenet.support.Logger;

/**
 * Class that accepts TrailerChunkPacketMessage's, reorders or terminates if necessary,
 * and forwards packets to a TrailerReadCallback.
 * @author amphibian
 */
public class MuxTrailerReader {

	private int curPos=0;
	private final TrailerReadCallback cb;
	final int id;
	private final Irreversible closed = new Irreversible(false);
	private final PeerHandler ph;
	private final LinkedList chunks = new LinkedList(); // list of trailer chunks
	private int totalChunksRecieved = 0; //For debugging purposes mostly
	/** Has a chunk been requested by the client? */
	private boolean wantChunk;
	private static boolean logDebug=true;

	public String toString() {
		return super.toString() + ": ID="+id+", curPos="+curPos+", "+chunks.size()+" chunks pending, "+totalChunksRecieved+" chunks recieved, wantChunk="+wantChunk+", ph="+ph;
	}
	
	MuxTrailerReader(TrailerReadCallback cb, int id, PeerHandler ph) {
		logDebug=Core.logger.shouldLog(Logger.DEBUG,this);
		this.id = id;
		this.cb = cb;
		this.ph = ph;
	}
	
	/**
	 * Receive a message from the network.
	 * If it is ahead of the current position, discard it and grumble loudly.
	 * If it is dead on, feed it to the client and move on the current position.
	 * If it is behind the current position, ignore it.
	 * @param message
	 */
	public synchronized void received(TrailerChunkPacketMessage message) {
		if(closed.state()) {
			Core.logger.log(this, "Ignoring late chunk after closed trailer: "+message+" for "+this,
					Logger.MINOR);
			ph.trailerReadManager.consumed(message);
			return;
		}
		chunks.addLast(message);
		totalChunksRecieved++;
		tryToForwardChunk();
	}

	synchronized void tryToForwardChunk() {
		if(chunks.isEmpty()) return;
		if(!wantChunk) return;
		TrailerChunkPacketMessage message =
			(TrailerChunkPacketMessage) chunks.removeFirst();
		ph.trailerReadManager.consumed(message);
		if(closed.state()) return;
		if(message.keyOffset == curPos) {
			// Got a valid chunk
			curPos += message.length;
			if(logDebug)
				Core.logger.log(this, toString()+" received a valid packet up to offset "+curPos+" from "+
						(curPos-message.length)+": "+message+" on "+this, Logger.DEBUG);
			wantChunk = false;
			try {
				cb.receive(message.data, message.offset, message.length);
			} catch (Throwable t) {
				try {
					Core.logger.log(this, "Caught "+t+" sending packet length "+message.length+
							" to "+cb, t, Logger.ERROR);
				} catch (Throwable tt) {
					Core.logger.log(this, "Caught "+tt+" logging throwable sending packet length "
							+message.length, tt, Logger.ERROR);
					Core.logger.log(this, "Original was "+t, t, Logger.ERROR);
				}
			}
		} else if (message.keyOffset < curPos) {
			Core.logger.log(this, "Ignoring "+message+": stream at "+curPos+", message starts: "+
					message.keyOffset+" ("+this+")", Logger.NORMAL);
		} else if (message.keyOffset > curPos) {
			// Uh oh...
			Core.logger.log(this, "Got a trailer chunk ahead of our time!: message starts "+
					message.keyOffset+", stream currently at "+curPos+" from "+message+" on "+this,
					Logger.ERROR);
			close();
		}
	}
	
	/**
	 * Request a chunk to be sent to the callback.
	 */
	public synchronized void requestChunk() {
		wantChunk = true;
		if(!chunks.isEmpty()) {
			tryToForwardChunk();
		}
	}
	
	public void close() {
	    close(false);
	}
	
	/**
	 * Close the trailer send
	 */
	public void close(boolean kill) {
        if(!closed.tryChange()) return; //Already changed (=already closed)
        if(Core.logger.shouldLog(Logger.DEBUG,this)) Core.logger.log(this, "Closing "+this+", kill="+kill, Logger.DEBUG);
		ph.trailerReadManager.remove(this);
		while(!chunks.isEmpty()) {
			TrailerChunkPacketMessage msg = (TrailerChunkPacketMessage)chunks.removeFirst();
			ph.trailerReadManager.consumed(msg);
		}
		try {
			cb.closed();
		} catch (Throwable t) {
			try {
				Core.logger.log(this, "Caught "+t+" notifying "+cb+" of closure of "+
						this, t, Logger.ERROR);
			} catch (Throwable tt) {
				Core.logger.log(this, "Caught "+tt+" logging notification of closure of "+
						this, tt, Logger.ERROR);
				Core.logger.log(this, "Original throwable was "+t, t, Logger.ERROR);
			}
		}
		if(kill) {
		    TrailerKillMessage km = new TrailerKillMessage(ph, id);
		    ph.innerSendMessageAsync(km);
		}
	}
}
