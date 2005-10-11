package freenet;

import freenet.presentation.MuxProtocol;
import freenet.support.Logger;

/**
 * @author amphibian
 */
public class TrailerChunkPacketMessage extends AbstractPeerPacketMessage {

	/** Format:
	 * 2 bytes length 
	 * 2 bytes type ID
	 * ^^^ part of all messages
	 * 2 bytes trailer ID
	 * 4 bytes offset in bytes within stream (including CBs etc)
	 * data
	 */
	
	// Send-related stuff
	MuxTrailerWriter writer; // can be null, if this was received rather than sent
	TrailerWriteCallback cb; // can be null
	int failuresThisChunk = 0;
	private final int maxFailuresThisChunk = 5;
	boolean succeeded = false;
	private static final int MESSAGE_LIFETIME=600*1000;
	final boolean fromInsert;
	
	
	// don't copy the data - hopefully these won't be around long, and the orig buffer won't be huge
	final byte[] data;
	final int offset;
	final int length;
	
	final int id; // trailer id
	MuxTrailerWriteManager writeManager;
	/** The offset within the file */
	final int keyOffset;
	
	private static boolean logDebug=true;
	
	public String toString() {
		return super.toString()+": id="+id+", keyOffset="+keyOffset+
			", length="+length+", cb="+cb;
	}
	
	/*
	 * Create a trailer chunk packet message for a message RECEIVED from the network.
	 * Used by TrailerChunkMessageFactory.
	 * 
	 */
	public TrailerChunkPacketMessage(PeerHandler ph,int id,byte[] block,int offset, int length, int keyOffset,
	        boolean fromInsert) {
		super(ph,MESSAGE_LIFETIME);
		logDebug = Core.logger.shouldLog(Logger.DEBUG,this);
		this.id = id;
		this.data = block;
		this.offset = offset;
		this.length = length;
		this.keyOffset = keyOffset;
		this.fromInsert = fromInsert;
	}
	/**
	 * Create a trailer chunk packet to be written to the network.
	 * Used by MuxTrailerWriter.
	 * @param writer
	 * @param block
	 * @param offset
	 * @param length
	 * @param cb
	 */
	public TrailerChunkPacketMessage(MuxTrailerWriter writer, byte[] block, 
									 int offset, int length, TrailerWriteCallback cb, int keyOffset) {
		this(writer.writeManager.ph,writer.id,block,offset,length,keyOffset, writer.fromInsert());
		this.writer = writer;
		this.cb = cb;
		this.writeManager = writer.writeManager;
		if(super.headerLength() + 2 + 4 + length > 65535) 
			throw new IllegalArgumentException("Packet too long!");
		if(logDebug)
			Core.logger.log(this, "Created "+this+" for "+writer+":"+cb, Logger.DEBUG);
	}

	public void resolve(Presentation p, boolean onlyIfNeeded) {
		if(!(p instanceof MuxProtocol))
			throw new IllegalArgumentException();
	}

	public void notifyFailure(SendFailedException sfe) {
		if(writer == null) {
			Core.logger.log(this, "notifyFailure("+sfe+") called on packet with no writer!",
					new Exception("debug"), Logger.ERROR);
			return;
		}
		// Now what?
		failuresThisChunk++;
		if(peerHandler.removingFromOCM()) {
		    // Drop it
		    Core.logger.log(this, "Lost connection permanently: "+
		            ", terminating send "+writer+" for "+this, Logger.NORMAL);
		    writer.writeManager.failedWrite(this);
		    writer.close();
		    cb.closed();
		    return;
		}
		Core.logger.log(this, "Failed to send ("+failuresThisChunk+
				") "+this+": "+sfe, sfe, Logger.NORMAL);
		if(failuresThisChunk > maxFailuresThisChunk) {
			// Drop it
			Core.logger.log(this, "Exceeded maximum failure count: "+failuresThisChunk+
					", terminating send "+writer+" for "+this, Logger.NORMAL);
			writer.writeManager.failedWrite(this);
			writer.close();
			cb.closed();
		} else {
			// Retry
			Core.logger.log(this, "Retrying "+this+" on "+peerHandler,
					Logger.MINOR);
			peerHandler.innerSendMessageAsync(this);
			resetStartTime();
		}
	}

	public void notifySuccess(TrailerWriter tw) {
		if(succeeded) {
			Core.logger.log(this, "notifySuccess() called twice on "+this,
					Logger.ERROR);
			return;
		}
		succeeded = true;
		if(writer == null) {
			Core.logger.log(this, "notifySuccess() called on packet with no writer!",
					new Exception("debug"), Logger.ERROR);
			return;
		}
		// Succeeded sending this chunk!
		// Add the bytes sent to the counter on the parent
		if(logDebug)
			Core.logger.log(this, "notifySuccess on "+this+" took ", Logger.DEBUG);
		writer.written(keyOffset, length);
		// That will also allow another chunk to be queued
		cb.written();
		Core.diagnostics.occurrenceCounting("outputBytesTrailerChunks", getLength());
		if(fromInsert)
		    Core.diagnostics.occurrenceCounting("outputBytesTrailerChunksInsert", getLength());
		Core.diagnostics.occurrenceContinuous("messageSendTimeTrailerChunk",
		        System.currentTimeMillis() - getStartTime()); 
	}

	public int getPriorityDelta() {
	    // more important than QueryRejected, but less than any other message
		return 0; 
	}

	public int getLength() {
		// Length includes the typeID bytes!
    	int l = length + super.headerLength() + 2 + 4;
    	if(logDebug){ //Do an extra sanity check when on debug loglevel
    		if(getContent().length != l)
    			Core.logger.log(this,"Invalid message-length calculated, "+getContent().length+" != "+l,Logger.ERROR);
	}
        return l;
	}

	public byte[] getContent() {
		// Not likely to be called often, and we don't need to getContent to getLength
		int baseHeaderLength = super.headerLength();
		byte[] content = new byte[baseHeaderLength + 2 + 4 + length];
		super.fillInHeader(content, length + 2 + 4);
		content[baseHeaderLength] = (byte) (id >> 8);
		content[baseHeaderLength+1] = (byte) (id & 0xff);
		
		content[baseHeaderLength+2] = (byte) ((keyOffset >> 24) & 0xff);
		content[baseHeaderLength+3] = (byte) ((keyOffset >> 16) & 0xff);
		content[baseHeaderLength+4] = (byte) ((keyOffset >> 8) & 0xff);
		content[baseHeaderLength+5] = (byte) (keyOffset & 0xff);
		System.arraycopy(data, offset, content, baseHeaderLength+6, length);
		return content;
	}

	public void execute() {
		peerHandler.trailerReadManager.received(this);
	}

	public int getTypeCode() {
		return PeerPacketMessage.TYPE_TRAILER_CHUNK;
	}

	//Interesting packet.. No trailer but a trailerMuxCode :)
    public boolean hasTrailer() {
        return false;
    }
    public long trailerLength() {
        return 0;
    }
	public int trailerMuxCode() {
		return id;
	}

	public boolean isRequest() {
		return false;
	}

    public boolean hasMRI() {
        return false;
    }

    public double sendingMRI() {
        return -1;
    }

    public static int headerSize() {
        return 10;
    }

    public boolean wasInsert() {
        // We don't start the trailer.
        return false;
    }
}
