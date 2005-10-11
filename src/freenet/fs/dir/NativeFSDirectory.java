package freenet.fs.dir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.EmptyStackException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Stack;
import java.util.StringTokenizer;
import java.util.Vector;

import freenet.Core;
import freenet.Key;
import freenet.Storables;
import freenet.config.Config;
import freenet.config.Params;
import freenet.support.Checkpointed;
import freenet.support.FakeRandomAccessFilePool;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.IteratorEnumeration;
import freenet.support.KeyHistogram;
import freenet.support.KeySizeHistogram;
import freenet.support.Logger;
import freenet.support.PooledRandomAccessFile;
import freenet.support.RandomAccessFilePool;
import freenet.support.RealRandomAccessFilePool;
import freenet.support.ReversibleSortedMap;
import freenet.support.SkiplistWithHashMap;
import freenet.support.UTF8;
import freenet.support.Walk;
import freenet.support.WalkEnumeration;
import freenet.support.io.CountedInputStream;
import freenet.support.io.NullOutputStream;
import freenet.support.io.ReadInputStream;

/**
 * A Directory implementation that drops everything through to a directory in
 * the native FS.
 * 
 * @author amphibian (mostly)
 * @author gthyni (early work)
 * @author tavin (skeleton)
 */
public class NativeFSDirectory implements Directory, Checkpointed {

	private static final long defaultCheckpointSleepTime = 15000;
	private static final long minCheckpointSleepTime = 5000;
	private long checkpointSleepTime = 15000;
	private long checkpointsLengthTotal = 0;
	private long checkpointsTotal = 0;
	private Object checkpointTimesSync = new Object();
	private SkiplistWithHashMap buffers;
	private int blockSize;
	private long spaceUsed = 0;
	private long tempSpaceUsed = 0;
	private long maxTempSpaceUsed;
	private final Object spaceUsedSync = new Object();
	public final RandomAccessFilePool rafpool;

	private NativeBuffer leastRecentlyUsed;
	private NativeBuffer mostRecentlyUsed;
	private final Object lruSync = new Object();
	
	/**
	 * Set to true for a thorough test of the lastmodified LRU It loads the
	 * store in the usual way except that NWalk doesn't sort them by
	 * lastModified, so they get inserted in disk order
	 */
	public boolean paranoidListCheck = false;

	private static final byte ALWAYS = 0;
	private static final byte SOMETIMES = 1;
	private static final byte NEVER = 2;
	private volatile int verification_requests_counter =0;

	private byte verifyMode = SOMETIMES;

	public boolean logDEBUG; // FIXME EVIL HACK

	private class LRUWalk implements Walk {

		private NativeBuffer current;
		private NativeBuffer next;
		private long lastTime;
		private boolean ascending;

		LRUWalk(boolean ascending) {
			// ascending = true means ascending time in millis
			// i.e. oldest first, youngest last
			this.ascending = ascending;
			synchronized (lruSync) {
				verifyList();
				current = ascending ? leastRecentlyUsed : mostRecentlyUsed;
				if (current == null) {
					next = null;
					lastTime = 0;
				} else {
					next = ascending ? current.nextLRU : current.prevLRU;
					lastTime = current.lastModified;
				}
			}
		}

		public Object getNext() {
			NativeBuffer b = current;
			synchronized (lruSync) {
				// Current may have jumped, or next may have jumped
				// This is not guaranteed to work right, but it's a best guess
				NativeBuffer altNext;
				if (current == null)
					altNext = null;
				else
					altNext = ascending ? current.nextLRU : current.prevLRU;
				long limit = ascending ? Long.MAX_VALUE : Long.MIN_VALUE;
				long llimit =
					ascending ? Long.MAX_VALUE - 1 : Long.MIN_VALUE + 1;
				long x = (altNext == null) ? limit : altNext.lastModified;
				long y = (next == null) ? llimit : next.lastModified;
				// We want the next object with a higher lastModified
				// We want the lower of altNext and next
				// If they are even different
				if (ascending ? x < lastTime : x > lastTime)
					x = limit; // lose if behind
				if (ascending ? y < lastTime : y > lastTime)
					y = llimit; // lose if behind
				if (ascending ? x < y : x > y) {
					current = altNext;
					lastTime = x;
				} else {
					current = next;
					lastTime = y;
				}
				if (current != null)
					next = ascending ? current.nextLRU : current.prevLRU;
			}
			if (b == null) {
				Core.logger.log(this, "Buffer is null", Logger.DEBUG);
				return null;
			}
			return b.fn;
		}
	}

	public class NWalk implements Walk {
		public final class FileItem {

			private final long modified;
			private final String prefix;
			private final String name;
			private final String sPath;
			private int bucket = -1;
			private long length = -1;

			public String toString() {
				return sPath
					+ ":"
					+ ((prefix == null) ? "(no prefix)" : (" prefix=" + prefix))
					+ ", name="
					+ name
					+ ", bucket="
					+ bucket
					+ ", modified="
					+ modified
					+ ", length="
					+ length;
			}

			FileItem(String n, long m, long length) {
				this(n, m);
				this.length = length;
			}

			FileItem(String n, long m) {
				modified = m;
				name = n;
				int x = n.lastIndexOf(File.separator);
				if (x > 0) {
					String bucket = n.substring(0, x);
					n = n.substring(x + 1);
					x = bucket.lastIndexOf(File.separator);
					if (x > 0)
						try {
							long h = Fields.hexToLong(bucket.substring(x + 1));
							if ((h >= 0) && (h < Integer.MAX_VALUE))
								this.bucket = (int) h;
						} catch (NumberFormatException e) {
						}
				}
				x = n.indexOf('-');
				if (x < 0) {
					prefix = null;
					sPath = n;
				} else {
					if (x == 0)
						prefix = "";
					else
						prefix = n.substring(0, x);
					if (x == n.length() - 1)
						sPath = "";
					else
						sPath = n.substring(x + 1);
				}
			}

			long lastModified() {
				return modified;
			}

			String prefix() {
				return prefix;
			}

			int bucket() {
				return bucket;
			}

			String getPath() {
				return name;
			}

			String getSPath() {
				return sPath;
			}

			boolean delete() {
				return (new File(name)).delete();
			}

			long length() {
				return length;
			}

			FileNumber makeFileNumber() throws NumberFormatException {
				if (prefix == null
					|| prefix.length() == 0
					|| sPath.length() == 0) {
					return null;
				}
				return new FileNumber(
					Fields.hexToInt(prefix),
					HexUtil.hexToBytes(sPath));
			}
		}

		private class NWComp implements java.util.Comparator {
			public int compare(Object o1, Object o2) {
				int x = innerCompare((FileItem) o1, (FileItem) o2);
				if (!ascending)
					x = -x;
				return x;
			}

			public int innerCompare(FileItem o1, FileItem o2) {
				if (byDate) {
					long mTime1 = o1.lastModified();
					long mTime2 = o2.lastModified();
					if (mTime1 > mTime2)
						return 1;
					if (mTime1 < mTime2)
						return -1;
					String s1 = o1.getSPath();
					String s2 = o2.getSPath();
					return s1.compareTo(s2);
				} else {
					String s1 = o1.getSPath();
					String s2 = o2.getSPath();
					int x = s1.compareTo(s2);
					if (x != 0)
						return x;
					long mTime1 = o1.lastModified();
					long mTime2 = o2.lastModified();
					if (mTime1 > mTime2)
						return 1;
					if (mTime1 < mTime2)
						return -1;
					return 0;
				}
			}
		}

		private boolean ascending;
		private boolean byDate;
		private File dir;
		private FileItem[] files;
		private int count = 0;

		private final class ReadDir extends Thread {

			private final Stack s; // Stack is synchronized
			private final Vector v;
			private final long time;

			ReadDir(Stack s, Vector v, long time) {
				super("Datastorage reader");
				this.s = s;
				this.v = v;
				this.time = time;
				super.start();
			}

			public void run() {
				while (!s.isEmpty())
					try {
						File d = (File) s.pop();
						File[] f = d.listFiles();
						if (f != null && f.length > 0)
							try {
								for (int y = 0;; y++)
									if (f[y].isFile())
										v.addElement(
											new FileItem(
												f[y].toString(),
												f[y].lastModified(),
												f[y].length()));
							} catch (ArrayIndexOutOfBoundsException e) {
							}
						if (logDEBUG)
							Core.logger.log(
								this,
								"Read dir "
									+ d.getName()
									+ " ("
									+ (f == null ? 0 : f.length)
									+ "/>"
									+ v.size()
									+ ") at "
									+ (System.currentTimeMillis() - time)
									+ "ms",
								Logger.DEBUG);
					} catch (EmptyStackException e) {
						break;
					}
			}
		}

		int atFile() {
			return count;
		}

		int totalFiles() {
			if (files == null)
				return 0;
			return files.length;
		}

		public NWalk(File dir, boolean ascending, boolean byDate)
			throws IOException {
			this(dir, ascending, byDate, false);
		}

		public NWalk(
			File dir,
			boolean ascending,
			boolean byDate,
			boolean screwTheOrdering)
			throws IOException {
			this.dir = dir;
			if (logDEBUG)
				Core.logger.log(this, "Initializing NWalk", Logger.DEBUG);
			Vector v = new Vector();
			long time = System.currentTimeMillis();
			if (!loaded) {
				load(v, time);
			} else {
				synchronized (buffers) {
					int l = buffers.size();
					if (logDEBUG)
						Core.logger.log(
							this,
							"Listing from buffers: " + l,
							Logger.DEBUG);
					Iterator e = buffers.values().iterator();
					v.ensureCapacity(l);
					for (int x = 0; x < l; x++) {
						NativeBuffer b = (NativeBuffer) e.next();
						if (b == null)
							throw new DirectoryException("null returned from buffer enum");
						if (!b.failed())
							v.addElement(
								new FileItem(b.fileName(), b.lastModified()));
						else
							throw new DirectoryException(
								"FAILED buffer "
									+ b.fn
									+ " still in hashtable");
					}
				}
			}

			// dump to files[]
			files = new FileItem[v.size()];
			v.toArray(files);

			this.ascending = ascending;
			this.byDate = byDate;
			if (files != null) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Listed " + files.length + " files",
						Logger.DEBUG);
				if (!screwTheOrdering)
					java.util.Arrays.sort(files, new NWComp());
				if (logDEBUG)
					Core.logger.log(
						this,
						"Listing store: found " + files.length + " files",
						Logger.DEBUG);
			} else {
				if (logDEBUG)
					Core.logger.log(this, "Empty dir", Logger.DEBUG);
			}
		}

		private void load(Vector v, long time) throws IOException {
			boolean readFromIndex = false;
			File index = new File(dir, "index.old");
			if (!index.isFile() || !index.canRead())
				index = new File(dir, "index");
			if (index.isFile() && index.canRead()) {
				if (!doIndex) {
					index.delete();
				} else {

					InputStream s = null;
					try {
						s = new FileInputStream(index);
					} catch (IOException e) {
						Core.logger.log(
							this,
							"Cannot read index file",
							e,
							Logger.ERROR);
						readFromIndex = false;
					}

					if (s != null) {
						// Outside the catch for "new FileInputStream()" so the
						// exception gets propegated up instead of caught.
						readFromIndex = readFromIndex(v, s);

						try {
							s.close();
						} catch (IOException ioe) {
							Core.logger.log(
								this,
								"Cannot close file " + index,
								Logger.ERROR);
							readFromIndex = false;
						}
					}
				}
			}
			if (!readFromIndex) {
				if (logDEBUG)
					Core.logger.log(this, "Reading dirs", Logger.DEBUG);
				v.clear();
				Stack s = new Stack();
				s.ensureCapacity(256 + 1);
				s.push(dir);
				File mydir = null;
				for (int x = 0; x < 256; x++) {
					mydir = new File(dir, Integer.toHexString(x));
					if (mydir.isFile())
						mydir.delete();
					if (mydir.exists()) {
						s.push(mydir);
					} else if (!mydir.mkdirs())
						throw new IOException(
							"couldn't create directory " + mydir);
				}
				v.ensureCapacity(mydir.list().length * 300);

				// Number of threads to use for reading in the ds if no index.
				// Arbitrary limit. Too low and we squander CPU time, too
				// high and the file descriptors might be flushed from cache
				// before we're finished with them.
				int readThreads = Math.min(16, s.size());

				if (logDEBUG)
					Core.logger.log(
						this,
						"Begin reading directories",
						Logger.DEBUG);
				Thread[] threads = new Thread[readThreads];
				for (int x = 0; x < readThreads; x++)
					threads[x] = new ReadDir(s, v, time);
				for (int x = 0; x < readThreads; x++)
					try {
						threads[x].join();
					} catch (Throwable e) {
					}
				Core.logger.log(
					this,
					"Finished reading directories",
					Logger.DEBUG);
			}

			File[] f = new File(dir, "temp").listFiles();
			if (logDEBUG)
				Core.logger.log(
					this,
					"Attempting to recover "
						+ f.length
						+ " files from temp directory",
					Logger.DEBUG);
			for (int x = 0; x < f.length; x++) {

				// Find parts before and after the last "-".
				StringTokenizer stok =
					new StringTokenizer(f[x].toString(), "-");
				String p1 = null;
				String p2 = null;
				while (stok.hasMoreTokens()) {
					p1 = p2;
					p2 = stok.nextToken();
				}

				if(p1 == null || p2 == null) {
					f[x].delete();
				}
				File dest =
					getFile(
						new FileNumber(
							Fields.hexToInt(p1),
							HexUtil.hexToBytes(p2)));
				if (dest.exists())
					f[x].delete();
				// dest is the same file already in the vector and the
				// correct location so just delete the temp file
				else
					try {
						renameTo(f[x], dest);
						v.addElement(
							new FileItem(
								dest.getPath(),
								dest.lastModified(),
								dest.length()));
					} catch (IOException ioe) {
						Core.logger.log(
							this,
							"Cannot recover temp file "
								+ f[x]
								+ " to "
								+ dest
								+ ": "
								+ ioe,
							Logger.ERROR);
					}
			}
		}

		private boolean readFromIndex(Vector v, InputStream s) {
			s = new BufferedInputStream(s, 1 << 16);
			DataInputStream dis = new DataInputStream(s);
			int x = 0, length = 0;
			try {
				long version = dis.readLong();
				if (version == 1) {
					length = dis.readInt();
					if (length == 0)
						return false;
					v.ensureCapacity(length);
					if (logDEBUG)
						Core.logger.log(
							this,
							"Reading " + length + " items from index",
							Logger.DEBUG);
					// 2 billion ought to be enough for anyone :))
					// besides, java doesn't support more :(
					int oc = 9 * length / 100;
					long nextTime = System.currentTimeMillis() + 5 * 1000;
					for (x = 0; x < length; x++) {
						if (logDEBUG) {
							if (x > oc
								|| System.currentTimeMillis() > nextTime) {
								int c = x * 100 / length;
								if (logDEBUG)
									Core.logger.log(
										this,
										"Read " + c + "%",
										Logger.DEBUG);
								nextTime += 5 * 1000;
								oc += 9 * length / 100;
							}
						}
						long len = dis.readLong();
						FileItem i;
						if (len != -1) {
							long m = dis.readLong();

							// Make sure that 'name' is detached from the
							// underlying dis-buffer.
							String name = new String(dis.readUTF());

							i = new FileItem(name, m, len);
							File grrr = new File(name);
							if (grrr.length() == len) {
								v.addElement(i);
							} else {
								if (!grrr.exists()) {
									File f = grrr.getParentFile();
									if (f != null) {
										f = f.getParentFile();
										if (f != null && !f.isDirectory())
											return false;
									}
								}
								// We must check here, or we risk
								// overestimating the space used by the store
								if (logDEBUG)
									Core.logger.log(
										this,
										"Deleted file "
											+ name
											+ " due to being too short on startup",
										Logger.DEBUG);
								grrr.delete();
							}
						} // else skip
					}
				} else
					return false;
			} catch (EOFException e) {
				Core.logger.log(
					this,
					"Truncated index file ("
						+ x
						+ "/"
						+ length
						+ "); rebuilding",
					e,
					Logger.NORMAL);
				return false;
			} catch (IOException e) {
				Core.logger.log(
					this,
					"I/O Error reading index file; trying to rebuild",
					e,
					Logger.ERROR);
				return false;
			}
			return true;
		}

		public NWalk(FileNumber fn, File dir, boolean ascending)
			throws IOException {
			this(dir, ascending, false);
			Object o = getNext();
			if (o == null)
				return;
			FileNumber f = (FileNumber) o;
			while ((ascending ? f.compareTo(fn) : fn.compareTo(f)) < 0) {
				o = getNext();
				if (o == null)
					return;
				f = (FileNumber) o;
			}
			count--;
			if (count < files.length && logDEBUG)
				Core.logger.log(
					this,
					"Starting at " + files[count],
					Logger.DEBUG);
		}

		public Object getNext() {
			return getNext(false);
		}

		public Object getNext(boolean deleteTemp) {
			return getNext(deleteTemp, false);
		}

		public Object getNext(boolean deleteTemp, boolean returnType) {
			//Core.logger.log(this, "Get file " + count, Logger.DEBUG);
			if (files == null)
				return null;
			if (count < files.length) {
				String s;
				FileItem f;
				while (true) {
					if (count >= files.length) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Can't find a valid filename at "
									+ files.length,
								Logger.DEBUG);
						files = null;
						return null;
					}
					f = files[count];
					files[count++] = null;
					if (f.getSPath().length() == 0) {
						// Probably not one of ours :)
						if (logDEBUG)
							Core.logger.log(this, "Empty path", Logger.DEBUG);
						continue;
					}
					s = f.prefix();
					if (s == null) {
						// Definitely not one of ours
						if (logDEBUG)
							Core.logger.log(this, "No prefix", Logger.DEBUG);
						continue;
					}
					if (s.equals("temp")) {
						if (deleteTemp)
							f.delete();
						continue;
					}
					FileNumber fn;
					try {
						fn = f.makeFileNumber();
					} catch (NumberFormatException e) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Can't make FileNumber for " + f,
								e,
								Logger.DEBUG);
						//if(deleteTemp) f.delete();
						continue;
					}
					if (fn == null) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Can't make FileNumber for " + f,
								Logger.DEBUG);
						//if(deleteTemp) f.delete();
						continue;
					}
					if (deleteTemp) {
						if (f.bucket() != (fn.hashCode() & 0xff)) {
							File source = new File(f.getPath());
							File dest = getFile(fn);
							if (dest.exists()) {
								if (!source.delete()) {
									Core.logger.log(
										this,
										"Can't delete file ("
											+ source
											+ ") in datastore which was "
											+ "filed in the wrong dir",
										Logger.ERROR);
								}
								continue;
							}
							try {
								renameTo(source, dest);
							} catch (IOException ioe) {
								Core.logger.log(
									this,
									"Can't rename file ("
										+ source
										+ " which was filed in wrong dir to "
										+ dest
										+ " or possible permissions problem)",
									Logger.ERROR);
								if (!source.delete()) {
									Core.logger.log(
										this,
										"Can't delete misfiled file " + source,
										Logger.ERROR);
								}
								continue;
							}
						}
					}
					//Core.logger.log(this, "Returning " + fn.toString(),
					// Logger.DEBUG);
					if (returnType)
						return f;
					else
						return fn;
				}
			} else {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Exhausted supply of files",
						Logger.DEBUG);
				files = null;
				return null;
			}
		}
	}

	public final File root;
	public final String rootAsString;

	public final long size;
	boolean loaded = false;
	final boolean doIndex;
	boolean indexSpoiled = false;
	// Histograms include in-flight keys
	protected KeyHistogram keyHistogram = null;
	protected KeySizeHistogram keySizeHistogram = null;

	/**
	 * @param root
	 *            the File object corresponding to the native FS directory
	 *            backing the implementation
	 * @param size
	 *            the maximum number of bytes to allow to be stored
	 * @param blockSize
	 *            the number of bytes in a block in the underlying FS
	 */
	public NativeFSDirectory(
		File root,
		long size,
		int blockSize,
		boolean doIndex,
		float maxTempFraction,
		int maxFilesOpen)
		throws IOException {
		this.doIndex = doIndex;
		this.root = root;
		this.rootAsString = root.getPath();
		this.size = size;
		this.blockSize = blockSize;
		if (blockSize > 0)
			spaceUsed += 257 * blockSize;
		if (maxFilesOpen == 0)
			this.rafpool = new FakeRandomAccessFilePool();
		else
			this.rafpool = new RealRandomAccessFilePool(maxFilesOpen);
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this); // FIXME: EVIL
		if (!root.isDirectory() && !root.mkdirs())
			throw new IOException(
				"couldn't create native directory: " + root.getCanonicalPath());
		File temp = new File(root, "temp");
		if (temp.exists()) {
			File[] f = temp.listFiles();
			for (int x = 0; x < f.length; x++)
				if (!verifyFile(f[x]))
					f[x].delete();
		} else if (!temp.mkdirs())
			throw new IOException("couldn't create temp dir!");
		maxTempSpaceUsed =
			(long) (((double) size) * ((double) maxTempFraction));
		NWalk w = new NWalk(root, true, true, paranoidListCheck);
		
		buffers = new SkiplistWithHashMap(2 * w.totalFiles());
		keyHistogram = new KeyHistogram();
		keySizeHistogram = new KeySizeHistogram();
		synchronized (lruSync) {
			Object o = w.getNext(true, true);
			if (o != null) {
				if (paranoidListCheck) {
					int x = 1;
					NWalk.FileItem fi = (NWalk.FileItem) o;
					NativeBuffer b = preload(fi, null);
					b.setLastModifiedAlreadyOut(b.lastModified(), true);
					if (logDEBUG)
						Core.logger.log(this, "Inserted " + x, Logger.DEBUG);
					x++;
					verifyList(true);
					while (o != null) {
						b.setLastModifiedAlreadyOut(b.lastModified(), true);
						verifyList(true);
						o = w.getNext(true);
						fi = (NWalk.FileItem) o;
						if (fi != null)
							b = preload(fi, null);
					}
				} else {
					NativeBuffer prev =
						leastRecentlyUsed = preload((NWalk.FileItem) o, null);
					o = w.getNext(true, true);
					int oc = 9 * w.totalFiles() / 100;
					long nextTime = System.currentTimeMillis() + 60 * 1000;
					while (o != null) {
						if (logDEBUG) {
							if (w.atFile() > oc
								|| System.currentTimeMillis() > nextTime) {
								int c = (w.atFile() * 100) / w.totalFiles();
								if (logDEBUG)
									Core.logger.log(
										this,
										"Preloaded " + c + "%",
										Logger.DEBUG);
								nextTime += 60 * 1000;
								oc += 9 * w.totalFiles() / 100;
							}
						}
						prev = prev.nextLRU = preload((NWalk.FileItem) o, prev);
						o = w.getNext(true, true);
					}
					mostRecentlyUsed = prev;
				}
			}
			verifyList(true);
		}

		loaded = true;
		if (doIndex)
			checkpoint();
		Core.logger.log(
			this,
			"starting with " + rootAsString + " (" + size + ")",
			Logger.MINOR);

		w = null;
	}

	/**
	 * (De-)Allocate space for a temp file write
	 * 
	 * @param curLength
	 *            current length of a file, -1 means not created yet
	 * @param finalLength
	 *            length after proposed operation, -1 means deleted
	 * @return the number of bytes if any that must be freed to make the write
	 *         possible, 0 if OK, -1 if impossible
	 */
	public long clearWrite(long curLength, long finalLength) {
		if (logDEBUG)
			Core.logger.log(
				this,
				"clearWrite(" + curLength + "," + finalLength + ")",
				Logger.DEBUG);
		synchronized (spaceUsedSync) {
			return innerClearWrite(
				spaceForFile(curLength),
				spaceForFile(finalLength));
		}
	}

	/**
	 * (De-)Allocate space for a temp file write Internal version, call while
	 * synchronized(spaceUsedSync), and see params
	 * 
	 * @param curLength
	 *            current used space for file, -1 means not created yet
	 * @param finalLength
	 *            used space for file after proposed operation
	 * @return the number of bytes if any that must be free to make the write
	 *         possible, 0 if OK, -1 if impossible
	 */
	protected long innerClearWrite(long curLength, long finalLength) {
		if (curLength < 0)
			curLength = 0;
		if (finalLength < 0)
			finalLength = 0;
		// We do not account for filesystem per-file overhead - FIXME if we
		// ever do

		long delta = finalLength - curLength;
		if (logDEBUG) {
			Core.logger.log(
				this,
				"innerClearWrite("
					+ curLength
					+ ","
					+ finalLength
					+ "); spaceUsed="
					+ spaceUsed
					+ ", tempSpaceUsed="
					+ tempSpaceUsed,
				(spaceUsed < 0 || tempSpaceUsed < 0)
					? Logger.ERROR
					: Logger.DEBUG);
			Core.logger.log(this, "delta = " + delta, Logger.DEBUG);
		}
		if ((tempSpaceUsed + delta > maxTempSpaceUsed) && delta > 0) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Refusing innerClearWrite("
						+ curLength
						+ ","
						+ finalLength
						+ " because it would push temp space over "
						+ "maximum ("
						+ maxTempSpaceUsed
						+ ")",
					Logger.DEBUG);
			return -1; // Impossible
		}
		if (spaceUsed + delta > size) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"spaceUsed = "
						+ spaceUsed
						+ ", delta = "
						+ delta
						+ ", size = "
						+ size
						+ ", spaceUsed+delta = "
						+ (spaceUsed + delta)
						+ ", returning "
						+ ((spaceUsed + delta) - size),
					Logger.DEBUG);
			return delta;
		}
		if ((spaceUsed + delta) < 0 || (tempSpaceUsed + delta) < 0) {
			Exception e =
				new IllegalStateException("clearWrite would make spaceUsed or tempSpaceUsed NEGATIVE!");
			Core.logger.log(
				this,
				"clearWrite("
					+ curLength
					+ ","
					+ finalLength
					+ ") would make spaceUsed or tempSpaceUsed negative... "
					+ "spaceUsed="
					+ spaceUsed
					+ ", tempSpaceUsed="
					+ tempSpaceUsed,
				e,
				Logger.ERROR);
		}
		spaceUsed += delta;
		if (spaceUsed < 0)
			spaceUsed = 0;
		tempSpaceUsed += delta;
		if (tempSpaceUsed < 0)
			tempSpaceUsed = 0;
		if (logDEBUG)
			Core.logger.log(
				this,
				"clearWrite("
					+ curLength
					+ ","
					+ finalLength
					+ ") made spaceUsed="
					+ spaceUsed
					+ ", tempSpaceUsed="
					+ tempSpaceUsed,
				Logger.DEBUG);
		return 0;
	}

	/**
	 * Deallocate space used by a temp file (after deleting it)
	 * 
	 * @param length
	 *            the length of the temp file that has been deleted
	 */
	public long onDeleteTempFile(long length) {
		synchronized (spaceUsedSync) {
			return innerClearWrite(spaceForFile(length), -1);
		}
	}

	public final KeyHistogram getHistogram() {
		return (KeyHistogram) (keyHistogram.clone());
	}

	public final KeySizeHistogram getSizeHistogram() {
		return (KeySizeHistogram) (keySizeHistogram.clone());
	}

	public long countKeys() {
		return keyHistogram.getTotal();
	}

	public final Object semaphore() {
		if (logDEBUG)
			Core.logger.log(this, "get semaphore", Logger.DEBUG);
		return this;
	}

	public long available() {
		long avail = size - spaceUsed;
		if (logDEBUG)
			Core.logger.log(this, "available = " + avail, Logger.DEBUG);
		return avail;
	}

	public long used() {
		if (logDEBUG)
			Core.logger.log(this, "spaceUsed = " + spaceUsed, Logger.DEBUG);
		return spaceUsed;
	}

	public boolean writeIndex() {

		// If an old index file doesn't exist then rename it.
		File index = new File(root, "index");
		File oldIndex = new File(root, "index.old");
		if (index.exists() && !oldIndex.exists()) {
			try {
				renameTo(index, oldIndex);
			} catch (IOException ioe) {
				Core.logger.log(
					this,
					"Cannot rename "
						+ index.getPath()
						+ " to "
						+ oldIndex.getPath()
						+ ": "
						+ ioe,
					Logger.ERROR);
			}
		}

		OutputStream s;
		if (!indexSpoiled) {
			try {
				s = new FileOutputStream(index.toString(), false);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"Can't write index file!",
					e,
					Logger.ERROR);
				return false;
			}
		} else {
			s = new NullOutputStream();
			index.delete(); // don't care about return value
		}

		s = new BufferedOutputStream(s, 1 << 16);
		DataOutputStream dos = new DataOutputStream(s);
		long version = 1;
		try {
			dos.writeLong(version);
			Hashtable h;
			long start = System.currentTimeMillis();
			synchronized (buffers) {
				h = buffers.cloneHash();
			}
			long end = System.currentTimeMillis();
			int sz = h.size();
			Vector toCommit = new Vector();
			if (logDEBUG)
				Core.logger.log(
					this,
					"Buffers was locked for "
						+ (end - start)
						+ " milliseconds.  "
						+ "Writing "
						+ sz
						+ " elements",
					Logger.DEBUG);
			dos.writeInt(sz);
			Enumeration e = h.elements();
			for (int x = 0; x < sz; x++) {
				Object o = e.nextElement();
				NativeBuffer b = null;
				if (o != null)
					b = (NativeBuffer) o;
				if (b != null) {
					int status = b.status;
					if (status == NativeBuffer.COMMITTED
						|| status == NativeBuffer.ALMOSTCOMMITTED) {
						dos.writeLong(b.size);
						dos.writeLong(b.lastModified);
						UTF8.writeWithLength(dos, b.getFile().getPath());
						if (status == NativeBuffer.ALMOSTCOMMITTED)
							toCommit.add(b);
					}
				} else {
					dos.writeLong(-1);
				}
			}
			dos.close();
			oldIndex.delete();
			if (logDEBUG)
				Core.logger.log(
					this,
					"Written index file, about to commit",
					Logger.DEBUG);
			// Only commit if it was already in ALMOSTCOMMITTED when we wrote
			// the index
			for (int x = 0; x < toCommit.size(); x++) {
				NativeBuffer b = (NativeBuffer) (toCommit.elementAt(x));
				if (b.status == NativeBuffer.ALMOSTCOMMITTED) {
					try {
						b.reallyCommit();
					} catch (DirectoryException ex) {
						try {
							Core.logger.log(
								this,
								"Got DirectoryException trying to "
									+ "commit "
									+ b
									+ " - releasing",
								ex,
								Logger.MINOR);
							b.release();
						} catch (DirectoryException exx) {
							Core.logger.log(
								this,
								"Got DirectoryException releasing "
									+ b
									+ ": "
									+ exx,
								exx,
								Logger.ERROR);
						}
					}
				}
			}
			toCommit.clear();
			if (logDEBUG)
				Core.logger.log(this, "Finished commit", Logger.DEBUG);
		} catch (IOException e) {
			Core.logger.log(
				this,
				"IOException writing index file",
				e,
				Logger.ERROR);
			return false;
		}
		return true;
	}

	/**
	 * Return an enumeration of all [ALMOST]COMMITTED keys in this Dir
	 */
	public Enumeration keys(boolean ascending) {
		if (logDEBUG)
			Core.logger.log(this, "get keys", Logger.DEBUG);
		Iterator i = buffers.keySet(!ascending).iterator();
		return new IteratorEnumeration(i);
	}

	public Enumeration keys(FilePattern pat) {
		if (logDEBUG)
			Core.logger.log(this, "get keys like " + pat, Logger.DEBUG);
		//         Walk w;
		//         try {
		//             w = new NWalk(pat.key(),root,true);
		//         } catch (IOException e) {
		//             throw new DirectoryException("can't happen");
		//         }
		//         return new WalkEnumeration(FileNumber.filter(pat, w));
		ReversibleSortedMap set;
		if (pat.ascending()) {
			set = (ReversibleSortedMap) (buffers.tailMap(pat.key()));
		} else {
			set = (ReversibleSortedMap) (buffers.headMap(pat.key()));
		}
		Iterator i = set.keySet(!pat.ascending()).iterator();
		return new IteratorEnumeration(i);
	}

	public Enumeration lruKeys(boolean ascending) {
		if (logDEBUG)
			Core.logger.log(this, "get LRU keys", Logger.DEBUG);
		Walk w;
		if (loaded) {
			w = new LRUWalk(ascending);
		} else {
			try {
				w = new NWalk(root, ascending, true);
			} catch (IOException e) {
				throw new DirectoryException("can't happen");
			}
		}
		if (logDEBUG)
			Core.logger.log(this, "walk", Logger.DEBUG);
		Enumeration e = new WalkEnumeration(w);
		return e;
	}

	private File getFile(FileNumber fn) {
		return new File(getPath(fn));
	}

	private String getPath(FileNumber fn) {
		// This method has the potential to consume surprising amounts of CPU
		// and memory. Future modification should be done with performance
		// considerations in mind, although this has been reduced by caching
		// the
		// result in the heaviest user (NativeBuffer).

		// Presize the StringBuffer to the exact size.
		byte[] b = fn.getByteArray();
		StringBuffer getFileBuffer =
			new StringBuffer(rootAsString.length() + b.length * 2 + 15);
		getFileBuffer.append(rootAsString).append(File.separator);

		// First byte of the hashCode as hex. Faster than Integer.toHexString()
		// for only two digits.
		int hash = fn.hashCode();
		if ((hash & 0xf0) != 0) {
			getFileBuffer.append(Character.forDigit((hash & 0xf0) >>> 4, 16));
		}
		getFileBuffer
			.append(Character.forDigit((hash & 0xf), 16))
			.append(File.separator)
			.append(Integer.toHexString(fn.getDirID()))
			.append('-');
		HexUtil.bytesToHexAppend(b, 0, b.length, getFileBuffer);

		return getFileBuffer.toString();
	}

	/**
	 * Create a temporary filename for a file. It will contain a random word to
	 * allow multiple temporary files to exist for one FileNumber
	 * 
	 * @param fn
	 *            the FileNumber of the file to be stored
	 * @return a temporary filename
	 */
	public File getTempFile(FileNumber fn) {
		return getTempFile(fn, Core.getRandSource().nextLong());
	}

	public File getTempFile(FileNumber fn, long rand) {
		StringBuffer buf = new StringBuffer(50);
		buf
			.append(rootAsString)
			.append(File.separator)
			.append("temp")
			.append(File.separator)
			.append("temp-")
			.append(Long.toHexString(rand))
			.append('-')
			.append(Long.toHexString(fn.getDirID()))
			.append('-')
			.append(HexUtil.bytesToHex(fn.getByteArray()));
		return new File(buf.toString());
	}

	public boolean delete(FileNumber fn) {
		return delete(fn, false);
	}

	/**
	 * Delete a (committed) file from the datastore. If it is a temp file, just
	 * take it out of the index. If it is still being used, rename it to a temp
	 * file and take it out of the index. Otherwise, delete the file and take
	 * it out of the index.
	 * 
	 * @param fn
	 *            the FileNumber to delete
	 * @param keepIfUsed
	 *            keep it, if we are using it or have used it since it was
	 *            committed
	 * @return whether a file was removed from the index
	 */
	public boolean delete(FileNumber fn, boolean keepIfUsed) {
		// FIXME: if(loaded) etc
		//         synchronized(buffers) {
		return deleteInternal(fn, keepIfUsed);
		//         }
	}

	/**
	 * Delete a file If it is a temp file, just take it out of the index. If it
	 * is still being used, rename it to a temp file and take it out of the
	 * index. Otherwise, delete the file and take it out of the index.
	 * 
	 * @param fn
	 *            FileNumber to delete
	 * @return whether a file was removed from the index
	 */
	protected boolean deleteInternal(FileNumber fn, boolean keepIfUsed) {
		NativeBuffer b;
		synchronized (buffers) {
			b = (NativeBuffer) buffers.get(fn);
		}
		if (logDEBUG)
			Core.logger.log(
				this,
				"Deleting(" + fn + "," + keepIfUsed + ")",
				new DirectoryException("debug"),
				Logger.DEBUG);
		if (b == null) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Couldn't find " + fn + " in deleteSynchronized",
					Logger.DEBUG);
			return false;
		} // Not in buffers at time delete called
		long size = b.size;
		// It's not absolutely necessary to keep the histograms consistent on
		// a millisecond basis
		synchronized (b) {
			if (keepIfUsed
				&& (b.usedSinceCommit || b.totalInsOutsSize() > 0)) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Not deleting "
							+ fn
							+ ": usedSinceCommit="
							+ b.usedSinceCommit
							+ ", totalInsOutsSize="
							+ b.totalInsOutsSize(),
						Logger.DEBUG);
				return false;
			}
			synchronized (buffers) {
				buffers.remove(fn);
				if (logDEBUG)
					Core.logger.log(
						this,
						"Removed from buffers: " + fn,
						Logger.DEBUG);
			}
			// remove from index regardless of whether we can actually delete
			// see e.g. FSDataObjectStore.flush()
			if (!b.committed())
				return false;
			if (logDEBUG)
				Core.logger.log(
					this,
					b.toString() + " is committed",
					Logger.DEBUG);
			if (b.totalInsOutsSize() > 0) {
				try {
					// unix semantics: if still have readers, keep open but
					//  take out of directory
					if (logDEBUG)
						Core.logger.log(
							this,
							"Renaming to tempfile",
							Logger.DEBUG);
					b.renameToTemp();
				} catch (IOException e) {
					if (Core.logger.shouldLog(Logger.ERROR, this))
						Core.logger.log(
							this,
							"IOException reopening file while renaming to temp",
							e,
							Logger.ERROR);
					throw new DirectoryException(
						"IOException renaming file to temp");
				}
			} else {
				Vector v = b.outs;
				if (v != null && v.size() > 0)
					throw new DirectoryException(
						"trying to delete file with open writers");
				v = b.ins;
				if (v != null && v.size() > 0)
					throw new DirectoryException(
						"trying to delete file with open readers");
				b.releaseInternal(false, false);
			}
		}
		histRemove(fn, size);
		return true;
	}

	public boolean demote(FileNumber fn) {
		NativeBuffer b;
		synchronized (buffers) {
			b = (NativeBuffer) buffers.get(fn);
		}
		if (b == null)
			return false;
		if (!b.committed())
			return false;
		b.moveToLRUEnd();
		return true;
	}

	/**
	 * Do we have a (committed) buffer for this FileNumber?
	 * 
	 * @param fn
	 *            the FileNumber to check for
	 * @return whether we have a committed file under that FileNumber
	 */
	public boolean contains(FileNumber fn) {
		if (logDEBUG)
			Core.logger.log(this, "contains: " + fn, Logger.DEBUG);
		Object o = buffers.get(fn);
		return (o == null) ? false : ((NativeBuffer) o).committed();
		// FIXME: loaded etc
	}

	/**
	 * Return a temporary Buffer to fetch a given FileNumber
	 * 
	 * @param fn
	 *            the FileNumber we want to fetch
	 * @return a temporary Buffer (ExternalNativeBuffer)
	 */
	public Buffer fetch(FileNumber fn) {
		if (logDEBUG)
			Core.logger.log(this, "fetch: " + fn, Logger.DEBUG);
		NativeBuffer b = internalFetch(fn);
		if (b == null)
			return null;
		ExternalNativeBuffer x = new ExternalNativeBuffer(b);
		return x;
	}

	protected NativeBuffer internalFetch(FileNumber fn) {
		return (NativeBuffer) (buffers.get(fn));
	}

	protected NativeBuffer preload(NWalk.FileItem i, NativeBuffer prev) {
		return preload(i.makeFileNumber(), prev, i.length(), i.modified);
	}

	protected NativeBuffer preload(FileNumber fn, NativeBuffer prev) {
		return preload(fn, prev, -1, -1);
	}

	/**
	 * Preload an internal buffer (NativeBuffer) for the given FileNumber
	 * 
	 * @param fn
	 *            the FileNumber to preload. Must exist.
	 */
	protected NativeBuffer preload(
		FileNumber fn,
		NativeBuffer prev,
		long length,
		long modified) {
		//if(!buffer.containsKey(fn)) throw new DirectoryException();
		// called internally, fn MUST exist
		NativeBuffer x;
		try {
			x = new NativeBuffer(fn, length, false, prev, modified);
		} catch (java.io.IOException e) {
			if (Core.logger.shouldLog(Logger.ERROR, this))
				Core.logger.log(
					this,
					"Something fucked up in preload()",
					e,
					Logger.ERROR);
			throw new DirectoryException("Something fucked up in preload()");
		}
		//         Core.logger.log(this, "Preloaded: "+fn+": "+x, Logger.DEBUG);
		// No need to synchronize as preload is the only thread running.
		// No point in multithreading preload as it never blocks.
		buffers.put(fn, x);
		histAdd(fn, x.size);
		spaceUsed += spaceForFile(x.size);
		return x;
	}

	/**
	 * Remove a {key,size} pair from the histograms
	 */
	void histRemove(FileNumber fn, long size) {
		if (keyHistogram != null)
			keyHistogram.remove(fn.getByteArray());
		if (keySizeHistogram != null)
			keySizeHistogram.remove(size);
	}

	/**
	 * Add a {key, size} pair to the histograms
	 */
	void histAdd(FileNumber fn, long size) {
		if (keyHistogram != null)
			keyHistogram.add(fn.getByteArray());
		if (keySizeHistogram != null)
			keySizeHistogram.add(size);
	}

	/**
	 * Create a Buffer to write a file to
	 * 
	 * @param fn
	 *            the FileNumber of the file to store
	 * @param size
	 *            the length of the buffer
	 * @return an ExternalNativeBuffer to write to
	 */
	public Buffer store(long size, FileNumber fn) {
		File temp = getTempFile(fn);
		if (logDEBUG) {
			Core.logger.log(
				this,
				"store: "
					+ getPath(fn)
					+ " ("
					+ size
					+ ") temp: "
					+ temp.getPath(),
				Logger.DEBUG);
		}
		NativeBuffer x;
		synchronized (buffers) {
			try {
				synchronized (lruSync) {
					verifyList();
					x =
						new NativeBuffer(
							fn,
							size,
							true,
							false,
							mostRecentlyUsed);
					x.lastModified = System.currentTimeMillis();
					if (mostRecentlyUsed == null) {
						mostRecentlyUsed = x;
						leastRecentlyUsed = x;
					} else
						mostRecentlyUsed.nextLRU = x;
					if (x.lastModified < mostRecentlyUsed.lastModified) {
						Core.logger.log(
							this,
							"ERROR! Clock skew detected!",
							Logger.ERROR);
						x.prevLRU = null;
						x.nextLRU = null;
						x.setLastModified(x.lastModified);
					}
					mostRecentlyUsed = x;
					verifyList();
				}
			} catch (java.io.IOException e) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Returning null storing "
							+ fn
							+ " because of exception",
						e,
						Logger.DEBUG);
				return null;
			}

			//if(buffers.containsKey(fn)) { } else
			//   buffers.put(fn, x);
		}
		synchronized (spaceUsedSync) {
			spaceUsed += spaceForFile(size);
			tempSpaceUsed += spaceForFile(size);
			if (logDEBUG)
				Core.logger.log(
					this,
					"Storing, spaceUsed="
						+ spaceUsed
						+ ", tempSpaceUsed="
						+ tempSpaceUsed
						+ " for "
						+ x,
					Logger.DEBUG);
		}
		histAdd(fn, size);
		return new ExternalNativeBuffer(x);
	}

	public long lastModified(FileNumber f) {
		NativeBuffer b = internalFetch(f);
		if (b == null)
			return -1;
		return b.lastModified();
	}

	public long tempSpaceUsed() {
		return tempSpaceUsed;
	}

	public long maxTempSpace() {
		return maxTempSpaceUsed;
	}

	public long mostRecentlyUsedTime() {
		synchronized (lruSync) {
			if (mostRecentlyUsed != null) {
				return mostRecentlyUsed.lastModified();
			} else
				return -1;
		}
	}

	public long leastRecentlyUsedTime() {
		synchronized (lruSync) {
			if (leastRecentlyUsed != null) {
				return leastRecentlyUsed.lastModified();
			} else
				return -1;
		}
	}

	/**
	 * Estimate the real space used by a file
	 * 
	 * @param x
	 *            the length of the file
	 */
	public long spaceForFile(long x) {
		// The number of blocks needed - 4095 is 1, 4096 is 1, 4097 is 2.
		// Plus 1 for filesystem overhead (pretty pessimistic...)
		// We *could* have a separate overhead parameter... FIXME ?
		if (x < 0)
			return x;
		else
			return ((x / blockSize) + (((x % blockSize) == 0) ? 0 : 1) + 1)
				* blockSize;
	}

	// Checkpoint interface

	boolean runningCheckpoint = false;

	public void checkpoint() {
		long time = System.currentTimeMillis();
		if (runningCheckpoint)
			return;
		try {
			runningCheckpoint = true;
			if (logDEBUG)
				Core.logger.log(
					this,
					"Checkpointing NativeFSDirectory at " + time,
					Logger.DEBUG);
			writeIndex();
			if (logDEBUG)
				Core.logger.log(
					this,
					"Checkpointed NativeFSDirectory, took "
						+ (System.currentTimeMillis() - time),
					Logger.DEBUG);
			runningCheckpoint = false;
		} finally {
			runningCheckpoint = false;
		}
		time = System.currentTimeMillis() - time;
		synchronized (checkpointTimesSync) {
			checkpointsLengthTotal += time;
			checkpointsTotal++;
		}

	}

	public String getCheckpointName() {
		return "Native Filesystem Directory checkpoint";
	}

	public long nextCheckpoint() {
		if (runningCheckpoint)
			return System.currentTimeMillis() + minCheckpointSleepTime;
		synchronized (checkpointTimesSync) {
			if (checkpointsTotal > 0) {
				checkpointSleepTime =
					minCheckpointSleepTime
						+ (20 * checkpointsLengthTotal) / checkpointsTotal;
				// Use no more than 5% of total store CPU time
			} else
				checkpointSleepTime = defaultCheckpointSleepTime;
		}
		if(logDEBUG)
		    Core.logger.log(this, "Next checkpoint is in "+checkpointSleepTime,
		            Logger.DEBUG);
		return System.currentTimeMillis() + checkpointSleepTime;
	}

	/**
	 * temporary Buffer - passes through to the underlying NativeBuffer, but
	 * keeps track of writers and readers allocated through itself, and closes
	 * them when asked to release()
	 */
	public class ExternalNativeBuffer implements Buffer {
		private NativeBuffer buffer;
		private String asString = null;

		public String toString() {
			if (buffer == null && asString != null)
				return asString;
			return super.toString() + ":" + buffer;
		}

		ExternalNativeBuffer(NativeBuffer b) {
			Core.logger.log(this, "Wrapping " + b + ": " + this, Logger.DEBUG);
			buffer = b;
		}

		public long length() {
			return (buffer == null) ? -1 : buffer.length();
		}

		public long realLength() {
			return (buffer == null) ? 0 : buffer.realLength();
		}

		public boolean failed() {
			return (buffer == null) ? false : buffer.failed();
		}

		public void touch() {
			if (buffer != null)
				buffer.touch();
		}
		public void touchThrottled() {
			if (buffer != null)
				buffer.touchThrottled();
		}

		public void commit() {
			if (buffer != null)
				buffer.commit();
		}

		Vector myIns = new Vector();
		Vector myOuts = new Vector();

		/**
		 * Close all our writers and readers, and then ask the underlying
		 * buffer to release
		 */
		public void release() {
			if (buffer == null)
				return;
			FileNumber fn = buffer.fn;
			if (logDEBUG)
				Core.logger.log(
					this,
					"ExternalNativeBuffer releasing " + fn,
					Logger.DEBUG);
			synchronized (this) {
				for (int x = 0; x < myIns.size(); x++) {
					try {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Closing a reader in " + fn,
								Logger.DEBUG);
						((InputStream) myIns.elementAt(x)).close();
					} catch (IOException e) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"IOException while trying to close reader",
								e,
								Logger.DEBUG);
						throw new DirectoryException(
							"IOException while closing InputStream in " + fn);
					}
				}
				myIns.clear();
				for (int x = 0; x < myOuts.size(); x++) {
					try {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Closing a writer in " + fn,
								Logger.DEBUG);
						((OutputStream) myOuts.elementAt(x)).close();
					} catch (IOException e) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"IOException while trying to close writer",
								e,
								Logger.DEBUG);
						throw new DirectoryException("IOException while closing OutputStream");
					}
				}
				myOuts.clear();
				buffer.releaseInternal(false, true);
				if (logDEBUG)
					asString = toString();
				buffer = null; // _WE_ have released
			}
			if (logDEBUG)
				Core.logger.log(
					this,
					"ExternalNativeBuffer released " + fn,
					Logger.DEBUG);
		}

		/**
		 * Get an InputStream to this Buffer. We will keep track of these and
		 * close() them on release()
		 */
		public InputStream getInputStream() throws IOException {
			if (buffer == null) {
				String err =
					"Attempt to get a stream from an already "
						+ "released Buffer: "
						+ this;
				IllegalStateException x = new IllegalStateException(err);
				Core.logger.log(this, err, x, Logger.MINOR);
				return null;
			}
			InputStream in = buffer.getInputStream();
			myIns.addElement(in);
			return in;
		}

		/**
		 * Get an OutputStream to the Buffer. We will keep track of these and
		 * close() them on release()
		 */
		public OutputStream getOutputStream() throws IOException {
			if (buffer == null) {
				Core.logger.log(
					this,
					"Attempt to get a stream from an already released Buffer",
					new IllegalStateException("Attempt to get a stream from an already released Buffer"),
					Logger.NORMAL);
				return null;
			}
			OutputStream out = buffer.getOutputStream();
			myOuts.addElement(out);
			return out;
		}
	}

	protected void verifyList() {
		verifyList(false);
	}

	// Call synchronized(lruSync)
	protected void verifyList(boolean force) {
		//Core.logger.log(this, "Verifying LRU list", Logger.DEBUG);
		if (!force) {
			if (verifyMode == NEVER) {
				return;
			}

			if (verifyMode != ALWAYS) //Then it is SOMETIMES
			{
				// Verify the list in one of 64Ki operations.
				verification_requests_counter++;
				if(verification_requests_counter > 0xFFFF)
					verification_requests_counter =0;
				if(verification_requests_counter!=0)
					return;
			}
		}

		NativeBuffer b = leastRecentlyUsed;
		if (b == null) {
			if (mostRecentlyUsed == null)
				return;
			throw new DirectoryException("LRU is null but MRU is not");
		}
		long x = -1;
		NativeBuffer ob = null;
		while (true) {
			x = b.lastModified;
			long now = System.currentTimeMillis();
			if (x > now)
				throw new DirectoryException("Clock skew detected, datastore file '"+b.fileName()+"' has a last modified time which is "+((x-now)/1000)+" seconds into the future");
			ob = b;
			b = b.nextLRU;
			if (b == ob)
				throw new DirectoryException("circular item in list");
			if (b == null)
				break;
			if (b.lastModified < x)
				throw new DirectoryException("list inconsistent");
		}
		if (mostRecentlyUsed != ob)
			throw new DirectoryException("MRU is not MRU");
		//Core.logger.log(this, "Verified LRU list", Logger.DEBUG);
	}

	/**
	 * Like File.renameTo() but with support for moving across file systems.
	 * <p>
	 * This does not support renaming directories (not needed for
	 * NativeFSDirectory). When and if it does it should be refactored out so
	 * other classes can use it.
	 * </p>
	 * 
	 * @param src
	 *            must exist and be a file
	 * @param dest
	 *            must not exists
	 */
	private static void renameTo(File src, File dest) throws IOException {
		if (!src.exists()) {
			throw new IOException(
				"Cannot move " + src.getPath() + " because it does not exist");
		}
		if (src.renameTo(dest)) {
			return;
		} else if (!src.isFile()) {
			throw new IOException(
				"renameTo failed and cannot fall back because "
					+ src.getPath()
					+ " is a directory");
		} else if (!dest.createNewFile()) {
			// Create the file and check for existence as an atomic
			// operation
			// to prevent someone creating the file before we do. It's
			// strictly
			// necessary but it's a good measure before locking the hard
			// way.
			String msg = "Cannot rename file to '" + dest.getPath() + "'";
			if (dest.exists()) {
				msg += " because it already exist";
			}
			throw new IOException(msg);
		}

		FileInputStream fis = null;
		FileChannel fcin = null;
		FileChannel fcout = null;
		FileOutputStream fos = null;
		FileLock destLock = null;
		boolean success = false;

		try {
			fos = new FileOutputStream(dest);
			fcout = fos.getChannel();

			// Get an exclusive lock on the destination file while creating
			// it.  There should be no need to lock the source file.
			destLock = fcout.tryLock();
			if (destLock == null) {
				throw new IOException(
					"Cannot rename to '"
						+ dest.getPath()
						+ "' because the file is in use");
			}

			fis = new FileInputStream(src);
			fcin = fis.getChannel();
			fcin.transferTo(0, fcin.size(), fcout);

			if (!src.delete()) {
				throw new IOException(
					"Cannot remove source file '"
						+ src.getPath()
						+ "' after copying");
			}

			success = true;

		} finally {

			// Clean up even on exception.
			try {
				if (destLock != null) {
					destLock.release();
				}
				if (fcin != null) {
					fcin.close();
				}
				if (fcout != null) {
					fcout.close();
				}
				if (fis != null) {
					fis.close();
				}
				if (fos != null) {
					fos.close();
				}
			} catch (IOException ioe) {
				Core.logger.log(
					NativeFSDirectory.class,
					"Exception on renameTo cleanup: " + ioe,
					Logger.NORMAL);
			}

			// Remove the destination file if the move was successful.
			if (success && !src.delete()) {
				Core.logger.log(
					NativeFSDirectory.class,
					"Cannot remove source file '"
						+ src.getPath()
						+ "' after rename",
					Logger.ERROR);
			}

		}
	}

	// REDFLAG: These 65536 objects are used as read and write semaphores for
	// NativeBuffer access. They consume at least 512KB of RAM. There's likely
	// something else that already exists that can be synchronized on.
	private static final int SEMAPHORE_COUNT =32768;
	private static final Object[] insSyncs = new Object[SEMAPHORE_COUNT];
	private static final Object[] outsSyncs = new Object[SEMAPHORE_COUNT];
	static {
		for (int x = 0; x < SEMAPHORE_COUNT; x++) {
			insSyncs[x] = new Object();
			outsSyncs[x] = new Object();
		}
	}

	public int totalOpenFiles() {
		return totalOpenFiles; //No need to sync since the var is volatile
	}

	private Object totalOpenFilesSync = new Object();
	private volatile int totalOpenFiles = 0;

	private class NativeBuffer implements Buffer, Comparable {

		/**
		 * Do not modify the file number after instantiation, unless the cached
		 * file objects are cleared on the file number change.
		 */
		private FileNumber fn;

		private volatile boolean usedSinceCommit = true;

		private byte status = TEMPORARY;
		private static final byte TEMPORARY = 0;
		private static final byte COMMITTED = 1;
		private static final byte FAILED = 2;
		private static final byte ALMOSTCOMMITTED = 3;
		private static final int  THROTTLEDTOUCH_INTERVAL = 60000; //1 minute

		/**
		 * Cache file objects because they can be expensive to create from a
		 * FileNumber. The status-specific file is cleared when the status
		 * changes.
		 */
		private File file, fileForStatus;

		private long size = 0;
		private long tempRand = -1;
		private PooledRandomAccessFile raf;

		private final Object rafSync = new Object();
		private Object insSync;
		private Object outsSync;
		// Locking: this must be taken BEFORE rafSync

		private long rafPos = -1;
		private long lastModified = -1;

		public int compareTo(Object o) {
			if (o instanceof NativeBuffer) {
				NativeBuffer b = (NativeBuffer) o;
				if (b == this)
					return 0;
				return fn.compareTo(b.fn);
			} else
				throw new ClassCastException();
		}

		public boolean equals(Object o) {
			if (o == null)
				return false;
			if (o instanceof NativeBuffer) {
				NativeBuffer b = (NativeBuffer) o;
				if (b == this)
					return true;
				return fn.equals(b.fn);
				// Some java collections require that equals() <=>
				// compareTo()==0
			} else
				return false;
		}

		NativeBuffer nextLRU;
		NativeBuffer prevLRU;

		/* readers - writers wait for readers ahead of them */
		Vector ins = null;
		/* writers - readers wait for writers ahead of them */
		Vector outs = null;

		/**
		 * Determine how far we can write (or read) without having to wait for
		 * readers (writers) ahead of us. Writers must stay one byte behind
		 * readers, but readers can go right up to writers. This is to prevent
		 * writers behind from coming up to the reader's position and
		 * deadlocking.
		 * 
		 * @param obj
		 *            the writer (reader)
		 * @param len
		 *            the maximum length we want to write
		 * @param v
		 *            Vector of readers (writers) that may be ahead of us
		 * @param writing
		 *            are we reading or writing?
		 * @return the number of bytes we can write (read) without waiting
		 */
		protected int maxLen(
			NativeStream obj,
			int len,
			Vector v,
			Object sync,
			boolean writing) {
			long newLen = 0;
			if (v != null) {
				synchronized (v) {
					newLen = maxLenSynchronized(obj, len, v, writing);
				}
			} else {
				newLen = maxLenSynchronized(obj, len, v, writing);
			}
			if (len > 1 && logDEBUG)
				Core.logger.log(
					NativeBuffer.class,
					"maxlen: "
						+ len
						+ " => "
						+ newLen
						+ "("
						+ (writing ? "W" : "R")
						+ ")",
					Logger.DEBUG);
			return (newLen > Integer.MAX_VALUE)
				? Integer.MAX_VALUE
				: (int) newLen;
		}

		protected long maxLenSynchronized(
			NativeStream obj,
			int len,
			Vector v,
			boolean writing) {
			long newLen = len;
			int sz = (v == null ? 0 : v.size());
			if (len > 1 && logDEBUG)
				Core.logger.log(
					NativeBuffer.class,
					"Checking maxlen, with " + sz + " peers",
					Logger.DEBUG);
			long position = obj.position();
			for (int i = 0; i < sz; i++) {
				NativeStream out = (NativeStream) v.elementAt(i);
				long pos = out.position();
				if (len > 1 && logDEBUG)
					Core.logger.log(
						NativeBuffer.class,
						"checking maxLen: want "
							+ position
							+ ", have "
							+ pos
							+ ", currently "
							+ newLen,
						Logger.DEBUG);
				if (writing ? position < pos : position <= pos) {
					long x = (pos - position) - (writing ? 1 : 0);
					if (x < newLen) {
						if (x <= 0 && logDEBUG)
							Core.logger.log(
								this,
								"Waiting at "
									+ position
									+ " ("
									+ (writing ? "W" : "R")
									+ ") for "
									+ out
									+ " to "
									+ " move from "
									+ pos
									+ " max was "
									+ newLen
									+ " on "
									+ fn
									+ " x = "
									+ x,
								Logger.DEBUG);
						newLen = x;
					}
				}
				// writer must stay 1 BEHIND a reader in front of it
				// but reader can go right up to writer
				// reader, writer at same place =>
				//   reader blocked, but writer can move
			}
			return newLen;
		}

		/**
		 * Create buffer from file, only if exists
		 */
		public NativeBuffer(FileNumber fn) throws java.io.IOException {
			_init(fn, 0, false, true, null);
		}

		/**
		 * Create buffer from file, possibly create
		 */
		public NativeBuffer(FileNumber fn, boolean b)
			throws java.io.IOException {
			_init(fn, 0, b, true, null);
		}

		/**
		 * Create buffer from file with length, only if exists
		 */
		public NativeBuffer(FileNumber fn, long size)
			throws java.io.IOException {
			_init(fn, size, false, true, null);
		}

		/**
		 * Create buffer from file with length, possibly create
		 */
		public NativeBuffer(
			FileNumber fn,
			long size,
			boolean b,
			NativeBuffer prev,
			long modified)
			throws java.io.IOException {
			_init(fn, size, b, true, prev, modified);
		}

		/**
		 * Create buffer from file with length, possibly create
		 */

		public NativeBuffer(
			FileNumber fn,
			long size,
			boolean create,
			boolean committed,
			NativeBuffer prev)
			throws java.io.IOException {
			_init(fn, size, create, committed, prev, -1);
		}

		/**
		 * Create buffer from file with length, possibly create, with a commit
		 * name
		 */
		public NativeBuffer(
			FileNumber fn,
			long size,
			boolean create,
			boolean committed,
			NativeBuffer prev,
			long modified)
			throws java.io.IOException {
			_init(fn, size, create, committed, prev, modified);
		}

		/**
		 * Initialize buffer, called by constructors
		 */
		private void _init(
			FileNumber fn,
			long size,
			boolean create,
			boolean committed,
			NativeBuffer prev)
			throws java.io.IOException {
			_init(fn, size, create, committed, prev, -1);
		}

		/**
		 * Initialize buffer, called by constructors
		 */
		private void _init(
			FileNumber fn,
			long size,
			boolean create,
			boolean committed,
			NativeBuffer prev,
			long modified)
			throws java.io.IOException {
			this.fn = fn;
			if (fn == null)
				throw new IllegalArgumentException("FN null!");
			int x = fn.hashCode();
			this.insSync = insSyncs[x & SEMAPHORE_COUNT-1];
			this.outsSync = outsSyncs[x & SEMAPHORE_COUNT-1];
			this.size = size;
			this.prevLRU = prev;
			if (committed) {
				status = COMMITTED;
			} else {
				status = TEMPORARY;
				tempRand = Core.getRandSource().nextLong();
			}
			File f = getFileForStatus();
			//         if(logDEBUG)
			//         Core.logger.log(this, "Creating "+f, Logger.DEBUG);
			if (create && (!f.exists()))
				f.createNewFile();
			if (this.size < 1)
				this.size = f.length();
			//         if(logDEBUG)
			//         Core.logger.log(this, "File " + f.toString() + " length " +
			//                 this.size, Logger.DEBUG);
			if (modified == -1) {
				lastModified = f.lastModified();
				if (lastModified > System.currentTimeMillis())
					lastModified = System.currentTimeMillis();
			} else
				lastModified = modified;
			raf = null;
		}

		/**
		 * Open raf
		 */
		protected void open() throws IOException {
			synchronized (rafSync) {
				if (status == FAILED)
					throw new DirectoryException(
						"Trying to open failed buffer" + fn);
				if (raf == null) {
					synchronized (totalOpenFilesSync) {
						totalOpenFiles++;
					}
					rafPos = -1;
					raf = rafpool.open(getFileForStatus(), "rw");
				}
			}
			touchThrottled();
		}

		/**
		 * Get file currently using
		 */
		protected File getFileForStatus() {
			if (fileForStatus == null) {
				if (status == COMMITTED) {
					fileForStatus = getFile();
				} else if (status == TEMPORARY || status == ALMOSTCOMMITTED) {
					fileForStatus =
						NativeFSDirectory.this.getTempFile(fn, tempRand);
				} else if (status == FAILED) {
					fileForStatus = null;
				} else {
					throw new DirectoryException(
						"invalid buffer status " + status);
				}
			}
			return fileForStatus;
		}

		Object fileSync = new Object();
		
		protected File getFile() {
			// Cache the file because it can be expensive to create.
		    if(logDEBUG) Core.logger.log(this, "getFile() on "+this, Logger.DEBUG);
		    synchronized(fileSync) {
		        if (file == null) {
		            if(logDEBUG)
		                Core.logger.log(this, "Getting new file on "+this, 
		                        Logger.DEBUG);
		            file = NativeFSDirectory.this.getFile(fn);
		        }
		        if(logDEBUG)
		            Core.logger.log(this, "Returning "+file, Logger.DEBUG);
		        return file;
		    }
		}

		private void setStatus(byte status) {
		    synchronized(fileSync) {
		        // Clear the cached file object if the status has changed.
		        if (this.status != status) {
		            this.status = status;
		            // file = null;
		            fileForStatus = null;
		        }
		    }
		}

		int totalInsOutsSize() {
			int x = 0;
			Vector v = ins;
			if (v != null)
				x += v.size();
			v = outs;
			if (v != null)
				x += v.size();
			return x;
		}

		/**
		 * Close raf, if we have no readers/writers
		 */
		protected void close() {
			synchronized (this) {
				if (totalInsOutsSize() == 0) {
					ins = null;
					outs = null;
					synchronized (rafSync) {
						if (raf != null)
							try {
								rafPos = -1;
								raf.sync();
								raf.close();
								synchronized (totalOpenFilesSync) {
									totalOpenFiles--;
								}
								raf = null;
							} catch (IOException e) {
								if (Core.logger.shouldLog(Logger.ERROR, this))
									Core.logger.log(
										this,
										"IOException while closing",
										e,
										Logger.ERROR);
								throw new DirectoryException("IOException while closing");
							}
					}
				}
			}
		}

		/**
		 * Flush all writers to disk
		 */
		public void flush() throws java.io.IOException {
			synchronized (rafSync) {
				if (raf != null)
					raf.sync();
			}
			touch();
		}

		/**
		 * Length of buffer (it may not use all this space at the moment)
		 */
		public long length() {
			return size;
		}

		/**
		 * Current length of file - the number of bytes we actually have cached
		 */
		public long realLength() {
			return getFileForStatus().length();
		}

		public boolean failed() {
			return status == FAILED;
		}

		/**
		 * Is the file committed, now or before opening?
		 * 
		 * @return false if the file is a temp file
		 */
		public boolean committed() {
			return status == COMMITTED || status == ALMOSTCOMMITTED;
		}

		/**
		 * Get an InputStream to read from this file, from the beginning. We
		 * open the file on the first stream open, and close it on the last
		 * stream close.
		 * 
		 * @return a NativeInputStream
		 */
		public InputStream getInputStream() throws java.io.IOException {
			usedSinceCommit = true;
			if (logDEBUG)
				Core.logger.log(
					this,
					"getInputStream " + NativeBuffer.this,
					Logger.DEBUG);
			NativeInputStream str;
			synchronized (insSync) {
				if (status == FAILED) {
					Core.logger.log(
						this,
						"Attempt to get stream for failed NativeBuffer",
						new IllegalStateException("Attempt to get stream for failed NativeBuffer"),
						Logger.MINOR);
					return null;
				}
				str = new NativeInputStream();
				if (ins == null) {
					ins = new Vector();
				}
				ins.addElement(str);
				if (logDEBUG)
					Core.logger.log(
						this,
						"Got input stream for "
							+ NativeBuffer.this
							+ ": "
							+ str.toString(),
						Logger.DEBUG);
			}
			open();
			touch();
			return str;
		}

		/**
		 * Get an OutputStream to write to the file, from the beginning. We
		 * open the file on the first stream open, and close it on the last
		 * stream close.
		 * 
		 * @return a NativeOutputStream
		 */
		public OutputStream getOutputStream() throws java.io.IOException {
			usedSinceCommit = true;
			if (logDEBUG)
				Core.logger.log(
					this,
					"getOutputStream " + NativeBuffer.this,
					Logger.DEBUG);
			NativeOutputStream str;
			synchronized (outsSync) {
				if (status == FAILED)
					return null;
				// Circular buffers need to be able to use multiple writers
				// parent will protect us from multiple simultaneous normal
				// writers
				str = new NativeOutputStream();
				if (outs == null)
					outs = new Vector();
				outs.addElement(str);
				if (logDEBUG)
					Core.logger.log(
						this,
						"Got output stream for "
							+ NativeBuffer.this
							+ ": "
							+ str.toString(),
						Logger.DEBUG);
			}
			open();
			touch();
			return str;
		}

		/**
		 * Set the last used time to now
		 */
		public void touch() {
			// It's not worth special-casing as it's only a difference
			// of a few compares
			setLastModified(System.currentTimeMillis());
		}
		
		/**
		 * Set the last used time to now if a significant time has
		 * passed since the last time this was done.
		 * As long as that 'significant time' << DS turnaround time 
		 * this will not have a bad impact on the lru kept by the DS
		 * 
		 * At a node datarate of 1MBit/s throttling touch()'s to once 
		 * per 60 seconds saves around 30 touch()'s per second.
		 * /Iakin 2004-01-22  
		*/
		public void touchThrottled() {
			if((lastModified+THROTTLEDTOUCH_INTERVAL)<System.currentTimeMillis()) //Limit to one touch per interval maximum
				touch();
		}

		public void setLastModified(long x) {
			if (status != FAILED) {
				getFileForStatus().setLastModified(x); //Intentionally do this before the sync block (might be slow due to OS issues)
				synchronized (lruSync) {
					verifyList();
					lastModified = x;
					// Remove from list first
					if (prevLRU != null)
						prevLRU.nextLRU = nextLRU;
					if (nextLRU != null)
						nextLRU.prevLRU = prevLRU;
					if (mostRecentlyUsed == this)
						mostRecentlyUsed = prevLRU;
					if (leastRecentlyUsed == this)
						leastRecentlyUsed = nextLRU;
					// Assume lastModified is increasing (FIXME?)
					verifyList();
					setLastModifiedAlreadyOut(x, false);
				}
			}
		}

		protected void setLastModifiedAlreadyOut(
			long x,
			boolean forceValidEverywhere) {
			NativeBuffer b = mostRecentlyUsed;
			if (b == null) {
				// Only element in list
				prevLRU = nextLRU = null;
				mostRecentlyUsed = leastRecentlyUsed = this;
				verifyList(forceValidEverywhere);
				//Core.logger.log(this, "done setLastModified (a)",
				//        Logger.DEBUG);
				return;
			}
			if (b.lastModified < x) {
				// insert at end of list
				b.nextLRU = this;
				prevLRU = b;
				nextLRU = null;
				mostRecentlyUsed = this;
				verifyList(forceValidEverywhere);
				//if(logDEBUG)
				//Core.logger.log(this, "done setLastModified (b)",
				//                  Logger.DEBUG);
				return;
			} else {
				NativeBuffer ob = null;
				while (b.lastModified >= x) {
					ob = b;
					b = b.prevLRU;
					if (b == null) {
						// Insert at beginning of list
						prevLRU = null;
						leastRecentlyUsed = this;
						nextLRU = ob;
						ob.prevLRU = this;
						verifyList(forceValidEverywhere);
						if (logDEBUG)
							Core.logger.log(
								this,
								"done setLastModified (c)",
								Logger.DEBUG);
						return;
					}
				}
				// Insert at middle of list
				prevLRU = b;
				nextLRU = ob;
				ob.prevLRU = this;
				b.nextLRU = this;
				verifyList(forceValidEverywhere);
				if (logDEBUG)
					Core.logger.log(
						this,
						"done setLastModified (d)",
						Logger.DEBUG);
			}
		}

		/**
		 * Flush all writers to disk, then move the file to its permanent
		 * name... if there isn't something already there.
		 */
		/*
		 * We always close a file before renaming it, for compatibility with
		 * certain non-POSIX OSs
		 */
		public void commit() {
		    if(logDEBUG)
		        Core.logger.log(this, "commit() on "+this, Logger.DEBUG);
			touch();
			if (status == COMMITTED || status == ALMOSTCOMMITTED)
				throw new DirectoryException(
					"trying to commit already committed Buffer " + this);
			if (status == FAILED)
				throw new DirectoryException(
					"trying to commit failed Buffer" + this);
			if (logDEBUG) {
				Core.logger.log(
					this,
					"Trying to commit "
						+ getFileForStatus()
						+ " to "
						+ getFile(),
					Logger.DEBUG);
			}

			// Checking for collision is done AT COMMIT TIME
			synchronized (buffers) {
				NativeBuffer b =(NativeBuffer) buffers.get(fn); 
				if (b != null) {
					if (b != this) {
						throw new DirectoryException("key collision");
					}
				} else {
					buffers.put(fn, this);
				}
			}
			synchronized (this) {
				try { // We can't have the status change while we are sync'ing
					synchronized (rafSync) { // locking: take this first
						if (raf != null)
							raf.sync();
					}
				} catch (IOException e) {
					Core.logger.log(
						this,
						"IOException trying to sync in commit()",
						e,
						Logger.NORMAL);
					throw new DirectoryException("IOException trying to sync in commit()");
				}
				setStatus(ALMOSTCOMMITTED);
				if (!doIndex)
					reallyCommit();
				if (logDEBUG)
					Core.logger.log(
						this,
						"Committed " + getFileForStatus()
						+", status now: "+status,
						Logger.DEBUG);
			}
			usedSinceCommit = false;
		}

		/**
		 * Move from ALMOSTCOMMITTED to COMMITTED
		 */
		public void reallyCommit() {
			// All I/O operations also synchronize on raf
			// Because they need to seek
			// This prevents us from having to deal with IOExceptions
			// Caused by raf being closed under a live i/o

			// We can't have them re-open it while we are doing this... we
			// can't have status change either. We absolutely must have both
			// locks. Get the order right! this before rafSync
		    if(logDEBUG)
		        Core.logger.log(this, "reallyCommit() on "+this, Logger.DEBUG);
			synchronized (this) {
				try {
					if (status != ALMOSTCOMMITTED)
						throw new DirectoryException(
							"reallyCommit() called on file "
								+ "not ALMOSTCOMMITTED");
					synchronized (rafSync) {
						boolean b = (raf != null);
						rafPos = -1;
						try {
							if (b) {
								synchronized (totalOpenFilesSync) {
									totalOpenFiles--;
								}
								raf.close();
							}
							raf = null;
						} catch (IOException e) {
							Core.logger.log(
								this,
								"IOException while closing in commit",
								e,
								Logger.NORMAL);
							throw new DirectoryException("IOException while closing in commit()");
						} finally {
							raf = null;
						}
						if (status != ALMOSTCOMMITTED)
							throw new DirectoryException(
								"reallyCommit() called on file "
									+ "not ALMOSTCOMMITTED");
						File f = getFileForStatus();
						if(logDEBUG)
						    Core.logger.log(this, "status: "+status+", file: "+f,
						            Logger.DEBUG);
						setStatus(COMMITTED);
						File g = getFileForStatus();
						if(logDEBUG)
						    Core.logger.log(this, "status: "+status+", file: "+g,
						            Logger.DEBUG);
						try {
						    if(logDEBUG)
						        Core.logger.log(this, "reallyCommit() moving: "+f+
						                " -> "+g, Logger.DEBUG);
							renameTo(f, g);
						} catch (IOException ioe) {
							handleIOERenaming(ioe, f, g);
						}
						try {
							if (b) {
								raf = rafpool.open(g, "rw");
								synchronized (totalOpenFilesSync) {
									totalOpenFiles++;
								}
							}
						} catch (IOException e) {
							Core.logger.log(
								this,
								"IOException reopening file in commit()",
								e,
								Logger.NORMAL);
							raf = null;
							throw new DirectoryException("IOException reopening file in commit()");
						}
					}
				} catch (DirectoryException e) {
					if (logDEBUG)
						Core.logger.log(
							this,
							"Caught DirectoryException trying to "
								+ "reallyCommit "
								+ this,
							e,
							Logger.DEBUG);
					synchronized (buffers) {
						if (buffers.get(fn) == this) {
							buffers.remove(fn);
						}
					}
					setStatus(TEMPORARY);
					throw e;
				}
			}
			synchronized (spaceUsedSync) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"reallyCommit moving file ("
							+ this
							+ ") out of temp space. Before: tempSpaceUsed="
							+ tempSpaceUsed,
						Logger.DEBUG);
				tempSpaceUsed -= spaceForFile(size);
				if (logDEBUG)
					Core.logger.log(
						this,
						"reallyCommit moved file ("
							+ this
							+ ") from temp space. After: tempSpaceUsed="
							+ tempSpaceUsed,
						Logger.DEBUG);
			}
		}

		/**
		 * @param ioe
		 */
		private void handleIOERenaming(IOException ioe, File f, File g) {
			// Hmmm
			String s = "Finalizing rename of ";
			if(g.exists()) {
				Core.logger.log(this, "Destination: "+f+
						" already existed when renaming "+g,
						Logger.NORMAL);
				g.delete();
				
				boolean failed = false;
				try {
					renameTo(f, g);
				} catch (IOException e) {
					s = "Tried deleting dest, but: "+s;
					failed = true;
				}
				if(!failed) return;
			}
			StringBuffer msg =
				new StringBuffer(s);
			msg.append(f.getPath()).append(" to ").append(
				g.getPath()).append(
				"failed! (");
			if (ioe.getMessage() == null) {
				msg.append(ioe);
			} else {
				msg.append(ioe.getMessage());
			}
			msg.append(')');

			Core.logger.log(this, msg.toString(), Logger.ERROR);
			throw new DirectoryException(msg.toString());
		}

		/**
		 * Move the buffer to the end of the LRU Should work even if the buffer
		 * is not currently in the LRU
		 */
		protected void moveToLRUEnd() {
			synchronized (lruSync) {
				if (leastRecentlyUsed == this)
					return;
				NativeBuffer oldStart = leastRecentlyUsed;
				lastModified = oldStart.lastModified() - 1; // demoted
				if (mostRecentlyUsed == this)
					mostRecentlyUsed = prevLRU;
				else if (nextLRU != null)
					nextLRU.prevLRU = prevLRU;
				if (prevLRU != null) {
					prevLRU.nextLRU = nextLRU;
					prevLRU = null;
				}
				oldStart.prevLRU = this;
				nextLRU = oldStart;
				leastRecentlyUsed = this;
				verifyList();
			}
		}

		/**
		 * Deallocate (if we aren't being held open by the Directory, in which
		 * case the only way to get deallocated is through
		 * NativeFSDirectory.delete() )
		 */
		public void release() {
			if (logDEBUG)
				Core.logger.log(this, "Releasing " + this, Logger.DEBUG);
			NativeBuffer b = (NativeBuffer) buffers.get(fn); 
			if (b==null
				|| (b != this)
				&& (!committed())) {
				releaseInternal(false, false);
			}
		}

		/**
		 * Convert from a committed file back to a temporary.
		 */
		public void renameToTemp() throws IOException {
			synchronized (this) { // locking: this before rafSync
				touch();
				synchronized (rafSync) {
					rafPos = -1;
					if (raf != null) {
						synchronized (totalOpenFilesSync) {
							totalOpenFiles--;
						}
						raf.close();
					}
					File f = getFileForStatus();
					tempRand = Core.getRandSource().nextLong();
					File g = getTempFile(fn, tempRand);
					try {
						try {
							renameTo(f, g);
						} catch (IOException ioe) {
							StringBuffer msg = new StringBuffer("Rename of ");
							msg.append(f.getPath()).append(" to ").append(
								g.getPath()).append(
								"failed! (");
							if (ioe.getMessage() == null) {
								msg.append(ioe);
							} else {
								msg.append(ioe.getMessage());
							}
							msg.append(')');

							Core.logger.log(this, msg.toString(), Logger.ERROR);
							throw new DirectoryException(msg.toString());
						}

					} finally {
						raf = null;
					}
					int oldStatus = status;
					setStatus(TEMPORARY);
					if (oldStatus == COMMITTED) {
						long x = spaceForFile(size);
						synchronized (spaceUsedSync) {
							if (logDEBUG)
								Core.logger.log(
									this,
									"renameToTemp on "
										+ this
										+ " moving "
										+ "back to temp space: before, tempSpaceUsed="
										+ tempSpaceUsed
										+ ", after, tempSpaceUsed="
										+ (tempSpaceUsed + x)
										+ ", file size "
										+ x,
									Logger.DEBUG);
							tempSpaceUsed += x;
						}
					}
					raf = rafpool.open(g, "rw");
					synchronized (totalOpenFilesSync) {
						totalOpenFiles++;
					}
				}
			}
		}

		protected void finalize() {
			releaseInternal(true, false);
		}

		public String toString() {
			StringBuffer s = new StringBuffer(fn.toString());
			s.append(':');
			if (status == TEMPORARY)
				s.append("temp");
			else if (status == COMMITTED)
				s.append("committed");
			else if (status == ALMOSTCOMMITTED)
				s.append("almostCommitted");
			else if (status == FAILED)
				s.append("failed");
			else
				s.append(status);
			s.append(':').append(size).append(':').append(
				Long.toHexString(tempRand));
			return new String(s);
		}

		/**
		 * Remove this file from the LRU list
		 */
		protected void removeFromLRU() {
			synchronized (lruSync) {
				verifyList();
				setStatus(FAILED);
				// the spaceUsed section was here.
				if (prevLRU != null)
					prevLRU.nextLRU = nextLRU;
				if (nextLRU != null)
					nextLRU.prevLRU = prevLRU;
				if (mostRecentlyUsed == this)
					mostRecentlyUsed = prevLRU;
				if (leastRecentlyUsed == this)
					leastRecentlyUsed = nextLRU;
				nextLRU = null;
				prevLRU = null;
				verifyList();
			}
		}

		/**
		 * release() without checking whether we are in the Directory Do not
		 * release if we have live readers/writers though.
		 * 
		 * @param force
		 *            whether to force close readers/writers, if not will fail
		 *            if there are open readers/writers
		 * @param weak
		 *            if true, fail if the buffer is in buffers, if false,
		 *            remove it from buffers if necessary
		 */
		protected void releaseInternal(boolean force, boolean weak) {
			if (!weak)
				releaseFromBuffers();
			else {
				if (status == COMMITTED || status == ALMOSTCOMMITTED)
					return;
			}
			if (logDEBUG)
				Core.logger.log(
					this,
					"Really releasing " + this,
					new DirectoryException("debug"),
					Logger.DEBUG);
			synchronized (outsSync) {
				if (!force) {
					if (outs != null && outs.size() > 0) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Still writing while releasing " + this,
								Logger.DEBUG);
						return;
					}
				} else
					while (outs != null && outs.size() > 0) {
						try {
							((NativeOutputStream) (outs.elementAt(0))).close(
								true);
						} catch (IOException e) {
						}
					}
				if (logDEBUG)
					Core.logger.log(
						this,
						"Done writers (" + this +")",
						Logger.DEBUG);
				synchronized (insSync) {
					if (outs != null)
						outs.trimToSize();
					if (!force) {
						if (ins != null && ins.size() > 0) {
							if (logDEBUG)
								Core.logger.log(
									this,
									"Still reading while releasing"
										+ " "
										+ this,
									Logger.DEBUG);
							return;
						}
					} else
						while (ins != null && ins.size() > 0) {
							((NativeInputStream) (ins.elementAt(0))).close(
                            	true);
						}
					// Now we are synchronized on both ins and outs
					if (logDEBUG)
						Core.logger.log(
							this,
							"Done readers (" + this +")",
							Logger.DEBUG);
					if (weak) {
					    // Doesn't make any sense to release it if it's not in buffers!
						if (status == COMMITTED || status == ALMOSTCOMMITTED)
						    releaseFromBuffers();
					}
					close();
					if (logDEBUG)
						Core.logger.log(
							this,
							"Closed and still really releasing " + this,
							Logger.DEBUG);
					if (status == FAILED) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Already failed",
								Logger.DEBUG);
						return;
					}
					File f = getFileForStatus();
					int oldStatus = status;
					removeFromLRU();
					long x = spaceForFile(size);
					if (!f.exists()) {
						Core.logger.log(
							this,
							"While deleting, didn't exist: "
								+ f.toString()
								+ " - either it was deleted by "
								+ "the  user or there is a bug. Please report"
								+ " to devl@freenetproject.org",
							new DirectoryException("backtrace"),
							Logger.NORMAL);
					} else {
						if (!f.delete()) {
							if (f.exists()) {
								Core.logger.log(
									this,
									"Could not delete "
										+ f.toString()
										+ "! - "
										+ "check for permissions problems, if none report "
										+ "to devl@freenetproject.org",
									Logger.ERROR);
								// What to do with it?
								// If we put it back in the LRU at the end,
								// we'll spin on it... unless we kill the
								// space... too much complexity
								// Lets just disable indexing
								indexSpoiled = true;
								throw new DirectoryException(
									"Couldn't delete " + f.toString());
							}
							// Else forget about it
						}
					}
					// This doesn't need to be inside lruSync, does it?
					synchronized (spaceUsedSync) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Releasing "
									+ this
									+ " - spaceUsed="
									+ spaceUsed
									+ ", tempSpaceUsed="
									+ tempSpaceUsed
									+ ", size="
									+ x,
								Logger.DEBUG);
						spaceUsed -= x;
						if (oldStatus == ALMOSTCOMMITTED
							|| oldStatus == TEMPORARY)
							tempSpaceUsed -= x;
						if (logDEBUG)
							Core.logger.log(
								this,
								"Released "
									+ this
									+ " - spaceUsed="
									+ spaceUsed
									+ ", tempSpaceUsed="
									+ tempSpaceUsed
									+ ", size="
									+ x,
								Logger.DEBUG);
					}
					if (logDEBUG)
						Core.logger.log(
							this,
							"Really released "
								+ this
								+ ", now free: "
								+ available(),
							Logger.DEBUG);
				}
				// If we have committed, file will == commitname
				// if we haven't, we don't want to delete commitname
			}
		}

		protected void releaseFromBuffers() {
			synchronized (buffers) {
				NativeBuffer b  =(NativeBuffer) buffers.get(fn);
				if (b != null) {
					if (b != this) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Another instance in buffers"
									+ ": I am "
									+ this
									+ " but it is "
									+ b,
								Logger.DEBUG);
					} else {
						if (logDEBUG)
							Core.logger.log(
								this,
								"Removing " + fn + " from buffers",
								Logger.DEBUG);
						buffers.remove(fn);
					}
				} else {
					if (logDEBUG)
						Core.logger.log(
							this,
							fn.toString() + " not in buffers",
							Logger.DEBUG);
				}
			}
		}

		String fileName() {
			File file = getFileForStatus();
			if (file == null) {
				return null;
			}
			return file.getPath();
		}

		long lastModified() {
			return lastModified;
		}

		class NativeInputStream extends InputStream implements NativeStream {
			long position = 0;
			boolean closed = false;

			/**
			 * Create an input stream
			 */
			public NativeInputStream() {
				if (logDEBUG)
					Core.logger.log(
						this,
						"open input: " + NativeBuffer.this,
						Logger.DEBUG);
			}

			public void close() {
				close(false);
			}

			/**
			 * Close the stream
			 */
			public void close(boolean alreadySynchronized) {
				if (closed)
					return;
				if (logDEBUG)
					Core.logger.log(
						this,
						"close input: " + NativeBuffer.this,
						Logger.DEBUG);
				if (alreadySynchronized) {
					closeInternal();
				} else {
					synchronized (NativeBuffer.this) {
						closeInternal();
					}
				}
				NativeBuffer.this.close();
				position = Long.MAX_VALUE;
			}

			private void closeInternal() {
				closed = true;
				synchronized (insSync) {
					if (ins == null)
						throw new IllegalStateException("ins null removing from ins!");
					ins.removeElement(this);
					ins.trimToSize();
					if (ins.size() == 0)
						ins = null;
				}
				NativeBuffer.this.notifyAll();
			}

			protected void finalize() {
				close();
			}

			protected boolean dead() {
				return closed || (status == FAILED);
			}

			/**
			 * How many bytes can we read without blocking on writers?
			 */
			public int available() {
				if (dead())
					return -1;
				long x = size - position;
				if (x > Integer.MAX_VALUE)
					x = Integer.MAX_VALUE;
				return maxLen(this, (int) x, outs, outsSync, false);
			}

			/**
			 * Read a byte (block if necessary)
			 */
			public int read() throws IOException {
				usedSinceCommit = true;
				if (dead())
					return -1;
				if (position + 1 > size)
					return -1;
				int maxlen, res;
				synchronized (NativeBuffer.this) {
					maxlen = maxLen(this, 1, outs, outsSync, false);
					//Core.logger.log(this, "read() 1 maxlen="+maxlen+
					//" size="+size+" position="+position,
					//Logger.DEBUG);
					try {
						while (maxlen <= 0) {
							NativeBuffer.this.wait(200); // evil buggy JVMs!
							if (dead())
								return -1;
							maxlen = maxLen(this, 1, outs, outsSync, false);
						}
					} catch (InterruptedException e) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"exception: " + e,
								Logger.DEBUG);
						return -1;
					}
					//Core.logger.log(this, "read() 2 maxlen="+maxlen,
					//                Logger.DEBUG);
					synchronized (rafSync) {
						try {
							if ((raf == null) && (status != FAILED))
								throw new DirectoryException(
									"File closed but status not FAILED. "
										+ "Please report.");
							if (rafPos != position)
								raf.seek(position);
							res = raf.read();
							rafPos = position + 1;
						} catch (IOException e) {
							rafPos = -1;
							throw e;
						}
					}
					position++;
					touchThrottled();
					// We are using mtime as atime - java only does mtime
					NativeBuffer.this.notify();
					// Read position has moved on
				}
				return res;
			}

			/**
			 * Read some bytes, blocking if necessary. Does not always read the
			 * full number of requested bytes.
			 */
			public int read(byte b[], int off, int len) throws IOException {
				usedSinceCommit = true;
				if (dead())
					return -1;
				if (logDEBUG)
					Core.logger.log(
						this,
						"read(b+,"
							+ off
							+ ","
							+ len
							+ ") size="
							+ size
							+ " position="
							+ position
							+ " 1 "
							+ fn,
						Logger.DEBUG);
				if (position + len > size) {
					len = (int) (size - position);
				}
				if (len <= 0)
					return -1;
				int maxlen;
				int wasread;
				synchronized (NativeBuffer.this) {
					maxlen = maxLen(this, len, outs, outsSync, false);
					try {
						while (maxlen == 0) {
							if (logDEBUG)
								Core.logger.log(
									this,
									"read() waiting for bytes "
										+ "at "
										+ position
										+ " of "
										+ size,
									Logger.DEBUG);
							NativeBuffer.this.wait(200);
							if (dead())
								return -1;
							maxlen = maxLen(this, len, outs, outsSync, false);
						}
					} catch (InterruptedException e) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"exception: " + e,
								Logger.DEBUG);
						return -1;
					}
					if (logDEBUG)
						Core.logger.log(
							this,
							"read(,,) 2 maxlen="
								+ maxlen
								+ ", position="
								+ position
								+ " "
								+ fn,
							Logger.DEBUG);
					if (maxlen > 0) {
						if (b != null) {
							synchronized (rafSync) {
								try {
									if (rafPos != position)
										raf.seek(position);
									wasread = raf.read(b, off, maxlen);
								} catch (IOException e) {
									rafPos = -1;
									throw e;
								}
								if (wasread < 0) {
									rafPos = -1;
								} else if (wasread > 0) {
									position += wasread;
									rafPos = position;
								}
							}
							if (wasread > 0) {
								touchThrottled();
								NativeBuffer.this.notify();
							}
						} else {
							position += maxlen;
							wasread = maxlen;
						}
					} else
						wasread = 0;
				}
				if (logDEBUG)
					Core.logger.log(
						this,
						wasread + "=read(b+," + off + "," + maxlen + ") 2",
						Logger.DEBUG);
				if (wasread < 0) {
					if (logDEBUG)
						Core
							.logger
							.log(
								this,
								"Deleting corrupt (too short) file: " + fn,
								Logger.MINOR
						/* hopefully! */
						);
					delete(fn);
				}
				return wasread;
			}

			/**
			 * Skip some bytes
			 */
			public long skip(long n) throws IOException {
				int x = (n > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) n;
				byte[] b = null;
				if (closed)
					return -1;
				return read(b, 0, x);
			}

			/**
			 * Current position in the file
			 */
			public long position() {
				return position;
			}

			/**
			 * Filename
			 */
			public File file() {
				return getFileForStatus();
			}
		}

		class NativeOutputStream extends OutputStream implements NativeStream {

			long position = 0;
			boolean closed = false;
			

			protected boolean dead() {
				return closed || (status == FAILED);
			}

			/**
			 * Create an output stream
			 */
			public NativeOutputStream() {
				if (logDEBUG)
					Core.logger.log(
						this,
						"open output: " + NativeBuffer.this,
						Logger.DEBUG);
			}

			public void close() throws java.io.IOException {
				close(false);
			}

			/**
			 * Close the stream
			 */
			public void close(boolean alreadySynchronized)
				throws java.io.IOException {
				if (closed)
					return;
				if (logDEBUG)
					Core.logger.log(
						this,
						"close output: "
							+ NativeBuffer.this
							+ " at "
							+ position
							+ " of "
							+ size,
						new DirectoryException("debug"),
						Logger.DEBUG);
				if (position != size && logDEBUG)
					Core.logger.log(
						this,
						"closing output not at buffer end",
						new DirectoryException("debug"),
						Logger.DEBUG);
				flush();
				if (alreadySynchronized) {
					closeInternal();
				} else {
					synchronized (NativeBuffer.this) {
						closeInternal();
					}
				}
				NativeBuffer.this.close();
				position = Long.MAX_VALUE;
			}

			private void closeInternal() {
				synchronized (outsSync) {
					if (outs == null)
						throw new IllegalStateException("outs null removing from outs!");
					outs.removeElement(this);
					if (outs.size() == 0)
						outs = null;
					else
						outs.trimToSize();
				}
				closed = true;
				NativeBuffer.this.notifyAll();
			}

			protected void finalize() {
				try {
					close();
				} catch (IOException e) {
				}
			}

			/**
			 * Flush unwritten data to disk
			 */
			public void flush() throws java.io.IOException {
				if (dead())
					return;
				synchronized (rafSync) {
					raf.sync();
				}
			}

			/**
			 * Write a byte to the file. Must succeed or throw. May block.
			 */
			public void write(int b) throws IOException {
				usedSinceCommit = true;
				if (dead())
					throw new DirectoryException("writing to a closed stream");
				if (logDEBUG)
					Core.logger.log(this, "Writing byte", Logger.DEBUG);
				if (position + 1 > size)
					return;
				synchronized (NativeBuffer.this) {
					int maxlen = maxLen(this, 1, ins, insSync, true);
					try {
						while (maxlen <= 0) {
							NativeBuffer.this.wait(200);
							if (dead())
								throw new DirectoryException("writing to a closed stream");
							maxlen = maxLen(this, 1, ins, insSync, true);
						}
					} catch (InterruptedException e) {
						if (logDEBUG)
							Core.logger.log(
								this,
								"exception: " + e,
								Logger.DEBUG);
						return;
					}
					synchronized (rafSync) {
						try {
							if (rafPos != position)
								raf.seek(position);
							raf.write(b);
						} catch (IOException e) {
							rafPos = -1;
							throw e;
						}
						rafPos = position + 1;
						touchThrottled();
					}
					position++;
					NativeBuffer.this.notify();
				}
			}

			/**
			 * Write a block of bytes to the file. Must succeed or throw.
			 */
			public void write(byte b[]) throws IOException {
				write(b, 0, b.length);
			}

			/**
			 * Write a block of bytes to the file. Must succeed or throw.
			 */
			public void write(byte b[], int off, int len) throws IOException {
				usedSinceCommit = true;
				int olen = len;
				if (len < 50 && logDEBUG)
					Core.logger.log(
						this,
						"really short write",
						new DirectoryException("debug"),
						Logger.DEBUG);
				if (dead())
					throw new DirectoryException("writing to a closed stream");
				if (logDEBUG)
					Core.logger.log(
						this,
						"write("
							+ b
							+ ","
							+ off
							+ ","
							+ len
							+ "), ("
							+ size
							+ ","
							+ position
							+ ") 1 "
							+ fn+
							" "+this,
						Logger.DEBUG);
				// off is the offset within b[], not the on disk buffer
				// clip to capacity - FIXME: should we throw here?
				if (position + len > size) {
					len = (int) (size - position);
					if (len < 0)
						len = 0;
				}
				if (len <= 0)
					return;
				while (len > 0) {
					int maxlen;
					synchronized (NativeBuffer.this) {
						maxlen = maxLen(this, len, ins, insSync, true);
						try {
							while (maxlen <= 0) {
								if (logDEBUG)
									Core.logger.log(
										this,
										"write waiting for "
											+ "bytes at "
											+ position
											+ " of "
											+ size,
										Logger.DEBUG);
								NativeBuffer.this.wait(200);
								if (dead())
									throw new DirectoryException("writing to a closed stream");
								maxlen = maxLen(this, len, ins, insSync, true);
							}
						} catch (InterruptedException e) {
							if (logDEBUG)
								Core.logger.log(
									this,
									"exception: " + e,
									Logger.DEBUG);
							return;
						}
						synchronized (rafSync) {
							try {
								if (rafPos != position)
									raf.seek(position);
								raf.write(b, off, maxlen);
								rafPos = position + maxlen;
							} catch (IOException e) {
								rafPos = -1;
								throw e;
							}
						}
						position += maxlen;
						touchThrottled();
						if (logDEBUG)
							Core.logger.log(
								this,
								"Write(,,) now at " + position,
								Logger.DEBUG);
						NativeBuffer.this.notify();
						// for benefit of readers behind us
					}
					len -= maxlen;
					off += maxlen;

				}
				if (logDEBUG)
					Core.logger.log(
						this,
						"write("
							+ b
							+ ", written: "
							+ olen
							+ ", left: "
							+ len
							+ ") 2",
						Logger.DEBUG);
			}

			/**
			 * Current position in file
			 */
			public long position() {
				return position;
			}

			/**
			 * Filename relevant for the current status of the buffer.
			 */
			public File file() {
				return getFileForStatus();
			}
		}
	}

	private static final boolean verifyFile(File f) {
		if (f.length() <= 0)
			return false;
		if (f.lastModified() > System.currentTimeMillis())
			return false;
		try {
			String file = f.toString();
			Key key = Key.readKey(file.substring(file.lastIndexOf("-") + 1));
			Storables storables = new Storables();
			FileInputStream fIn = new FileInputStream(f);
			BufferedInputStream bIn = new BufferedInputStream(fIn);
			CountedInputStream cIn = new CountedInputStream(bIn);
			ReadInputStream rIn = new ReadInputStream(cIn);
			try {
				storables.parseFields(rIn);
				key.verifyStream(rIn, storables, f.length() - cIn.count());
			} catch (Throwable e) {
				return false;
			} finally {
				rIn.close();
			}
		} catch (Throwable e) {
			return false;
		}
		return true;
	}

	private static final void verifyDir(File f) {
		File[] files = f.listFiles();
		for (int y = 0; y < files.length; y++)
			if (files[y].isDirectory())
				verifyDir(files[y]);
			else if (!verifyFile(files[y]))
				System.out.println(files[y]);
	}

	public static void main(String[] args) throws freenet.KeyException {
		Key.addKeyType(freenet.keys.SVK.keyNumber, freenet.keys.SVK.class);
		Key.addKeyType(freenet.keys.CHK.keyNumber, freenet.keys.CHK.class);
		if (args.length == 0) {
			Config config = new Config();
			config.addOption("storeFile", 1, "store", 0);
			Params params = new Params(config.getOptions());
			try {
				String[] defaultRCfiles =
					new String[] {
						"freenet.conf",
						"freenet.ini",
						".freenetrc" };
				params.readParams(defaultRCfiles);
			} catch (Throwable e) {
			}
			String storeFile = params.getString("storeFile");
			System.out.println(
				"Verifying the integrity of all files in the store directory.  This may take");
			System.out.println(
				"a while.  Files listed below (if any) are not valid keys.  If you should find");
			System.out.println(
				"invalid files in your store that are not easily explained away, please report");
			System.out.println("to devl@freenetproject.org.");
			verifyDir(new File(storeFile));
		} else {
			System.out.println(
				"Verifying the integrity of the file or directory passed on the command line.");
			System.out.println(
				"This may take a while.  Files listed below (if any) are not valid keys.");
			File f = new File(args[0]);
			if (f.isDirectory()) {
				verifyDir(f);
			} else if (!verifyFile(f)) {
				System.out.println(f);
			}
		}
	}

}
