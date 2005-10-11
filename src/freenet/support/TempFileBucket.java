package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import freenet.Core;
import freenet.support.io.NullInputStream;

/*
 *  This code is part of fproxy, an HTTP proxy server for Freenet.
 *  It is distributed under the GNU Public Licence (GPL) version 2.  See
 *  http://www.gnu.org/ for further details of the GPL.
 */
/**
 * Temporary file handling. TempFileBuckets start empty.
 *
 * @author     giannij
 */
public class TempFileBucket extends FileBucket {
	TempBucketHook hook = null;
	// How much we have asked the Hook to allocate for us
	long fakeLength = 0;
	long minAlloc;
	float factor;
	private static boolean logDebug = true;
	/**
	 * Constructor for the TempFileBucket object
	 *
	 * @param  f  File
	 */
	protected TempFileBucket(
		File f,
		TempBucketHook hook,
		long startLength,
		long minAlloc,
		float factor)
		throws IOException {
		super(f);
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		if (minAlloc > 0)
			this.minAlloc = minAlloc;
		else
			this.minAlloc = 1024;
		this.factor = factor;
		if (factor < 1.0)
			throw new IllegalArgumentException("factor must be >= 1.0");

		// Make sure finalize wacks temp file 
		// if it is not explictly freed.
		newFile = true;

		//System.err.println("FproxyServlet.TempFileBucket -- created: " +
		//         f.getAbsolutePath());
		this.hook = hook;
		long x = startLength <= 0 ? minAlloc : startLength;
		hook.createFile(x);
		this.fakeLength = x;
		if (logDebug)
			Core.logger.log(
				this,
				"Initializing TempFileBucket(" + f + "," + hook + ")",
				Logger.DEBUG);
	}

	/**
	 *  Gets the realInputStream attribute of the TempFileBucket object
	 *
	 * @return                  The realInputStream value
	 * @exception  IOException  Description of the Exception
	 */
	InputStream getRealInputStream() throws IOException {
		if (released)
			throw new IllegalStateException(
				"Trying to getInputStream on " + "released TempFileBucket!");
		if (logDebug)
			Core.logger.log(
				this,
				"getRealInputStream() for " + file,
				new Exception("debug"),
				Logger.DEBUG);
		if (!file.exists())
			return new NullInputStream();
		else
			return new HookedFileBucketInputStream(file);
	}

	/**
	 *  Gets the realOutputStream attribute of the TempFileBucket object
	 *
	 * @return                  The realOutputStream value
	 * @exception  IOException  Description of the Exception
	 */
	OutputStream getRealOutputStream() throws IOException {
		if (logDebug)
			Core.logger.log(
				this,
				"getRealOutputStream() for " + file,
				new Exception("debug"),
				Logger.DEBUG);
		return super.getOutputStream();
	}

	// Wrap non-const members so we can tell
	// when code touches the Bucket after it
	// has been released.
	/**
	 *  Gets the inputStream attribute of the TempFileBucket object
	 *
	 * @return                  The inputStream value
	 * @exception  IOException  Description of the Exception
	 */
	public synchronized InputStream getInputStream() throws IOException {
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDebug)
			Core.logger.log(this, "getInputStream for " + file, Logger.DEBUG);
		InputStream newIn = new SpyInputStream(this, file.getAbsolutePath());
		return newIn;
	}

	/**
	 *  Gets the outputStream attribute of the TempFileBucket object
	 *
	 * @return                  The outputStream value
	 * @exception  IOException  Description of the Exception
	 */
	public synchronized OutputStream getOutputStream() throws IOException {
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDebug)
			Core.logger.log(this, "getOutputStream for " + file, Logger.DEBUG);
		return new SpyOutputStream(this, file.getAbsolutePath());
	}

	/**  Description of the Method */
	public synchronized void resetWrite() {
		if (logDebug)
			Core.logger.log(this, "Resetting write for " + file, Logger.DEBUG);
		if (isReleased()) {
			throw new RuntimeException(
				"Attempt to use a released TempFileBucket: " + getName());
		}
		super.resetWrite();
	}

	/**
	 *  Release
	 *
	 * @return    Success
	 */
	public synchronized boolean release() {
		//System.err.println("FproxyServlet.TempFileBucket -- release: " +                      // file.getAbsolutePath());

		//System.err.println("CALL STACK: ");
		//(new Exception()).printStackTrace();

		// Force all open streams closed.
		// Windows won't let us delete the file unless we
		// do this.
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDebug)
			Core.logger.log(
				this,
				"Releasing TempFileBucket " + file,
				Logger.DEBUG);
		for (int i = 0; i < streams.size(); i++) {
			try {
				if (streams.elementAt(i) instanceof InputStream) {
					InputStream is = (InputStream) streams.elementAt(i);
					is.close();

					if (logDebug) {
						Core.logger.log(
							this,
							"closed open InputStream !: "
								+ file.getAbsolutePath(),
							new Exception("debug"),
							Logger.DEBUG);
						if (is instanceof FileBucketInputStream) {
							Core.logger.log(
								this,
								"Open InputStream created: ",
								((FileBucketInputStream) is).e,
								Logger.DEBUG);
						}
					}
				} else if (streams.elementAt(i) instanceof OutputStream) {
					OutputStream os = (OutputStream) (streams.elementAt(i));
					os.close();
					if (logDebug) {
						Core.logger.log(
							this,
							"closed open OutputStream !: "
								+ file.getAbsolutePath(),
							new Exception("debug"),
							Logger.DEBUG);
						if (os instanceof FileBucketOutputStream) {
							Core.logger.log(
								this,
								"Open OutputStream created: ",
								((FileBucketOutputStream) os).e,
								Logger.DEBUG);
						}

					}
				}
			} catch (IOException ioe) {
			}
		}
		if (logDebug)
			Core.logger.log(this, "Closed streams for " + file, Logger.DEBUG);
		if (released) {
			Core.logger.log(
				this,
				"Already released file: " + file.getName(),
				Logger.MINOR);
			if (file.exists())
				throw new IllegalStateException(
					"already released file "
						+ file.getName()
						+ " BUT IT STILL EXISTS!");
			return true;
		}
		if (logDebug)
			Core.logger.log(
				this,
				"Checked for released for " + file,
				Logger.DEBUG);
		released = true;
		if (file.exists()) {
			if (logDebug)
				Core.logger.log(
					this,
					"Deleting bucket " + file.getName(),
					Logger.DEBUG);
			if (!file.delete()) {
				Core.logger.log(
					this,
					"Delete failed on bucket " + file.getName(),
					new Exception(),
					Logger.ERROR);
				// Nonrecoverable; even though the user can't fix it it's still very serious
				return false;
			} else {
				if (hook != null)
					hook.deleteFile(fakeLength);
			}
		} else {
			if (hook != null)
				hook.deleteFile(fakeLength);
		}
		if (logDebug)
			Core.logger.log(
				this,
				"release() returning true for " + file,
				Logger.DEBUG);
		return true;
	}

	/**
	 *  Gets the released attribute of the TempFileBucket object
	 *
	 * @return    The released value
	 */
	public final synchronized boolean isReleased() {
		return released;
	}

	/**
	 *  Finalize
	 *
	 * @exception  Throwable  Description of the Exception
	 */
	public void finalize() throws Throwable {
		if (logDebug)
			Core.logger.log(
				this,
				"Finalizing TempFileBucket for " + file,
				Logger.DEBUG);
		super.finalize();
	}

	protected Vector streams = new Vector();
	private boolean released;

	protected FileBucketOutputStream newFileBucketOutputStream(
		String s,
		boolean append,
		long restartCount)
		throws IOException {
		if (logDebug)
			Core.logger.log(
				this,
				"Creating new HookedFileBucketOutputStream for " + file,
				Logger.DEBUG);
		if (hook != null)
			return new HookedFileBucketOutputStream(s, append, restartCount);
		else
			return super.newFileBucketOutputStream(s, append, restartCount);
	}

	protected void deleteFile() {
		if (logDebug)
			Core.logger.log(this, "Deleting " + file, Logger.DEBUG);
		file.delete();
		if (hook != null)
			hook.deleteFile(fakeLength);
	}

	protected synchronized void resetLength() {
		if (logDebug)
			Core.logger.log(this, "Resetting length for " + file, Logger.DEBUG);
		if (length != 0) {
			if (hook != null) {
				hook.shrinkFile(0, fakeLength);
				fakeLength = 0;
			}
			super.resetLength();
		}
	}

	protected final void getLengthSynchronized(long len) throws IOException {
		//       Core.logger.log(this, "getLengthSynchronized("+len+
		// 		      "); fakeLength = "+fakeLength, Logger.DEBUG);
		long l = fakeLength;
		long ol = l;
		while (len > l) {
			l = (long) (l * factor);
			if (minAlloc > 0)
				l = l + minAlloc - (l % minAlloc);
			if (l <= fakeLength)
				throw new IllegalStateException("Bucket extension error!");
			// 	  Core.logger.log(this, "l now "+l, Logger.DEBUG);
			if (ol == l)
				throw new IllegalStateException("infinite loop");
			ol = l;
		}
		if (fakeLength != l) {
			if (Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(
					this,
					"getLengthSynchronized("
						+ len
						+ "): increasing "
						+ fakeLength
						+ " to: "
						+ l
						+ " (real length: "
						+ length
						+ ")",
					Logger.DEBUG);
			hook.enlargeFile(fakeLength, l);
		}
		fakeLength = l;
	}
	
	public String toString(){
		return "TempFileBucket (File: '"+getFile().getAbsolutePath()+"', streams: "+streams.size()+", hook: "+hook+")";
	}

	class HookedFileBucketInputStream extends FileBucketInputStream {
		HookedFileBucketInputStream(File f) throws IOException {
			super(f);
			streams.addElement(this);
		}

		public void close() throws IOException {
			super.close();
			while (streams.remove(this));
		}
	}

	class HookedFileBucketOutputStream extends FileBucketOutputStream {

		protected HookedFileBucketOutputStream(
			String s,
			boolean append,
			long restartCount)
			throws IOException {
			super(s, append, restartCount);
			streams.addElement(this);
			if (Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(
					this,
					"Created HookedFileBucketOutputStream("
						+ s
						+ ","
						+ append
						+ ","
						+ restartCount
						+ ")",
					Logger.DEBUG);
		}

		public void close() throws IOException {
			super.close();
			while (streams.remove(this));
		}

		public void write(byte[] b) throws IOException {
			// 	  Core.logger.log(this, "HookedFileBucketOutputStream.write(byte[] len "+
			// 			  b.length+") for "+file, Logger.DEBUG);
			synchronized (TempFileBucket.this) {
				// 	      Core.logger.log(this, "Synchronized on TempFileBucket", 
				// 			      Logger.DEBUG);
				super.confirmWriteSynchronized();
				// 	      Core.logger.log(this, "confirmWriteSynchronized()", Logger.DEBUG);
				long finalLength = length + b.length;
				// 	      Core.logger.log(this, "length="+length+", finalLength="+finalLength,
				// 			      Logger.DEBUG);
				long realStartLen = fakeLength;
				getLengthSynchronized(finalLength);
				// 	      Core.logger.log(this, "Called hook.enlargeFile()", Logger.DEBUG);
				try {
					super.write(b);
					// 		  Core.logger.log(this, "Written", Logger.DEBUG);
				} catch (IOException e) {
					// 		  Core.logger.log(this, "Write failed", Logger.DEBUG);
					hook.shrinkFile(realStartLen, fakeLength);
					fakeLength = realStartLen;
					// 		  Core.logger.log(this, "Shrank file", Logger.DEBUG);
					throw e;
				}
			}
		}

		public void write(byte[] b, int off, int len) throws IOException {
			//  	  Core.logger.log(this, "HookedFileBucketOutputStream.write(byte[], "+off+
			//  			  ","+len+") for "+file, Logger.DEBUG);
			synchronized (TempFileBucket.this) {
				long finalLength = length + len;
				long realStartLen = fakeLength;
				getLengthSynchronized(finalLength);
				try {
					super.write(b, off, len);
				} catch (IOException e) {
					hook.shrinkFile(realStartLen, fakeLength);
					fakeLength = realStartLen;
					throw e;
				}
			}
		}

		public void write(int b) throws IOException {
			// 	  Core.logger.log(this, "HookedFileBucketOutputStream.write(int) for "+file,
			// 			  Logger.DEBUG);
			synchronized (TempFileBucket.this) {
				long finalLength = length + 1;
				long realStartLen = fakeLength;
				getLengthSynchronized(finalLength);
				try {
					super.write(b);
				} catch (IOException e) {
					hook.shrinkFile(realStartLen, fakeLength);
					fakeLength = realStartLen;
					throw e;
				}
			}
		}

	}

}
