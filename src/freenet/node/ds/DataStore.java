package freenet.node.ds;

import freenet.Key;
import freenet.FieldSet;
import java.io.IOException;

/**
 * The DataStore caches Freenet keys.  Isn't that nice?
 * @author tavin
 */
public interface DataStore {

    /**
     * Allocates space in the cache for the key and returns an OutputStream
     * that can be written to in order to store the key's data in the cache.
     * 
     * The data is available immediately unless the indexNow flag is set false.
     * Streams reading data that is currently being written will block behind
     * the write position of this output stream.
     *
     * If the data length is larger than the cache tolerates, the data
     * is tunneled through a circular buffer which is automatically deleted
     * from the index once the first lap is finished and the writer begins
     * to overwrite it from the beginning.  The writer will block behind
     * the physical positions of reading streams that are on the previous
     * lap.  Once all streams are closed the storage is freed.
     *
     * If the stream must be abandoned before dataSize bytes have been
     * written, abort(int failureCode) may be called, which is the same as
     * close(), except that reading streams will receive the failureCode as
     * a BadDataException.  If close() is called instead of abort(), the
     * reading streams will see a failure code of -1.
     *
     * @param  k          The key to insert the data for
     * @param  dataSize   total length of data (including control bytes)
     * @param  storables  Storable fields for this data
     * @param  ignoreDS     if true, don't check whether the data is in the store.
     * Implementations are expected to buffer the data even though there is already
     * a stored copy, and the commit will fail. 
     */
    KeyOutputStream putData(Key k, long dataSize, FieldSet storables, boolean ignoreDS)
                            throws IOException, KeyCollisionException;

    /**
     * @param k  the key to retrieve
     * @return  an input stream for a cached key,
     *          or null if the key is not in the cache
     *
     * If the data is in the process of being written, there is a possibility
     * that a BadDataException can be thrown on a read.  The BadDataException
     * may be examined for a failure code from the writer.
     */
    KeyInputStream getData(Key k) throws IOException;
    
    /**
     * Removes a key's data from the store.
     * @param k the file to delete
     * @param keepIfUsed if true, keep the file if it is in use, or if it 
     * has been used since the commit
     * @return  true, if a key was removed
     */
    boolean remove(Key k, boolean keepIfUsed);
    
    /** 
     * Demote a key to the end of the LRU
     * @param k the file to demote
     * @return true if a key was moved
     */
    boolean demote(Key k);
    
    /**
     * @return  true, if the given key is cached
     */
    boolean contains(Key k);

    /**
     * @return  a snapshot of the closest keys
     * @param startAt  the key value to search from
     * @param inclusive  whether to include the starting value, if it exists
     * @param limit  the maximum number of Keys to return
     */
    Key[] findClosestKeys(Key startAt, boolean inclusive, int limit);
}


