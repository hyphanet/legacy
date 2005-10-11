package freenet;

/**
 * Class to account for data transfer on a connection. 
 */
public class ConnectionDataTransferAccounter {

	private long sendQueueSize = 0;
	private long totalDataSent = 0;
	private long receiveQueueSize = 0;
	private long totalDataReceived = 0;

	/** The total amount of data sent over this connection so far. */
	synchronized long totalDataSent() {
		return totalDataSent;
	}
	
	synchronized void registerSentData(int len) {
		totalDataSent += len;
	}
	
	synchronized void registerReceivedData(int len) {
		totalDataReceived += len;
	}

	/** The total amount of data recived over this connection so far. */
	synchronized long totalDataReceived() {
		return totalDataReceived;
	}
	
	/**
	 *@return    the number of bytes waiting to be sent on this connection
	 */
	public long sendQueueSize() {
		return sendQueueSize;
	}
	
	/**
	 *@return    the number of bytes waiting to be received on this connection
	 */
	public synchronized long receiveQueueSize() {
		return receiveQueueSize;
	}
   
	/**
	 * Increases the receiveQueue with the specified amount
	 */
	synchronized void incReceiveQueue(long amount) {
		receiveQueueSize += amount;
	}

	/**
	 * Decreases the receiveQueue with the specified amount
	 */
	synchronized void decReceiveQueue(long amount) {
		receiveQueueSize = Math.max(receiveQueueSize - amount, 0);
		totalDataReceived += amount;		
	}
}
