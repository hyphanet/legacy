package freenet;
/**
 * Interface for a client class that receives trailer data.
 * By this point it will have been demuxed, reordered etc as necessary.
 * @author amphibian
 */
public interface TrailerReadCallback {
	/**
	 * Receive some bytes from the trailer.
	 * @param buf
	 * @param offset
	 * @param length
	 */
	void receive(byte[] buf, int offset, int length);
	
	/**
	 * The connection was closed.
	 * @author amphibian
	 */
	void closed();
}
