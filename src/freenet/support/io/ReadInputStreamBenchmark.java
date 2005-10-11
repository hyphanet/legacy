package freenet.support.io;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Simple benchmark for ReadInputStream.
 * <p>
 * USAGE: ReadInputStreamBenchmark seednodes.ref [time limit in seconds]
 * </p>
 * 
 * @author syoung
 */
public class ReadInputStreamBenchmark {

	public static void main(String[] args) throws IOException {

		// Parse command line arguments.
		if (args.length < 1 || args.length > 2) {
			printUsage();
			System.exit(1);
		}
		File inFile = new File(args[0]);

		// How long to run in seconds.
		int timeLimit = 10;
		if (args.length > 1) {
			try {
				timeLimit = Integer.parseInt(args[1]);
			} catch (NumberFormatException nfe) {
				System.err.println("ERROR: Second argument is not a number");
				printUsage();
				System.exit(1);
			}
		}
		System.out.println(
			"Benchmarking for "
				+ timeLimit
				+ " seconds with "
				+ inFile.length()
				+ " bytes... ");
		benchmark(inFile, timeLimit);
	}

	private static void benchmark(File inFile, long timeLimit)
		throws IOException {
		
		// Convert to milliseconds.
		timeLimit *= 1000;

		// Load the input file (suggest using seednodes.ref) into a byte array
		// to reduce the impact of disk performance.
		DataInputStream dis = new DataInputStream(new FileInputStream(inFile));
		byte[] inContents = new byte[(int) inFile.length()];
		dis.readFully(inContents);
		dis.close();
		ByteArrayInputStream bis = new ByteArrayInputStream(inContents);

		// And go.
		String s;
		int iterations = 0;
		long duration, start = System.currentTimeMillis();
		do {
			ReadInputStream ris = new ReadInputStream(bis);
			try {
				while ((s = ris.readToEOF('\n')) != null) {
					// Do nothing.
				}
			} catch (EOFException eof) {
				// No way to detect when at EOF other than by waiting for
				// the exception.
			}
			bis.reset();
			iterations++;

		}
		while ((duration = System.currentTimeMillis() - start) < timeLimit);

		// Show performance.
		System.out.println(
			"readToEOF: "
				+ (iterations * inContents.length / 1024) / (duration / 1000)
				+ " kb/s");

		// And go again.
		iterations = 0;
		start = System.currentTimeMillis();
		do {
			ReadInputStream ris = new ReadInputStream(bis);
			try {
				while (true) {
					ris.readUTFChar();
				}
			} catch (EOFException eof) {
				// No way to detect when at EOF other than by waiting for
				// the exception.
			}
			bis.reset();
			iterations++;

		}
		while ((duration = System.currentTimeMillis() - start) < timeLimit);

		// Show performance.
		System.out.println(
			"readUTFChar: "
				+ (iterations * inContents.length / 1024) / (duration / 1000)
				+ " kb/s");

		// And go again reading directly from the file without buffer. Oh the
		// humanity!
		iterations = 0;
		start = System.currentTimeMillis();
		do {
			FileInputStream fis = new FileInputStream(inFile);
			ReadInputStream ris = new ReadInputStream(fis);
			try {
				while (true) {
					ris.readUTFChar();
				}
			} catch (EOFException eof) {
				// No way to detect when at EOF other than by waiting for
				// the exception.
			}
			fis.close();
			iterations++;
		}
		while ((duration = System.currentTimeMillis() - start) < timeLimit);

		// Show performance.
		System.out.println(
			"readUTFChar unbuffered: "
				+ (iterations * inContents.length / 1024) / (duration / 1000)
				+ " kb/s");

		System.out.println("Done");
	}

	private static void printUsage() {
		System.err.println(
			"USAGE: ReadInputStreamBenchmark seednodes.ref [time limit in seconds]");
	}

}
