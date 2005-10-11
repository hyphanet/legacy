package freenet;

import java.io.OutputStream;
import java.io.IOException;

import freenet.support.Logger;

public class TrailerWriterOutputStream
	extends OutputStream
	implements TrailerWriteCallback {

	private TrailerWriter tw;
	private volatile boolean finishedSend = false;
	private volatile boolean succeeded = false;
	private Object overallSync = new Object();

	public TrailerWriterOutputStream(TrailerWriter tw) {
		if (tw == null)
			throw new NullPointerException();
		this.tw = tw;
	}

	public void close() {
		synchronized (overallSync) {
			tw.close();
		}
	}

	public void flush() {
	    // No buffer, no flush
	}

	public long bytesAvailable() {
		return -1;
	}

	public void write(byte[] b, int offset, int length) throws IOException {
		synchronized (overallSync) {
			finishedSend = false;
			try {
				tw.writeTrailing(b, offset, length, this);
			} catch (TrailerException e) {
				IOException ioe = new IOException(e.toString());
				ioe.initCause(e);
				throw ioe;
			}

			long now = System.currentTimeMillis();
			synchronized (this) {
				while (!finishedSend) {
					try {
						this.wait(200);
					} catch (InterruptedException e) {
					    // Don't care
					}
					if (System.currentTimeMillis() - now > 5 * 60 * 1000) {
						Core.logger.log(
							this,
							"Trailer write took longer than 5 minutes for "
								+ this,
							new Exception("debug"),
							Logger.NORMAL);
						throw new IOException("Trailer write took longer than 5 minutes");
					}
				}
			}
			if (!succeeded)
				throw new IOException("Trailed send failed");
		}
	}

	private byte[] oneByteBuf = new byte[1];
	public void write(int b) throws IOException {
		oneByteBuf[0] = (byte) (b & 0xff);
		write(oneByteBuf, 0, 1);
	}

	public void write(byte[] buf) throws IOException {
		write(buf, 0, buf.length);
	}

	public void closed() {
		succeeded = false;
		finishedSend = true;
		synchronized (this) {
			this.notify();
		}
	}

	public void written() {
		succeeded = true;
		finishedSend = true;
		synchronized (this) {
			this.notify();
		}
	}
}
