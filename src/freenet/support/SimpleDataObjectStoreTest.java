package freenet.support;

import junit.framework.TestCase;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

import freenet.fs.dir.FileNumber;

/**
 * @author syoung
 */
public class SimpleDataObjectStoreTest extends TestCase {

	/**
	 * How long to run threaded tests for, in milliseconds. A few seconds is
	 * usually enough to reveal bugs but if you're active working on
	 * SimpleDataObjectStore you may want to let it run for a few hours.
	 */
	private final static int THREAD_DURATION = 5 * 1000;

	/** Class variable so it can be accessed by threads. */
	private SimpleDataObjectStore store;

	public static void main(String[] args) {
		junit.textui.TestRunner.run(SimpleDataObjectStoreTest.class);
	}

	/**
	 * Add data while flushing. Please, no flushing jokes.
	 */
	public void testConcurrentModification() throws IOException {
		File file = File.createTempFile("freenet", "junit");
		file.deleteOnExit();

		store = new SimpleDataObjectStore(new File[] { file, }, 0);

		Thread flusher = new Thread() {
			public void run() {
				while (!isInterrupted()) {
					try {
						store.flush();
					} catch (IOException ioe) {
						fail("IOException on flush");
					}
				}
			}
		};

		Thread setter = new Thread() {
			int number = 0;
			DataObject dataObject = new BlankDataObject();
			public void run() {
				while (!isInterrupted()) {
					store.set(
						new FileNumber(Integer.toHexString(number)),
						dataObject);
					number++;
				}
			}
		};

		flusher.start();
		setter.start();

		// Stop the threads after the duration.
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < THREAD_DURATION) {
			try {
				Thread.sleep(System.currentTimeMillis() - start);
			} catch (InterruptedException ie) {
				// Ignored.
			}
		}

		// Kill the threads.
		flusher.interrupt();
		setter.interrupt();
		try {
			flusher.join();
		} catch (InterruptedException ie) {
			// Ignored.
		}
		try {
			setter.join();
		} catch (InterruptedException ie) {
			// Ignored.
		}

		file.delete();
	}

	private class BlankDataObject implements DataObject {
		byte[] zeros = new byte[50];

		public void writeDataTo(DataOutputStream out) throws IOException {
			out.write(zeros);
		}

		public int getDataLength() {
			return zeros.length;
		}

	}
}
