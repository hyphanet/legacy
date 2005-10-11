package freenet.support.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import freenet.Core;
import freenet.support.Logger;
import freenet.support.UTF8;

/**
 * An OutputStream for writing to Freenet streams. This is like a simpler,
 * platform constant version of PrintStream (instead of the platforms encoding
 * and newlines, this always uses UTF8 and /n).
 * 
 * @author oskar
 */
public class WriteOutputStream extends FilterOutputStream {

	/**
	 * Creates a new WriteOutputStream
	 */
	public WriteOutputStream(OutputStream out) {
		super(out);
	}

	/**
	 * Writes the UTF bytes of the string to the output.
	 * 
	 * @param s
	 *            The String to write.
	 */
	public void writeUTF(String s) throws IOException {
		UTF8.write(this, s);
	}

	/**
	 * Writes the UTF bytes of the string to the output.
	 * 
	 * @param s
	 *            The String to write.
	 * @param term
	 *            The (line) terminator to write.
	 */
	public void writeUTF(String s, char term) throws IOException {
		writeUTF(s);
		UTF8.write(this, term);
	}

	/**
	 * Writes the UTF bytes of the string to the output.
	 * 
	 * @param s
	 *            The String to write.
	 * @param pre
	 *            The character to write before the terminator.
	 * @param term
	 *            The (line) terminator to write.
	 */
	public void writeUTF(String s, char pre, char term) throws IOException {
		writeUTF(s);
		UTF8.write(this, pre);
		UTF8.write(this, term);
	}

	/**
	 * The same as writeUTF(s) but this swallows the IOException. I'm leaving
	 * this since a lot of the code was written for printstream originally.
	 */
	public void print(String s) {
		try {
			writeUTF(s);
		} catch (IOException e) {
			Core.logger.log(
				this,
				"IOException when printing : " + e,
				Logger.DEBUG);
		}
	}

	/**
	 * The same as writeUTF(s,'\n') but this swallows the IOException. I'm
	 * leaving this since a lot of the code was written for printstream
	 * originally.
	 */
	public void println(String s) {
		try {
			writeUTF(s, '\n');
		} catch (IOException e) {
			Core.logger.log(
				this,
				"IOException when printing : " + e,
				Logger.DEBUG);
		}
	}

}
