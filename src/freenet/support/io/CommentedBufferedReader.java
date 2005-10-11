package freenet.support.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * This extends BufferedReader with the difference that <code>readLine()</code>
 * results that are only whitespace or start with the comment character are
 * ignored.
 * 
 * @author oskar
 */
public class CommentedBufferedReader extends BufferedReader {

	/**
	 * The characters that are considered comments.
	 */
	private final String commentChars;

	/**
	 * @param in
	 *            The stream to read from.
	 * @param commentChars
	 *            The characters that denote comments if they appear at the
	 *            beginning of a line (not counting whitespace)
	 */
	public CommentedBufferedReader(Reader in, String commentChars) {
		super(in);
		this.commentChars = commentChars;
	}

	public String readLine() throws IOException {
		String s;
		do {
			s = super.readLine();
		} while (
			s != null
				&& (s.trim().length() == 0
					|| commentChars.indexOf(s.trim().charAt(0)) != -1));
		return s;
	}
}
