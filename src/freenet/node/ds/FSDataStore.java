package freenet.node.ds;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UTFDataFormatException;
import java.util.Enumeration;
import java.util.Stack;

import freenet.Core;
import freenet.FieldSet;
import freenet.Key;
import freenet.fs.dir.Buffer;
import freenet.fs.dir.FileNumber;
import freenet.fs.dir.LossyDirectory;
import freenet.fs.dir.RangeFilePattern;
import freenet.support.EnumerationWalk;
import freenet.support.KeyHistogram;
import freenet.support.KeySizeHistogram;
import freenet.support.Logger;
import freenet.support.Walk;
import freenet.support.io.WriteOutputStream;

/**
 * A DataStore implementation that uses a freenet.fs.LossyDirectory
 * to store the keys.  It automatically deletes least-recently-accessed
 * keys when it fills up.
 * 
 * @see freenet.fs.dir.LossyDirectory
 * @author tavin
 */
public class FSDataStore implements DataStore {

	public static void dump(FSDataStore ds, PrintWriter pw) {
		synchronized (ds.dir.semaphore()) {

			pw.println("Free space: " + ds.dir.available());
			pw.println();

		}
	}

	final LossyDirectory dir;

	public final long maxDataSize;

	private static boolean logDebug = true;

	/**
	 * @param dir          the backing storage
	 * @param maxDataSize  largest key length that can be stored
	 *                     in a non-circular buffer
	 */
	public FSDataStore(LossyDirectory dir, long maxDataSize) {
		this.dir = dir;
		this.maxDataSize = maxDataSize;
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
	}

	/**
	 * @return  a KeyOutputStream that transfers the written data
	 *          into the store
	 */
	public KeyOutputStream putData(
		Key k,
		long dataSize,
		FieldSet storables,
		boolean ignoreDS)
		throws IOException, KeyCollisionException {

		// we need to know the byte-length of the Storables in advance
		ByteArrayOutputStream bout = new ByteArrayOutputStream(512);
		storables.writeFields(new WriteOutputStream(bout));
		dataSize += bout.size();

		FileNumber fn = new FileNumber(k.getVal());
		Buffer buffer;

		if (maxDataSize == 0)
			dir.getSpace(dataSize);
		else
			dir.getSpace(Math.min(dataSize, maxDataSize));

		synchronized (dir.semaphore()) {

			if (dir.contains(fn) && !ignoreDS)
				throw new KeyCollisionException();

				// create normal buffer
				buffer = dir.forceStore(dataSize, fn);
			}

		if (logDebug)
			Core.logger.log(
				this,
				"storing key: " + k + ":" + dataSize,
				Logger.DEBUG);

		FSDataStoreElement dse =
			new FSDataStoreElement(this, k, buffer, dataSize);
		KeyOutputStream kout = dse.getKeyOutputStream();
		bout.writeTo(kout);
		kout.flush();

		return kout;
	}

	/**
	 * Retrieves the data for a key.
	 * @return  a KeyInputStream from which the key data can be read,
	 *          or null if not found
	 */
	public KeyInputStream getData(Key k) throws IOException {
		String debugLogLevelLogString=null;
		if(logDebug) //DONT use it unless you are logging at debug level..
			debugLogLevelLogString = " for " + k.toString();
		long startTime = System.currentTimeMillis();
		Buffer buffer;
		FileNumber fn = new FileNumber(k.getVal());
		long touchedTime;

		logDebug=Core.logger.shouldLog(Logger.DEBUG,this);

		buffer = dir.fetch(fn);
		long fetchedTime = System.currentTimeMillis();
		if (logDebug)
			Core.logger.log(
				this,
				"Fetch took " + (fetchedTime - startTime) + debugLogLevelLogString,
				Logger.DEBUG);
		if (buffer == null) {
			if (logDebug)
				Core.logger.log(this, "key not found: " + k, Logger.DEBUG);
			return null;
		}
		buffer.touchThrottled(); // register access for LRU tracking
		touchedTime = System.currentTimeMillis();
		if (logDebug)
			Core.logger.log(
				this,
				"touch() took "
					+ (touchedTime - fetchedTime)
					+ debugLogLevelLogString
					+ "; key found: "
					+ k,
				Logger.DEBUG);

		KeyInputStream is = null;
		try {
			// new FSDataStoreElement can take some time due to doing I/O
			FSDataStoreElement dse =
				new FSDataStoreElement(this, k, buffer, buffer.length());
			long gotElementTime = System.currentTimeMillis();
			if (logDebug)
				Core.logger.log(
					this,
					"new FSDataStoreElement took "
						+ (gotElementTime - touchedTime)
						+ debugLogLevelLogString,
					Logger.DEBUG);
			is = dse.getKeyInputStream();
			long gotInputStreamTime = System.currentTimeMillis();
			if (logDebug)
				Core.logger.log(
					this,
					"getKeyInputStream took "
						+ (gotInputStreamTime - gotElementTime)
						+ debugLogLevelLogString,
					Logger.DEBUG);
			return is;
		} catch (UTFDataFormatException e) {
			// Corrupted file, remove it
			Core.logger.log(
				this,
				"Corrupted fields in file in datastore, deleting",
				e,
				Logger.NORMAL);
			buffer.release();
			dir.delete(fn, false);
			is = null;
			return null;
		} finally {
			long finishedTime = System.currentTimeMillis();
			if (Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(
					this,
					"Finishing "
						+ ((is == null) ? "(failed)" : "")
						+ "took "
						+ (finishedTime - touchedTime)
						+ " millis"
						+ debugLogLevelLogString,
					Logger.DEBUG);
		}
	}

	/**
	 * Deletes a Key from the store.
	 * @return true, if a Key was removed
	 */
	public final boolean remove(Key k, boolean keepIfUsed) {
		return dir.delete(new FileNumber(k.getVal()), keepIfUsed);
	}

	public final boolean demote(Key k) {
		return dir.demote(new FileNumber(k.getVal()));
	}

	/**
	 * @return true, if the Key is contained in the store
	 */
	public final boolean contains(Key k) {
		return dir.contains(new FileNumber(k.getVal()));
	}

	private final Stack keyStack = new Stack();

	/**
	 * @param k          the key to measure closeness to
	 * @param inclusive  whether to include k, if found
	 * @param limit      max size of returned array
	 * 
	 * @return  an array of Keys arranged in order of closeness
	 */
	public Key[] findClosestKeys(Key k, boolean inclusive, int limit) {
		synchronized (keyStack) {
			try {
				synchronized (dir.semaphore()) {

					FileNumber fnk = new FileNumber(k.getVal());
					Enumeration e1 =
						dir.keys(new RangeFilePattern(fnk, false, false));
					Enumeration e2 =
						dir.keys(new RangeFilePattern(fnk, inclusive, true));

					Walk w1 = new KeyWalk(new EnumerationWalk(e1));
					Walk w2 = new KeyWalk(new EnumerationWalk(e2));

					Key k1 = (Key) w1.getNext();
					Key k2 = (Key) w2.getNext();

					// push onto stack in order of closeness
					while (limit-- > 0) {
						if (k1 == null && k2 == null) {
							break;
						} else if (
							k1 == null
								|| (k2 != null && k.compareTo(k1, k2) > 0)) {
							// use k2 instead of k1
							keyStack.push(k2);
							k2 = (Key) w2.getNext();
						} else {
							// use k1 instead of k2
							keyStack.push(k1);
							k1 = (Key) w1.getNext();
						}
					}

					Key[] ret = new Key[keyStack.size()];
					keyStack.copyInto(ret);

					if (logDebug)
						Core.logger.log(
							this,
							"returning "
								+ ret.length
								+ " closest keys to: "
								+ k,
							Logger.DEBUG);

					return ret;
				}
			} finally {
				keyStack.removeAllElements();
			}
		}
	}

	// It would be less ugly to just expose the key enumeration,
	// but I didn't want to add access to it to the public
	// interfaces.
	public KeyHistogram getHistogram() {
		KeyHistogram histogram = dir.getHistogram();
		if (histogram != null)
			return histogram;
		else {
			histogram = new KeyHistogram();

			if (logDebug)
				Core.logger.log(this, "getHistogram()", Logger.DEBUG);
			Enumeration keys;
			synchronized (dir.semaphore()) {
				keys = dir.keys(true);
			}
			while (keys.hasMoreElements()) {
				FileNumber fn = (FileNumber) keys.nextElement();
				if (logDebug)
					Core.logger.log(
						fn,
						"adding filenumber " + fn + " to histogram",
						Logger.DEBUG);
				Buffer buffer = dir.fetch(fn);
				if (logDebug)
					Core.logger.log(
						buffer,
						"got buffer for filenumber",
						Logger.DEBUG);
				try {
					histogram.add(fn.getByteArray());
				} finally {
					buffer.release();
				}
			}
			if (logDebug)
				Core.logger.log(this, "getHistogram() returning", Logger.DEBUG);

			//         FileNumber fn = NativeFSDirectory.randomKey;
			//         Core.logger.log(this, "Random key: "+fn, Logger.DEBUG);
			//         long time = System.currentTimeMillis();
			//         Key[] k = findClosestKeys(new Key(fn.getByteArray()), true, 20);
			//         long end = System.currentTimeMillis();
			//         Core.logger.log(this, "Took "+(end-time)+" ms", Logger.DEBUG);
			//         for(int x=0;x<k.length;x++) {
			//         Core.logger.log(this, "Key "+x+": "+k[x], Logger.DEBUG);
			//         }
			return histogram;
		}
	}

	public KeySizeHistogram getSizeHistogram() {
		KeySizeHistogram histogram = dir.getSizeHistogram();
		if (histogram != null)
			return histogram;
		else {
			histogram = new KeySizeHistogram();
			Enumeration keys;
			synchronized (dir.semaphore()) {
				keys = dir.keys(true);
			}
			while (keys.hasMoreElements()) {
				FileNumber fn = (FileNumber) keys.nextElement();
				Buffer buffer = dir.fetch(fn);
				try {
					histogram.add(buffer.length());
				} finally {
					buffer.release();
				}
			}

			return histogram;
		}
	}

	private static final class KeyWalk implements Walk {
		private final Walk fnw;
		KeyWalk(Walk fnw) {
			this.fnw = fnw;
		}
		public final Object getNext() {
			FileNumber fn = (FileNumber) fnw.getNext();
			return fn == null ? null : new Key(fn.getByteArray());
		}
	}
}
