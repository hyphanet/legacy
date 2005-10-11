package freenet.node.ds;

import freenet.*;
import freenet.fs.dir.*;
import freenet.support.Irreversible;
import freenet.support.Logger;
import freenet.support.io.*;
import java.io.*;

/**
 * Device to manage a storage entry.
 * @author tavin
 */
final class FSDataStoreElement {

	private final FSDataStore ds;

	private final Key key;
	private final Buffer buffer;

	private final long physLen;

	private int failureCode = -2;

	private boolean newData = false;

	private int users = 0;

	private static boolean logDebug = true;

	FSDataStoreElement(FSDataStore ds, Key key, Buffer buffer, long physLen) {
		this.ds = ds;
		this.key = key;
		this.buffer = buffer;
		this.physLen = physLen;
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDebug)
			Core.logger.log(
				this,
				"FSDataStoreElement initializing: "
					+ key.toString()
					+ ":"
					+ physLen,
				Logger.DEBUG);
	}

	public final String toString() {
		return "Key: " + key + " Buffer: " + buffer + " New: " + newData;
	}

	synchronized final KeyOutputStream getKeyOutputStream()
		throws IOException {
		newData = true;
		return new KeyOutputStreamImpl();
	}

	synchronized final KeyInputStream getKeyInputStream() throws IOException {
		return new KeyInputStreamImpl();
	}

	synchronized final void setFailureCode(int c) {
		failureCode = c;
		notifyAll();
	}

	synchronized final int getFailureCode() {
		if (failureCode == -2 && newData) {
			try {
				wait(30 * 60 * 1000);
			} catch (InterruptedException e) {
			}

			if (failureCode == -2) {
				System.err.println("WAITED FOR 30 MINUTES ON FAILURE CODE.");
			}
		}
		return failureCode;
	}

	synchronized final void release() {
		logDebug = Core.logger.shouldLog(Logger.DEBUG,this);
		if (logDebug)
			Core.logger.log(
				this,
				"release(): " + users + " still waiting for " + this,
				Logger.DEBUG);
		if (--users == 0) {
			buffer.release();
			if (logDebug)
				Core.logger.log(
					this,
					"Releasing underlying buffer for " + this,
					Logger.DEBUG);
		}
	}

	private final class KeyOutputStreamImpl extends KeyOutputStream {

		final OutputStream out;

		long bytesWritten = 0;

		boolean closed = false;

		boolean commit = false, rollback = false;
		
		boolean logDebug=true;

		public KeyOutputStreamImpl() throws IOException {
			out = buffer.getOutputStream();
			logDebug=Core.logger.shouldLog(Logger.DEBUG,this);
			++users;
		}

		public final void write(int b) throws IOException {
			out.write(b);
			++bytesWritten;
		}

		public final void write(byte[] buf, int off, int len)
			throws IOException {
			out.write(buf, off, len);
			bytesWritten += len;
		}

		public final void flush() throws IOException {
			out.flush();
		}

		public void close() throws IOException {
			if (!closed) {
				if (bytesWritten >= buffer.length())
					setFailureCode(-1); // succeeded
				try {
					if (bytesWritten != buffer.length()
						|| bytesWritten != physLen) {
						Core.logger.log(
							this,
							"Wrote: "
								+ bytesWritten
								+ " of "
								+ buffer.length()
								+ " (or "
								+ physLen
								+ ") for "
								+ this,
							Logger.MINOR);
						rollback = true;
					}
					out.close();
					closed = true;
				} finally {
					if ((!closed) || rollback) {
						closed = true;
						rollback = true;
						release();
					}
				}
			}
		}

		public final KeyInputStream getKeyInputStream() throws IOException {
			return FSDataStoreElement.this.getKeyInputStream();
		}

		public String toString() {
			return FSDataStoreElement.this.getClass().getName()
				+ ":"
				+ FSDataStoreElement.this.toString()
				+ ":"
				+ bytesWritten
				+ ":"
				+ (closed ? "closed" : "open")
				+ ":"
				+ (rollback ? "rollback:" : "")
				+ (commit ? "commit" : "");
		}

		public final void commit() throws IOException, KeyCollisionException {
			if (!closed) {
				throw new IllegalStateException(
					"cannot commit before closing: " + this);
			}
			if (!rollback && !commit) {
				synchronized (FSDataStoreElement.this) {
					try {
						if (logDebug)
							Core.logger.log(
								this,
								"committing key: " + key,
								Logger.DEBUG);

						synchronized (ds.dir.semaphore()) {
							if (ds
								.dir
								.contains(new FileNumber(key.getVal()))) {
								commit = true;
								setFailureCode(Presentation.CB_CANCELLED);
								if (logDebug)
									Core.logger.log(
										this,
										"collision: " + key,
										Logger.DEBUG);
								throw new KeyCollisionException();
							}
							buffer.commit(); // will sync to disk too
						}
						commit = true;
					} finally {
						release();
						if (!commit) {
							setFailureCode(Presentation.CB_CACHE_FAILED);
							Core.logger.log(
								this,
								"failed to store key: " + key,
								Logger.ERROR);
						}
					}
				}
			}
		}

		public final void rollback() {
			if (commit) {
				throw new IllegalStateException("already committed: " + this);
			}
			if (!rollback && closed) {
				release();
			}
			rollback = true;
		}

		public final void fail(int code) {
			if (logDebug)
				Core.logger.log(
					this,
					"Failing " + key + ": code=" + code,
					new Exception("failing"),
					Logger.DEBUG);
			rollback();
			setFailureCode(code);
		}

		protected final void finalize() throws Throwable {
			// I should have fixed what made this necessary, and it
			// may be causing trouble.
			//if (rollback)
			// Anyone still waiting needs to hear something!
			//    fail(Presentation.CB_CACHE_FAILED);
			if (!closed) {
				Core.logger.log(
					this,
					"FSDataStoreElement not closed in finalizer! - please report to devl@freenetproject.org if this happens frequently: "
						+ this,
					Logger.ERROR);
				if (!commit && !rollback) {
					rollback = true;
				}
				close();

			}
		}
	}

	private final class KeyInputStreamImpl extends KeyInputStream {

		final InputStream in;

		final long length;

		final Storables storables = new Storables();

		private final Irreversible closed = new Irreversible(false);

		private long read = 0;
		
		private boolean logDebug=true;

		public KeyInputStreamImpl() throws IOException {
			logDebug=Core.logger.shouldLog(Logger.DEBUG,this);
			if (logDebug)
				Core.logger.log(
					this,
					"KeyInputStreamImpl initializing",
					Logger.DEBUG);
			InputStream oin = buffer.getInputStream();
			if (oin == null)
				throw new IOException(
					"null stream from buffer "
						+ buffer
						+ ", probably already released ("
						+ this
						+ ")");
			/* main purpose of this buffer is to avoid one byte reads getting fields,
			   fields are never going to be _that_ long */
			in = new BufferedInputStream(oin, 8192);
			CountedInputStream cin = new CountedInputStream(in);
			try {
				storables.parseFields(new ReadInputStream(cin));
			} catch (IOException e) {
				in.close();
				closed.change();
				Core.logger.log(
					this,
					"IOException parsing fields",
					Logger.ERROR);
				throw e;
			}
			if (logDebug)
				Core.logger.log(
					this,
					"Read "
						+ cin.count()
						+ " into fields in "
						+ key
						+ ", "
						+ this,
					new Exception("debug"),
					Logger.DEBUG);
			length = buffer.length() - cin.count();
			if (logDebug)
				Core.logger.log(
					this,
					"Remaining: " + length + " bytes in " + key + ".",
					Logger.DEBUG);
			if (length < 0)
				throw new IllegalStateException("negative bytes remaining");
			++users;
		}

		public final int read() throws IOException {
			int res = in.read();
			if (res >= 0)
				read++;
			return res;
		}

		public final int read(byte[] buf, int off, int len)
			throws IOException {
			int res = in.read(buf, off, len);
			if (res > 0)
				read += res;
			return res;
		}

		public final int available() throws IOException {
			return in.available();
		}

		public final void close() throws IOException {
			if (closed.tryChange()) { //Do some stuff if we weren't already closed
				try {
					in.close();
				} finally {
					release();
				}
			}
		}

		public final long skip(long n) throws IOException {
			long res = in.skip(n);
			if (res >= 0)
				read += res;
			return res;
		}

		public final long length() {
			return length;
		}

		public final long realLength() {
			return buffer.realLength();
		}

		public final Storables getStorables() {
			return storables;
		}

		public final int getFailureCode() {
			return newData
				? FSDataStoreElement.this.getFailureCode()
				: Presentation.CB_CACHE_FAILED;
		}

		// Start some temp debug code. 
		private long id = 0;
		private freenet.MessageHandler mh = null;
		private String message;
		public void setParent(
			long id,
			freenet.MessageHandler mh,
			String message) {
			this.id = id;
			this.mh = mh;
			this.message = message;
		}
		// end temp debug code 

		protected final void finalize() throws Throwable {
			if (!closed.state()) {
				Core.logger.log(
					this,
					"Please close() me manually in finalizer: " + this,
					new IllegalStateException("unclosed"),
					Logger.ERROR);

				// Debug
				if (logDebug)
					Core.logger.log(this, "Message = " + message, Logger.DEBUG);
				if (mh != null)
					mh.printChainInfo(id, Core.logStream);
				else if (logDebug)
					Core.logger.log(this, "No info", Logger.DEBUG);

				// end debug

				close();
			}
		}

		public final String toString() {
			return FSDataStoreElement.this.toString()
				+ " ( "
				+ read
				+ " of "
				+ length
				+ " read)";

		}
	}
}
