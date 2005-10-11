package freenet.fs.dir;

import java.util.Enumeration;

import freenet.support.KeyHistogram;
import freenet.support.KeySizeHistogram;

/**
 * This is an application-level interface to the file-system.
 * 
 * @author tavin
 */
public interface Directory {

	/**
	 * @return an Object to synchronize on when doing multiple directory
	 *         operations
	 */
	Object semaphore();

	/**
	 * @return the number of bytes of space available without deleting any
	 *         entries in the directory.  May be negative.
	 */
	long available();

	/**
	 * @return the number of bytes of disk space used by the directory,
	 *         including file system overhead if possible
	 */
	long used();

	/**
	 * @param ascending
	 *            true for ascending order
	 * 
	 * @return an Enumeration of FileNumber keys in order
	 */
	Enumeration keys(boolean ascending);

	/**
	 * @param pat a FilePattern indicating a range of FileNumbers
	 * to return
	 * @return an Enumeration of FileNumber keys in order
	 */
	Enumeration keys(FilePattern pat);
	//Enumeration keys(FileNumber fn, boolean inclusive, boolean ascending);

	/**
	 * @param ascending
	 *            true for ascending order (oldest first)
	 * @return an Enumeration of FileNumber keys from oldest to newest, or vice
	 *         versa
	 */
	Enumeration lruKeys(boolean ascending);

	/**
	 * Deletes a file.
	 * 
	 * @param fn
	 *            the file to delete
	 * @param keepIfUsed
	 *            if true, keep the file if it is in use, or if it has been
	 *            used since the commit
	 * @return true, if a file was deleted
	 */
	boolean delete(FileNumber fn, boolean keepIfUsed);

	/**
	 * Demotes a file to the end of the LRU list
	 * 
	 * @return whether a file was moved
	 */
	boolean demote(FileNumber fn);

	/**
	 * @return true, if the file exists, and is committed
	 */
	boolean contains(FileNumber fn);

	/**
	 * Accesses a file.
	 * 
	 * @return a Buffer for that file or null if not found
	 */
	Buffer fetch(FileNumber fn);

	/**
	 * Creates a new file.
	 * 
	 * @param size
	 *            the size of the file
	 * @param fn
	 *            the key
	 * @return the created Buffer, or null if there was insufficient storage
	 *         space
	 */
	Buffer store(long size, FileNumber fn);

	/**
	 * Get a key histogram, if available, else return null
	 */
	KeyHistogram getHistogram();

	/**
	 * Get a key size histogram, if available, else return null
	 */
	KeySizeHistogram getSizeHistogram();

	/**
	 * Count the number of keys in the Directory
	 */
	long countKeys();

}
