package freenet;

import java.util.Hashtable;
import java.util.Vector;

import freenet.support.Logger;

/**
 * Handles trailer writes. Details of flow control are implemented here.
 * @author amphibian
 */
public class MuxTrailerWriteManager {
	/** Parent PeerHandler */
	public final PeerHandler ph;
	/** The number of bytes actually written to the connection so far */
	long byteCountWritten = 0;
	/** The number of written bytes authorized to send by the other side */
	long byteCountAuthorized = 0;
	/** Hashtable of Integer(id) -> MuxTrailerWriter */
	final Hashtable writers;
//	final MuxPacketLogger plInitWrite;
//	final MuxPacketLogger plWritten;
	/** The queues */
	// FIXME: make this cheaper
	final Vector writersWithPackets;

	MuxTrailerWriteManager(PeerHandler ph) {
		this.ph = ph;
		writers = new Hashtable();
		writersWithPackets = new Vector();
//		plInitWrite = new MuxPacketLogger("initWrite", ph);
//		plWritten = new MuxPacketLogger("written", ph);
	}
	
	/**
	 * Try to write a message. Will be either sent immediately or queued.
	 * @param msg
	 */
	public void write(TrailerChunkPacketMessage msg) {
//		plInitWrite.log(msg);
		ph.innerSendMessageAsync(msg);
//		if(!writersWithPackets.contains(msg.writer)) {
//			writersWithPackets.add(msg.writer);
//		}
//		msg.writer.queueAddLast(msg);
//		tryToSendSomething();
	}
	
	/**
	 * Try to write some messages from the queue.
	 * If we start to send a message, and it fails, we notify it, rather than
	 * throwing.
	 */
//	synchronized void tryToSendSomething() {
//		long diff = byteCountAuthorized - byteCountWritten;
//		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
//		if(logDEBUG)
//			Core.logger.log(this, "Trying to send something: "+this+", diff="+
//					diff, Logger.DEBUG);
//		if(diff < 0) {
//			Core.logger.log(this, "Count authorized: "+byteCountAuthorized+", but count written: "+
//					byteCountWritten+" on "+this+" !!", Logger.NORMAL);
//		} else if (diff > 0) {
//			// Try to send one message
//			while(diff > 0) {
//				if(writersWithPackets.isEmpty()) break;
//				// Pick a random writer, and take the top message
//				// FIXME: maybe we don't need crypto hard rands here?
//				int idx = Core.getRandSource().nextInt(writersWithPackets.size()); 
//				MuxTrailerWriter mw =
//					(MuxTrailerWriter) writersWithPackets.get(idx);
//				TrailerChunkPacketMessage msg =
//					(TrailerChunkPacketMessage)(mw.removeFirstQueued());
//				if(msg == null) {
//					Core.logger.log(this, "Was in writersWithPackets: "+mw+
//							" but no queued packets! for "+this, Logger.NORMAL);
//					writersWithPackets.remove(idx);
//					continue;
//				}
//				int msgLen = msg.getLength();
//				if(msgLen < diff) {
//					byteCountWritten += msgLen;
//					diff -= msgLen;
//					if(logDEBUG)
//						Core.logger.log(this, "Written "+msgLen+" (queued "+msg+"): now "+
//								this, Logger.DEBUG);
//					ph.innerSendMessageAsync(msg);
//					plWritten.log(msg);
//				} else {
//					mw.queueAddFirst(msg);
//					break; // avoid infinite loop
//				}
//				if(mw.queueEmpty())
//					writersWithPackets.remove(idx);
//			}
//		}
//	}

	public synchronized void receivedCredit(TrailerFlowCreditMessage msg) {
//		if(Core.logger.shouldLog(Logger.DEBUG, this))
//			Core.logger.log(this, "Received credit "+msg+": now: "+this,
//					Logger.DEBUG);
//		byteCountAuthorized += msg.creditLength;
//		tryToSendSomething();
	}
	
	public synchronized void failedWrite(TrailerChunkPacketMessage pm) {
		byteCountWritten -= pm.getLength();
		if(Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, "failedWrite("+pm+"): "+this,
					Logger.DEBUG);
	}
	
	/**
	 * Create a TrailerWriter for a trailer send with the given ID
	 * @param id the ID to create
	 * @return a TrailerWriter for this trailing field transfer.
	 */
	public TrailerWriter makeTrailerWriter(int id, boolean wasInsert) {
		synchronized(writers) {
			TrailerWriter tw = new MuxTrailerWriter(this, id, wasInsert);
			Object o = writers.put(new Integer(id), tw);
			if(o != null)
				Core.logger.log(this, "Replaced already existant trailer writer for id="+id+" with new writer, new writer="+tw+", old writer="+o,Logger.ERROR);
			Core.logger.log(this, "New trailer writer for "+id+": "+tw,
					Logger.MINOR);
			return tw;
		}
	}
    
	/**
	 * @param writer
	 */
	public void remove(MuxTrailerWriter writer) {
		writers.remove(new Integer(writer.id));
		writersWithPackets.remove(writer);
	}

	public String toString() {
		long diff = byteCountAuthorized - byteCountWritten;
		return super.toString()+": "+ph+", written "+byteCountWritten+", authorized "+
			byteCountAuthorized+", diff "+diff+", writers with messages queued: "+
			writersWithPackets.size()+", writers total: "+writers.size()+
			", queued bytes on PH: "+ph.trailerChunkQueuedBytes();
			// FIXME: possible deadlock!
	}

	/**
	 * @param trailerMuxCode
	 * @return
	 */
	public TrailerWriter getWriter(int trailerMuxCode) {
		synchronized(this) {
			return (TrailerWriter)(writers.get(new Integer(trailerMuxCode)));
		}
	}

    /**
     * @param tid
     */
    public void terminateWriter(int tid) {
        if(Core.logger.shouldLog(Logger.DEBUG, this))
            Core.logger.log(this, "terminateWriter("+tid+") on "+this,
                    Logger.DEBUG);
        Integer i = new Integer(tid);
        MuxTrailerWriter tw;
        synchronized(this) {
            tw = (MuxTrailerWriter)(writers.get(i));
            writers.remove(i);
        }
        if(tw == null) {
            Core.logger.log(this, "terminateWriter("+tid+"): no writer for "+
                    tid, Logger.MINOR);
            return;
        }
        try {
            tw.close(true, false);
        } catch (Throwable t) {
            Core.logger.log(this, "Could not close "+tw+" in terminateWriter("
                    +tid+"): "+t, t, Logger.ERROR);
        }
    }
}
