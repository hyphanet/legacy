package freenet.support.io;
import java.io.*;

/**
 * this will hopefully fix FCP
 * <p>
 * FIXME: Pass a magnet over the section of your drive containing this file. It
 * needs to go away because ReadInputStream is doing The Right Thing returning
 * remaining data before throwing EOF, meaning the FCP code is probably
 * unfriendly. We need to be E O F'ing right.
 * </p>
 */
public final class EOFingReadInputStream extends ReadInputStream {

	public EOFingReadInputStream(InputStream i) {
		super(i);
	}

	/**
	 * overriden to throw
	 */
	public String readToEOF(char ends) throws IOException, EOFException {
		// 25 seems to be a good value, to reduce memory allocations
		StringBuffer tmp = new StringBuffer(26);
		char r = ' ';
		int read = 0;
		while (true) {
			//try {
			r = readUTFChar();
			read++;
			/*
			 * } catch (EOFException e) { if (tmp.length() > 0) return
			 * tmp.toString(); else throw e;
			 */
			if (r == -1) {
				if (tmp.length() > 0)
					return tmp.toString();
				else
					throw new EOFException();
			}
			if (r == ends) {
				break;
			}
			if (read > MAX_LENGTH) {
				if (tmp.length() > 0)
					return tmp.toString();
				else
					throw new EOFException();
			}
			tmp.append(r);
		}
		return tmp.toString();
	}

}
