package freenet.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.Address;
import freenet.BadAddressException;
import freenet.Connection;
import freenet.Identity;
import freenet.ListeningAddress;

public interface Link {

    /**
     * returns the Connection object
     */
    public Connection getConnection();
    
    /**
     * Returns the links InputStream.
     */
    public InputStream getInputStream();

    /**
     * Returns the links OutputStream.
     */
    public OutputStream getOutputStream();

    /**
     * Close this link.
     */
    public void close() throws IOException;

    /**
     * Set the timeout on reading from this Link.
     */
    public void setTimeout(int timeout) throws IOException;

    /**
     * Returns the current timeout on reading from this Link.
     */
    public int getTimeout() throws IOException;

    /**
     * Returns the address to the me, with the given ListeningAddress.
     */
    public Address getMyAddress(ListeningAddress lstaddr) 
        throws BadAddressException;

    /**
     * Returns the address to me, with the ListeningAddress this Link
     * was received on (when called on outgoing connections behaviour is
     * unspecified).
     */
    public Address getMyAddress();

    /**
     * Returns the address to the peer, with the given ListeningAddress.
     */
    public Address getPeerAddress(ListeningAddress lstaddr) 
        throws BadAddressException;

    /**
     * Returns the address to the peer, with the ListeningAddress this Link
     * was received on (when called on incoming connections behaviour is
     * unspecified).
     */ 
    public Address getPeerAddress();


    /**
     * Returns ones own identity.
     */
    public Identity getMyIdentity();

    /**
     * Returns the identity of the peer.
     */
    public Identity getPeerIdentity();

    /**
     * Returns the manager of this link
     */
    public LinkManager getManager();
    
    /** 
     * Encrypt some bytes in-place for transmission in order
     */
    public void encryptBytes(byte[] data, int offset, int length)
		throws IOException;
    
	/**
	 * Decrypt some bytes read from the connection, in-place.
	 */
	public void decryptBytes(byte[] decryptBuffer, int i, int len)
		throws IOException;
    
    /**
     * Wrap the (raw) InputStream given
     */
    public InputStream makeInputStream(InputStream is);
    
    /**
     * Wrap the (raw) OutputStream given
     */
    public OutputStream makeOutputStream(OutputStream os);
    
    /**
     * @return the number of bytes right at the beginning that
     * will not result in output plaintext bytes using our 
     * various InputStreams
     */
    public int headerBytes();

    /**
     * Returns true if the Link is currently ferrying outbound data
     */
    //public boolean sending();

}


