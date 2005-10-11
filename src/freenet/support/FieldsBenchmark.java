package freenet.support;

/**
 * One-time fields class benchmark with very rudimentary verification.  
 * Verifies the speed change relative to an original version.
 * 
 * @author syoung
 */
public class FieldsBenchmark {

	private static int baseIterations = 50000;

	public static void main(String[] args) {
		FieldsBenchmark app = new FieldsBenchmark();
		for (int i = 0; i < 10; i++) {
			app.benchmarkNumberList(baseIterations);
			app.benchmarkLongToHex(75 * baseIterations);
		}
	}
	
	private void benchmarkLongToHex(int iterations) {
		System.out.println("longToHex:");
		long longHex = 0xDEADBEEFBABEBEADL;

		// Verify both produce the same results.
		String strOrig = longToHexOriginal(longHex);
		String str = Long.toHexString(longHex);
		if (!str.equals(strOrig)) {
			throw new Error("longToHex validation failed!");
		}

		// Benchmark.
		long startOrig = System.currentTimeMillis();
		for (int i = 0; i < iterations; i++) {
			longToHexOriginal(longHex);
		}
		long endOrig = System.currentTimeMillis();
		System.out.println("    old: " + (endOrig - startOrig) + " ms");

		long start = System.currentTimeMillis();
		for (int i = 0; i < iterations; i++) {
			Long.toHexString(longHex);
		}
		long end = System.currentTimeMillis();
		System.out.println("    native: " + (end - start) + " ms");

		double improvement =
			(1 - (end - start) / (float) (endOrig - startOrig)) * 100;
		System.out.println("    change: " + improvement + " %");
	}

	private void benchmarkNumberList(int iterations) {
		System.out.println("numberList(long[]):");

		long longHex = 0xDEADBEEFBABEBEADL;
		long[] data = new long[100];
		for (int i = 0; i < data.length; i++) {
			data[i] = longHex;
		}

		// Verify both produce the same results.
		String strOrig = numberListOriginal(data);
		String str = Fields.numberList(data);
		if (!str.equals(strOrig)) {
			throw new Error("numberList validation failed!");
		}

		// Benchmark.
		long startOrig = System.currentTimeMillis();
		for (int i = 0; i < iterations; i++) {
			numberListOriginal(data);
		}
		long endOrig = System.currentTimeMillis();
		System.out.println("    old: " + (endOrig - startOrig) + " ms");

		long start = System.currentTimeMillis();
		for (int i = 0; i < iterations; i++) {
			Fields.numberList(data);
		}
		long end = System.currentTimeMillis();
		System.out.println("    new: " + (end - start) + " ms");

		double improvement =
			(1 - (end - start) / (float) (endOrig - startOrig)) * 100;
		System.out.println("    change: " + improvement + " %");
	}
	/**
	 * Converts a long into a hex String.
	 * 
	 * Equivalent to Long.toHexString(), but faster? FIXME syoung 2003-Dec-12:
	 * about 25% slower than Long.toHexString() in J2SDK 1.4.2_02 so it should
	 * be removed.
	 * 
	 * @param l
	 *            the long value to convert.
	 * @return A hex String.
	 */
	public String longToHexOriginal(long l) {
		StringBuffer sb = new StringBuffer(17);
		longToHexOriginal(l, sb);
		return sb.toString();
	}

	/**
	 * Converts a long into characters in a StringBuffer.
	 * 
	 * @param l
	 *            the long value to convert.
	 * @param sb
	 *            the StringBuffer in which to place the output. Note that if
	 *            there is existing data in the StringBuffer, it will end up
	 *            reversed and after the output!
	 */
	public void longToHexOriginal(long l, StringBuffer sb) {
		do {
			sb.append(Character.forDigit((int) l & 0xf, 16));
			/*
			 * Doing it manually makes this method about 4.8% faster here which
			 * I don't think justifies it... int i = (int) (l & 0xf); if (i
			 * < 10) sb.append((char) (i + '0')); else sb.append((char) (i +
			 * ('a' - 10)));
			 */
			l >>>= 4;

		} while (l != 0);
		sb.reverse();
	}

	/**
	 * Converts an int into a hex String. Equivalent to Integer.toHexString(),
	 * but faster?
	 * 
	 * @param l
	 *            the int value to convert.
	 * @return A hex String.
	 */
	public String intToHexOriginal(int l) {
		StringBuffer sb = new StringBuffer(9);
		do {
			sb.append(Character.forDigit(l & 0xf, 16));
			/*
			 * Doing it manually makes this method about 4.8% faster here which
			 * I don't think justifies it... int i = (int) (l & 0xf); if (i
			 * < 10) sb.append((char) (i + '0')); else sb.append((char) (i +
			 * ('a' - 10)));
			 */
			l >>>= 4;

		} while (l != 0);
		return sb.reverse().toString();
	}

	public String numberListOriginal(long[] ls) {
		StringBuffer sb = new StringBuffer(ls.length * 18);
		for (int i = 0; i < ls.length; i++) {
			sb.append(longToHexOriginal(ls[i]));
			if (i != ls.length - 1)
				sb.append(',');
		}
		return sb.toString();
	}

}
