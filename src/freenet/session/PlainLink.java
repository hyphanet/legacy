package freenet.session;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.Address;
import freenet.Connection;
import freenet.ConnectionHandler;
import freenet.Identity;
import freenet.ListeningAddress;

/**
 * Plainlinks wrap connections and do nothing.
 *
 * @author oskar
 */

public final class PlainLink implements Link {

    private PlainLinkManager lm;
    private Connection conn;

    //profiling
    //WARNING:remove before release
    public static volatile int instances=0;
    private static final Object profLock = new Object();
    
    PlainLink(PlainLinkManager lm, Connection conn) {
        this.lm = lm;
        this.conn = conn;
	synchronized(profLock) {
		instances++;
	}
    }

    public LinkManager getManager() {
        return lm;
    }

    public boolean sending() {
        return true; // FIXME! Scott, what did you want of this?
    }

    public Connection getConnection() {
    	return conn;
    }
    /**
     * PlainLink peers have no known identity.
     * @return   Null.
     */
    public Identity getPeerIdentity() {
        return null;
    }

    public Address getPeerAddress() {
        // FIXME - nulls are bad
        return conn.getPeerAddress();
    }
    

    public Address getPeerAddress(ListeningAddress param1) {
        return conn.getPeerAddress(param1);
    }

    public Address getMyAddress() {
        return conn.getPeerAddress();
    }

    public Address getMyAddress(ListeningAddress param1) {
        return conn.getPeerAddress(param1);
    }

    public Identity getMyIdentity() {
        return null;
    }

    public int getTimeout() throws IOException {
        return conn.getSoTimeout();
    }

    public void setTimeout(int param1) throws IOException {
        conn.setSoTimeout(param1);
    }

    public void close() {
        conn.close();
    }

    public OutputStream getOutputStream() {
        return conn.getOut();
    }

    public InputStream getInputStream() {
        return conn.getIn();
    }
    
    public OutputStream makeOutputStream(OutputStream os) {
	return os;
    }
    
    public InputStream makeInputStream(InputStream is) {
	return is;
    }
    
    public void encryptBytes(byte[] b, int offset, int length) {
	// Do nothing!
    }
    
    /**
     * Does nothing!
     */
    public void registerConnectionHandler(ConnectionHandler param1) {
    }
    
    public int headerBytes() {
	return 0;
    }
    
    //profiling
    //WARNING:remove before release
    protected void finalize() {
    	synchronized(profLock) {
    		instances--;
	}
    }

	public void decryptBytes(byte[] decryptBuffer, int i, int len) {
		// Do nothing. PlainLinks are not encrypted.
	}
}
