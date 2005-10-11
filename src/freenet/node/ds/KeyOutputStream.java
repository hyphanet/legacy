package freenet.node.ds;

import java.io.OutputStream;
import java.io.IOException;

/**  
 * Exposes an output stream to the application that caches
 * the data for a Freenet key.
 * @author tavin
 */
public abstract class KeyOutputStream extends OutputStream {

    /**
     * Called to commit and index the key into the store.
     */
    public abstract void commit() throws IOException, KeyCollisionException;

    /**
     * Called to remove the key from the live index immediately,
     * with a close() to come later.
     */
    public abstract void rollback();

    /**
     * Read the key data that is being written by this output stream.
     */
    public abstract KeyInputStream getKeyInputStream() throws IOException;

    /**
     * Set the failure code to be propagated to input streams on this data.
     */
    public abstract void fail(int code);

}


