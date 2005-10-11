package freenet;

import java.util.Enumeration;
import java.util.Hashtable;

import freenet.support.DoublyLinkedList;
import freenet.support.DoublyLinkedListImpl;
import freenet.support.Logger;

/**
 * Implement flow control - read side.
 * 
 * @author amphibian
 */
public class MuxTrailerReadManager {
	long totalBytesRead;
	long totalBytesInitiallyRead;
	long totalBytesAuthorized;
	long waitingBytes;
	final PeerHandler ph;
	final int maxBufferedBytes;
	final Hashtable readers = new Hashtable();
	final Hashtable chunksWaiting = new Hashtable();
	DoublyLinkedList unrecognizedIDsList;
	Hashtable unrecognizedIDsHash;
	static final int MAX_UNRECOGNIZED_IDS = 16;
//	static final boolean logPackets = false;
//	final MuxPacketLogger plInitReceived;
//	final MuxPacketLogger plReceived;

	public String toString() {
		return super.toString()
			+ ": read: " + totalBytesRead + ", init read: " + 
			totalBytesInitiallyRead + ", authorized: " + totalBytesAuthorized
			+ ", waiting: " + waitingBytes + ", max buffered: " 
			+ maxBufferedBytes + ", readers: " + readers.size() 
			+ ", chunks waiting: " + chunksWaiting.size() + " on " + ph;
	}

	MuxTrailerReadManager(PeerHandler ph, int maxBufferedBytes) {
		totalBytesRead = 0;
		totalBytesAuthorized = 0;
		totalBytesInitiallyRead = 0;
		this.ph = ph;
		this.maxBufferedBytes = maxBufferedBytes;
//		if (logPackets) {
//			plInitReceived = new MuxPacketLogger("initRead", ph);
//			plReceived = new MuxPacketLogger("read", ph);
//		} else {
//			plInitReceived = null;
//			plReceived = null;
//		}
	}

	synchronized TrailerFlowCreditMessage firstConnection() {
		TrailerFlowCreditMessage msg =
			new TrailerFlowCreditMessage(ph, maxBufferedBytes);
		totalBytesAuthorized += maxBufferedBytes;
		return msg;
	}

	/**
	 * Process a TrailerChunkPacketMessage we have received.
	 * 
	 * @param message
	 *            the message
	 */
	public void received(TrailerChunkPacketMessage message) {
		MuxTrailerReader mtr;
		synchronized(this) {
			totalBytesInitiallyRead += message.getLength();
			mtr =
				(MuxTrailerReader) (readers.get(new Integer(message.id)));
		}
//		plInitReceived.log(message);
		if (mtr == null) {
		    if(unrecognizedIDsHash == null)
		        unrecognizedIDsHash = new Hashtable();
		    if(unrecognizedIDsList == null)
		        unrecognizedIDsList = new DoublyLinkedListImpl();
		    int id = message.id;
		    UnrecognizedTrailerIDItem item =
		        (UnrecognizedTrailerIDItem)(this.unrecognizedIDsHash.get(new Integer(id)));
		    long now = System.currentTimeMillis();
		    if(item == null) {
			    if(now - Core.beganTime > 10*60*1000) {
			Core.logger.log(this, "Unrecognized trailer ID: "
					+ message.id + " on " + ph + " for "
					+ this + " (" + message + "), Number of readers="+readers.size(), Logger.NORMAL);
			    }
		        item = new UnrecognizedTrailerIDItem(id, now);
		        this.unrecognizedIDsList.push(item);
		        while(unrecognizedIDsList.size() > MAX_UNRECOGNIZED_IDS) {
		            UnrecognizedTrailerIDItem idel = 
		                (UnrecognizedTrailerIDItem) unrecognizedIDsList.shift();
		            unrecognizedIDsHash.remove(idel.iid);
		        }
		        unrecognizedIDsHash.put(item.iid, item);
		    } else {
		        // Already present
		        if(now - item.lastTime > 60000) {
		            Core.logger.log(this, "Unrecognized trailer ID after "+
		                    (now - item.lastTime) + "ms : "+
		                    + message.id + " on " + ph + " for "
			                + this + " (" + message + 
			                "), Number of readers="+readers.size(), Logger.NORMAL);
		            item.lastTime = now;
		        }
		    }
		    
			consumed(message);
			TrailerKillMessage msg = new TrailerKillMessage(ph, message.id);
			ph.innerSendMessageAsync(msg);
			return;
		} else {
			// Queue it on the mtr, or consume it on the mtr
			// Send it to the mtr
			// It will notify us if it has consumed it
			mtr.received(message);
		}
	}

	protected class UnrecognizedTrailerIDItem extends DoublyLinkedListImpl.Item {
	    final int id;
	    final Integer iid;
	    long lastTime;
	    int hits;
	    
	    UnrecognizedTrailerIDItem(int id, long now) {
	        this.id = id;
	        iid = new Integer(id);
	        lastTime = now;
	        hits = 0;
	    }
	}
	
	/**
	 * Notify us that some bytes have been consumed.
	 * 
	 * @param msg The message that has been consumed.
	 */
	public void consumed(TrailerChunkPacketMessage msg) {
		int msgLength = msg.getLength();
//		plReceived.log(msg);
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"Consumed packet of length " + msgLength + " on " + this,
				Logger.DEBUG);
//		TrailerFlowCreditMessage credit = null;
//		synchronized(this) {
//			totalBytesRead += msgLength;
//			long diff = totalBytesAuthorized - totalBytesRead;
//			if (diff < 0) {
//				// Read more than authorized!
//				Core.logger.log(this, "Read too many bytes for " + this,
//						Logger.NORMAL);
//			} else if (diff > 0 && diff < maxBufferedBytes) {
//				if (diff > 65535)
//					diff = 65535;
//				diff = maxBufferedBytes - diff;
//				if (diff > 0) {
//					// They can send us some more bytes
//					totalBytesAuthorized += diff;
//					credit = new TrailerFlowCreditMessage(ph, (int) diff);
//				}
//			}
//		}
//		if(credit != null) ph.innerSendMessageAsync(credit);
	}

	/**
	 * Create a multiplexed trailer reader from an ID, with a callback
	 * 
	 * @param tid
	 *            the trailer's multiplexing ID
	 * @param cb
	 *            a TrailerReadCallback to report status to
	 * @return
	 */
	public MuxTrailerReader makeMuxTrailerReader(
		int tid,
		TrailerReadCallback cb) {
		synchronized (readers) {
			Integer iid = new Integer(tid);
			if (readers.get(iid) != null) {
				Core.logger.log(this, "Duplicate trailer ID: " + tid + ": old consumer is " + readers.get(iid), new Exception("debug"), Logger.NORMAL);
				return null;
			}
			MuxTrailerReader r = new MuxTrailerReader(cb, tid, ph);
			readers.put(iid, r);
			return r;
		}
	}

	public String dumpReaders() {
	    StringBuffer sb = null;
	    synchronized(readers) {
	        for(Enumeration e = readers.elements(); e.hasMoreElements();) {
	            if(sb == null)
	                sb = new StringBuffer();
	            sb.append(((MuxTrailerReader)e.nextElement()).toString());
	            sb.append("\n");
	        }
	    }
	    if(sb == null) return null;
	    return sb.toString();
	}
	
	/**
	 * @param reader
	 */
	public void remove(MuxTrailerReader reader) {
	    Core.logger.log(this, "Removing: "+reader+" - currently readers.size()="+readers.size(),
	            Logger.DEBUG);
		readers.remove(new Integer(reader.id));
		Core.logger.log(this, "Removed: "+reader+" - readers.size()="+readers.size(),
		        Logger.DEBUG);
	}
}
