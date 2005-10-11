package freenet.node.ds;

import freenet.Storables;
import java.io.InputStream;

/**
 * Exposes an input stream from a key in the cache to the application.
 * @author tavin
 */
public abstract class KeyInputStream extends InputStream {

    public abstract long length();
    
    public abstract long realLength();

    public abstract Storables getStorables();


    /**
     * Returns any failure code given when caching the data, or -1
     * is the writing succeeded.
     * @see KeyOutputStream#fail(int)
     */
    public abstract int getFailureCode();


    /**
     * this is temporary debug code for oskar.
     */
    public void setParent(long id, freenet.MessageHandler mh, 
                          String comment) {
    
    }
}



