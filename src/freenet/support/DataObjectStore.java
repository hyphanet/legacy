package freenet.support;

import freenet.fs.dir.FileNumber;
import freenet.fs.dir.FilePattern;
import java.util.Enumeration;
import java.io.IOException;

/**
 * A cache of objects that are retrievable by a key
 * and persist in a serialized form.
 */
public interface DataObjectStore {

    /**
     * Write changes to the backing storage.
     */
    void flush() throws IOException;

    /**
     * @return  the DataObject for the given key,
     *          or null if not found
     * 
     * @throws DataObjectUnloadedException
     *         whenever an instance is found in serial form only
     */
    DataObject get(FileNumber key)
        throws DataObjectUnloadedException;

    /**
     * Although accessing the object through get() and modifying it
     * will cause other callers to see the modified object, it is
     * still necessary to call this method to trigger reserialization
     * of the object in the backing store.
     */
    void set(FileNumber key, DataObject o);

    /**
     * @return  true, if there is a data object stored under the key
     */
    boolean contains(FileNumber key);

    /**
     * Erases an object.
     * @return  true, if something was removed
     */
    boolean remove(FileNumber key);

    /**
     * @return  an enumeration of FileNumber
     */
    Enumeration keys(boolean ascending);

    Enumeration keys(FileNumber start, boolean ascending);

    /**
     * @return  the subset of keys matching the pattern
     */
    Enumeration keys(FilePattern pat);
}



