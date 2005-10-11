package freenet.fs.dir;

import java.util.Enumeration;

import freenet.support.EnumerationWalk;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.UTF8;
import freenet.support.Walk;
import freenet.support.WalkEnumeration;
import freenet.support.Fields.ByteArrayComparator;

/**
 * This is basically an abstraction of a file name.
 * 
 * @author tavin
 */
public final class FileNumber implements Comparable {

	public static final FileNumber NIL = new FileNumber(new byte[0]);

	final int dirID;
	final byte[] key;
	final int hashCode;
	final long longHashCode;

	/**
	 * Wrap a FileNumber as a FileNumber (with a different dirID)
	 * 
	 * @param dirID
	 *            directory ID
	 * @param fn
	 *            byte array
	 */
	FileNumber(int dirID, FileNumber fn) {
		this.dirID = dirID;
		this.key = fn.key;
		longHashCode = fn.longHashCode;
		hashCode = fn.hashCode;
	}

	/**
	 * Wrap a byte array as a FileNumber key with a dirID
	 * 
	 * @param dirID
	 *            directory ID
	 * @param key
	 *            byte array
	 */
	FileNumber(int dirID, byte[] key) {
		this.dirID = dirID;
		this.key = key;
		longHashCode = Fields.longHashCode(key);
		hashCode = (int) ((longHashCode >>> 32) ^ longHashCode);
	}

	/**
	 * Create an empty FileNumber with a dirID
	 * 
	 * @param dirID
	 *            directory ID
	 */
	FileNumber(int dirID) {
		this(dirID, NIL);
	}

	/**
	 * Wrap a byte array as a FileNumber key.
	 * 
	 * @param key
	 *            byte array
	 */
	public FileNumber(byte[] key) {
		this(0, key);
	}

	/**
	 * Wrap a string as a FileNumber key.
	 * 
	 * @param key
	 *            the string
	 */
	public FileNumber(String key) {
		this(0, UTF8.encode(key));
	}

	/**
	 * @return String representation for log messages, etc.
	 */
	public final String toString() {
		StringBuffer sb = new StringBuffer(2 + 8 + 3 + key.length * 2);
		sb.append("0x").append(Integer.toHexString(dirID)).append(" : ");
		HexUtil.bytesToHexAppend(key, 0, key.length, sb);
		return sb.toString();
	}

	/**
	 * @return this key as a string
	 */
	public final String getString() {
		return new String(key);
	}

	/**
	 * @return this key as a byte array
	 */
	public final byte[] getByteArray() {
		return key;
	}

	/**
	 * compare to an Object throws if the Object isn't a FileNumber
	 */
	public final int compareTo(Object o) {
		return compareTo((FileNumber) o);
	}

	/**
	 * compare to another FileNumber we compare first by dirID, then by key
	 */
	public final int compareTo(FileNumber fn) {
		return dirID == fn.dirID
			? ByteArrayComparator.compare(key, fn.key)
			: (dirID > fn.dirID ? 1 : -1);
	}

	/**
	 * equality to an Object
	 */
	public final boolean equals(Object o) {
		return o instanceof FileNumber && equals((FileNumber) o);
	}

	/**
	 * equality to a FileNumber
	 */
	public final boolean equals(FileNumber fn) {
		return dirID == fn.dirID && Fields.byteArrayEqual(key, fn.key);
	}

	public final long longHashCode() {
		return longHashCode;
	}

	public final int hashCode() {
		return hashCode;
	}

	/**
	 * get directory ID
	 */
	public final int getDirID() {
		return dirID;
	}

	/*
	 * use a FilePattern to filter an Enumeration @param pat pattern to filter
	 * with @param enu enumeration of FileNumbers to filter with
	 */
	public static final Enumeration filter(FilePattern pat, Enumeration enu) {
		return new WalkEnumeration(filter(pat, new EnumerationWalk(enu)));
	}

	/*
	 * use a FilePattern to filter a Walk @param pat pattern to filter with
	 * @param w walk of FileNumbers to filter
	 */
	// FIXME: consider reducing use of Walk, and filtering Enums directly?
	public static final Walk filter(FilePattern pat, Walk w) {
		return new FilterWalk(pat, w);
	}

	private static final class FilterWalk implements Walk {

		private final FilePattern pat;
		private final Walk walk;

		private boolean done = false;

		FilterWalk(FilePattern pat, Walk walk) {
			this.pat = pat;
			this.walk = walk;
		}

		public final Object getNext() {
			if (!done) {
				FileNumber fn;
				while (null != (fn = (FileNumber) walk.getNext())) {
					if (pat.matches(fn))
						return fn;
					else if (pat.isLimitedBy(fn))
						break;
				}
				done = true;
			}
			return null;
		}
	}
}
