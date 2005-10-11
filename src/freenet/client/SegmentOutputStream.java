package freenet.client;

import java.io.IOException;
import java.io.OutputStream;

import freenet.Core;
import freenet.client.events.ExceptionEvent;
import freenet.client.events.SegmentCompleteEvent;
import freenet.client.events.TransferCompletedEvent;
import freenet.client.events.TransferEvent;
import freenet.client.events.TransferFailedEvent;
import freenet.support.Bucket;
import freenet.support.Logger;
import freenet.support.NullBucket;

/**
 * An OutputStream which writes to a sequence of Buckets in turn, producing
 * TransferEvents, SegmentCompleteEvents, and finally TransferCompletedEvent.
 * 
 * @author tavin
 */
public class SegmentOutputStream extends OutputStream {

	private ClientEventProducer cep;
	private boolean ee; // produce events on exceptions?

	private Bucket[] buckets;
	private long[] lengths;
	private int index = 0;

	private Bucket currentBucket;
	private OutputStream currentStream;
	private long currentLength = 0;

	private long interval, // byte interval to produce TransferEvents
		written = 0, // bytes written so far
	total = 0; // total bytes to write

	private boolean closed = false;

	public String toString() {
		StringBuffer buf = new StringBuffer(125);
		buf
			.append(getClass().getName())
			.append(':')
			.append(cep)
			.append(',')
			.append(index)
			.append('/')
			.append(buckets.length)
			.append(",written=")
			.append(written)
			.append(",total=")
			.append(total)
			.append(",closed=")
			.append(closed)
			.append(",currentBucket=");
		if (currentBucket == null) {
			buf.append("null");
		} else {
			buf
				.append(currentBucket.getName())
				.append('[')
				.append(currentBucket.size())
				.append(']');
		}
		return buf.toString();
	}

	/**
	 * No events.
	 */
	public SegmentOutputStream(
		long interval,
		Bucket[] buckets,
		long[] lengths) {
		this(null, interval, buckets, lengths);
	}

	/**
	 * Transfer events but no exception events.
	 */
	public SegmentOutputStream(
		ClientEventProducer cep,
		long interval,
		Bucket[] buckets,
		long[] lengths) {
		this(cep, interval, buckets, lengths, false);
	}

	/**
	 * @param cep
	 *            the event producer to use when events are raised
	 * @param interval
	 *            the byte interval at which to produce TransferEvents
	 * @param buckets
	 *            the array of Buckets to write to in order
	 * @param lengths
	 *            the number of bytes to write to each Bucket
	 * @param ee
	 *            true to produce events on exceptions
	 */
	public SegmentOutputStream(
		ClientEventProducer cep,
		long interval,
		Bucket[] buckets,
		long[] lengths,
		boolean ee) {
		if (buckets.length == 0)
			throw new IllegalArgumentException("No buckets provided.");
		if (buckets.length > lengths.length)
			throw new IllegalArgumentException("There must be a length provided for each bucket.");
		this.cep = cep;
		this.ee = ee && (cep != null);
		this.interval = interval;
		this.buckets = buckets;
		this.lengths = lengths;
		for (int i = 0; i < buckets.length; i++) {
			total += lengths[i];
		}
	}

	boolean finished = false;

	private void doEvents(long inc) throws IOException {
		if (cep != null) {
			if (currentLength - inc == 0) {
				flush(); // Oh jesus.
				cep.produceEvent(new SegmentCompleteEvent(currentBucket));
			}

			if (written + inc >= total) {
				// REDFLAG: Oh jesus. Hacks to make InternalClient work.
				// There are two issues:
				// 0) InternalClient writes past the end of the
				//    retrieved document size. The InputStream
				//    used to source the data in SendData.receive()
				//    appears to be 0 padded to multiples of the
				//    part size. I worked around this problem
				//    by having nextBucket() return a NullBucket
				//    to catch the overflow.
				// 1) InternalClient uses the TransferCompletedEvent
				//    from the SegmentOutputStream to decide when
				//    to transition to the DONE state. The problem is
				//    that with the overflow bytes from problem 0)
				//    the InternalClient can transition to DONE
				//    before the OutputStream for the destination
				//    bucket is flushed. The code immediately below
				//    explicitly flushes and closes the OutputStream.
				//
				// Revisit these hacks if/when InternalClient is
				// cleaned up.
				currentStream.flush();
				currentStream.close();
				nextBucket();
				if (!finished)
					cep.produceEvent(new TransferCompletedEvent(total));
				finished = true;
			} else if (
				(interval > 0 && inc >= (interval - written % interval))
					&& (written < total)) { // No events past the end
				cep.produceEvent(new TransferEvent(written + inc));
			}
		}
	}

	public void write(int b) throws IOException {
		if (closed)
			throw new IOException("Attempt to write after closing.");
		try {
			if (currentLength == 0)
				nextBucket();
			currentStream.write(b);
			doEvents(1);
			++written;
			--currentLength;
			if (currentLength == 0)
				currentStream.close();
		} catch (IOException e) {
			if (ee)
				cep.produceEvent(new ExceptionEvent(e));
			throw (IOException) e.fillInStackTrace();
		}
	}

	public void write(byte[] buf, int off, int len) throws IOException {
		if (closed)
			throw new IOException("Attempt to write after closing.");
		try {
			while (len > 0) {
				if (currentLength == 0)
					nextBucket();
				int n = (int) Math.min(len, currentLength);
				currentStream.write(buf, off, n);
				off += n;
				len -= n;
				doEvents(n);
				written += n;
				currentLength -= n;
				if (currentLength == 0)
					currentStream.close();
			}
		} catch (IOException e) {
			if (ee)
				cep.produceEvent(new ExceptionEvent(e));
			throw (IOException) e.fillInStackTrace();
		}
	}

	public void flush() throws IOException {
		try {
			if (currentLength > 0) {
			    if(currentStream == null)
			        Core.logger.log(this, "currentStream null in "+this, new Exception("error"), Logger.ERROR);
			    else
			        currentStream.flush();
			}
		} catch (IOException e) {
			if (ee)
				cep.produceEvent(new ExceptionEvent(e));
			throw (IOException) e.fillInStackTrace();
		}
	}

	public void close() throws IOException {
	    if(!finished) {
	        cep.produceEvent(new TransferFailedEvent(total));
	        finished = true;
	    }
		try {
			if (!closed) {
				closed = true;
				if (currentLength > 0) {
				    if(currentStream == null)
				        Core.logger.log(this, "currentStream null in close on "+this, new Exception("error"), Logger.ERROR);
				    else
				        currentStream.close();
				}
			}
		} catch (IOException e) {
			if (ee)
				cep.produceEvent(new ExceptionEvent(e));
			throw (IOException) e.fillInStackTrace();
		}
	}

	private void nextBucket() throws IOException {
		do {
			Core.logger.log(this, "nextBucket() on " + this, Logger.DEBUG);
			if (index >= buckets.length) {
				Core.logger.log(
					this,
					"nextBucket() in deep water: " + this,
					Logger.DEBUG);
				// See notes in doEvents() before you change this.
				currentBucket =
					new freenet.support.NullBucket(Integer.MAX_VALUE);
				currentLength = Integer.MAX_VALUE;
				currentStream = currentBucket.getOutputStream();
				if(currentStream == null)
				    throw new NullPointerException();
				// throw new IOException(
				//                    "Attempt to write past last Bucket."
				// );
				return;

			}
			currentBucket = buckets[index];
			currentLength = lengths[index];
			if (currentBucket == null)
				currentBucket = new NullBucket(currentLength);
			// FIXME: could be more efficient by just handling the nulls
			++index;
			Core.logger.log(
				this,
				"nextBucket finished done normal forward: " + this,
				Logger.DEBUG);
		} while (currentLength == 0);

		currentStream = currentBucket.getOutputStream();
		if (currentStream == null)
			throw new NullPointerException();
	}
}
