package freenet;

import freenet.support.Logger;

/**
 * Base class for building simple PeerPacketMessage's.
 * Contains:
 * 1. Utility methods for rendering a message
 * 2. Expiration/StartTime handling
 * 3. PeerHandler/Identity handling
 * 4. Default implementations of some PeerPacketMessage methods
 * 
 * @author amphibian
 * @author Iakin
 */
public abstract class AbstractPeerPacketMessage implements PeerPacketMessage {
	private long startTime;
	private final long maxAge;
	protected final PeerHandler peerHandler;
	
	protected static boolean logDEBUG;
	private static int logDEBUGRefreshCounter=0; 
	
	/**
	 * Format a message - length bytes, type bytes, data.
	 * @param data buffer to read data from
	 * @param offset offset to start reading at
	 * @param length length of data to pack into message
	 * @return a formatted message ready for encryption
	 */
	protected byte[] constructMessage(byte[] data, int offset, int length) {
		if(length > 65535) throw new IllegalArgumentException();
		byte[] buf = new byte[2 + 2 + length];
		fillInHeader(buf, length);
		if(length > 0)
		    System.arraycopy(data, offset, buf, 4, length);
		return buf;
	}
	
	protected final int headerLength() {
		return 4;
	}
	
	AbstractPeerPacketMessage(PeerHandler peerHandler,long maxAge){
		this.peerHandler = peerHandler;
		startTime = System.currentTimeMillis();
		if (peerHandler == null)
			throw new NullPointerException();
		this.maxAge = maxAge <= 0?-1:maxAge;
		if(Core.logger.shouldLog(Logger.MINOR, this))
	        Core.logger.log(this, "Setting timeout to -1 for "+super.toString(), 
	                new Exception("debug"), Logger.MINOR);
		if(maxAge > 0 && maxAge < 3)
		    Core.logger.log(this, "Odd timeout: "+maxAge+" - wrong way around args?",
		            new Exception("debug"), Logger.ERROR);
		
		if(logDEBUGRefreshCounter%1000 == 0) //Dont refresh the flag too often, saves us some CPU
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		logDEBUGRefreshCounter++;
	}
	
	/**
	 * Fill in the header bytes.
	 * @param buf
	 * @param len the payload length. We will add 2 to this to get the
	 * overall length, because the overall length includes the type ID.
	 * @return
	 */
	protected int fillInHeader(byte[] buf, int len) {
		len += 2;
		if(len > 65535 || len < 0)
			throw new IllegalArgumentException("invalid payload length: "+len);
		buf[0] = (byte)(len >> 8);
		buf[1] = (byte)(len & 0xff);
		int type = getTypeCode();
		if(type > 65535 || type < 0)
			throw new IllegalArgumentException("invalid payload type: "+type);
		buf[2] = (byte)(type >> 8);
		buf[3] = (byte)(type & 0xff);
		return 4;
	}
    public String toString() {
        return super.toString() + "@" + peerHandler;
    }
	
	public final Identity getIdentity() {
		return peerHandler.getIdentity();
	}
	
	//Override in subclasses to use other priority than NORMAL
	public int getPriorityClass() {
		return PeerPacketMessage.NORMAL;
	}
	
    //Override if your message is one...
	public boolean isCloseMessage() {
		return false;
	}
    public final long expiryTime() {
        return startTime+maxAge;
    }
    protected final void resetStartTime(){
    	startTime = System.currentTimeMillis();
    }
    protected final long getStartTime(){
    	return startTime;
    }
}
