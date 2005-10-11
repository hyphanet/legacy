package freenet.support;

import java.io.File;
import java.io.IOException;

import freenet.Core;

/*
 * This code is part of fproxy, an HTTP proxy server for Freenet. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * Temporary Bucket Factory
 * 
 * @author giannij
 */
public class TempBucketFactory implements BucketFactory {
	private String tmpDir = null;

	private static class NOPHook implements TempBucketHook {
		public void enlargeFile(long curLength, long finalLength) {
		}
		public void shrinkFile(long curLength, long finalLength) {
		}
		public void deleteFile(long curLength) {
		}
		public void createFile(long curLength) {
		}
	}

	private final static TempBucketHook DONT_HOOK = new NOPHook();
	private static TempBucketHook hook = DONT_HOOK;
	private static boolean logDebug=true;

	public static long defaultIncrement = 4096;

	// Storage accounting disabled by default.
	public TempBucketFactory(String temp) {
		logDebug = Core.logger.shouldLog(Logger.DEBUG,this);
		tmpDir = temp;
		if (tmpDir == null)
			tmpDir = System.getProperty("java.io.tmpdir");
		//     Core.logger.log(this, "Creating TempBucketFactory, tmpDir = "+
		// 		    (tmpDir == null ? "(null)" : tmpDir),
		// 		    new Exception("debug"), Logger.DEBUG);
	}

	public TempBucketFactory() {
		this(System.getProperty("java.io.tmpdir"));
		if (logDebug)
			Core.logger.log(
				this,
				"Creating TempBucketFactory, tmpDir = "
					+ (tmpDir == null ? "(null)" : tmpDir),
				new Exception("debug"),
				Logger.DEBUG);
	}

	public Bucket makeBucket(long size) throws IOException {
		return makeBucket(size, 1.25F, defaultIncrement);
	}

	public Bucket makeBucket(long size, float factor) throws IOException {
		return makeBucket(size, factor, defaultIncrement);
	}

	/**
	 * Create a temp bucket
	 * 
	 * @param size
	 *            Default size
	 * @param factor
	 *            Factor to increase size by when need more space
	 * @return A temporary Bucket
	 * @exception IOException
	 *                If it is not possible to create a temp bucket due to an
	 *                I/O error
	 */
	public TempFileBucket makeBucket(long size, float factor, long increment)
		throws IOException {
		logDebug = Core.logger.shouldLog(Logger.DEBUG,this);
		File f = null;
		do {
			if (tmpDir != null) {
				f =
					new File(
						tmpDir,
						"tbf_"
							+ Long.toHexString(
								Math.abs(Core.getRandSource().nextInt())));
				if (logDebug)
					Core.logger.log(
						this,
						"Temp file in " + tmpDir,
						Logger.DEBUG);
			} else {
				f =
					new File(
						"tbf_"
							+ Long.toHexString(
								Math.abs(Core.getRandSource().nextInt())));
				if (logDebug)
					Core.logger.log(this, "Temp file in pwd", Logger.DEBUG);
			}
		} while (f.exists());

		//System.err.println("TEMP BUCKET CREATED: " + f.getAbsolutePath());
		//(new Exception("creating TempBucket")).printStackTrace();

		if (logDebug)
			Core.logger.log(
				this,
				"Temp bucket created: "
					+ f.getAbsolutePath()
					+ " with hook "
					+ hook
					+ " initial length "
					+ size,
				new Exception("creating TempBucket"),
				Logger.DEBUG);

		return new TempFileBucket(f, hook, size, increment, factor);
	}

	/**
	 * Free bucket
	 * 
	 * @param b
	 *            Description of the Parameter
	 */
	public void freeBucket(Bucket b) {
		if (b instanceof TempFileBucket) {
			if (logDebug)
				Core.logger.log(
					this,
					"Temp bucket released: "
						+ ((TempFileBucket) b).getFile().getAbsolutePath(),
					new Exception("debug"),
					Logger.DEBUG);
			if (!((TempFileBucket) b).release()) {
				System.err.println("Could not release temp bucket" + b);
				Core.logger.log(
					this,
					"Could not release temp bucket " + b,
					 new Exception("Failed to release tempbucket"),Logger.ERROR);
			}
		}
	}

	/**
	 * Sets the storage accounting hook.
	 * 
	 * @param t
	 *            The hook object to use to keep track of the amount of storage
	 *            used. It is legal for t to be null. In this case storage
	 *            accounting is disabled.
	 */
	public static void setHook(TempBucketHook t) {
		if (logDebug)
			Core.logger.log(
				TempBucketFactory.class,
				"Set TempBucketHook to " + t,
				Logger.DEBUG);
		hook = t;
		if (hook == null) {
			// Allow hooks to be disabled w/o sprinkling
			// if (hook != null) {/*blah blah */} calls
			// throughout the code.
			hook = DONT_HOOK;
			if (logDebug) {
				Core.logger.log(
					TempBucketHook.class,
					"TempBucketHook file usage management was disabled.",
					Logger.DEBUG);
			}
		}
	}
}
