package freenet.support.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * This extends ReadInputStream with the difference that readTo() results that
 * are only whitespace or start with the comment character are ignored.
 * 
 * @author oskar
 */
public class CommentedReadInputStream extends ReadInputStream {

	/**
	 * The characters that are considered comments.
	 */
	private final String commentChars;

	/**
	 * @param i
	 *            The inputstream to read from.
	 * @param comment
	 *            The characters that denote comments if they appear at the
	 *            beginning of a line (not counting whitespace);
	 */
	public CommentedReadInputStream(InputStream i, String comment) {
		super(i);
		this.commentChars = comment;
	}

	public String readTo(char ends) throws IOException, EOFException {
		String s;
		do {
			s = super.readTo(ends);
		} while (
			s.trim().length() == 0
				|| commentChars.indexOf(s.trim().charAt(0)) != -1);
		return s;
	}

	public String readToEOF(char ends) throws IOException, EOFException {
		String s;
		do {
			s = super.readToEOF(ends);
		} while (
			s.trim().length() == 0
				|| commentChars.indexOf(s.trim().charAt(0)) != -1);
		return s;
	}
}
