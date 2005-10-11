package freenet;

import java.io.IOException;

public interface TrailerWriter {
    /**
     * Write a block of a trailing field
     * @param block the byte[] containing the actual data. Will be encrypted in-place.
     * @param offset the offset within block that the data starts at
     * @param length the length of the data to write
     * @param cb the write callback to call when the write is completed
     *
     * @throws UnknownTrailerSendIDException if the ID is not currently sending
     * @throws TrailerSendFinishedException if the trailer has already finished sending
     * @throws IOException if the send fails immediately for some reason
     * @throws IllegalArgumentException if the length is 0 or less
     */
    public void writeTrailing(byte[] block, int offset, int length,
			      TrailerWriteCallback cb)
	throws UnknownTrailerSendIDException, TrailerSendFinishedException, AlreadySendingTrailerChunkException, IOException;
    
    /**
     * @return true if the transfer is now closed
     */
    public boolean isClosed();
    
    /**
     * @return true if we timed out waiting for a writeTrailing().
     */
    public boolean wasClientTimeout();
    
    /**
     * @return true if the reason for the write failure is known to have been
     * because the reader on the other end didn't want the data.
     */
    public boolean wasTerminated();
    
    public void close();

    /**
     * @return whether this is an external write, this affects diagnostics.
     */
    public boolean isExternal();
}
