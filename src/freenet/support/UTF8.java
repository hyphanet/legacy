package freenet.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UTFDataFormatException;

/**
 * The UTF-8 text transfer format is used throughout Freenet. Because the
 * format is widely used this implementation has been optimized for
 * performance. An advantage over methods like <code>String.getBytes(encoding)</code>
 * is a UnsupportedEncodingException will never be thrown.
 * <p>
 * Unless the method is named <code>*WithCount</code> the characters are
 * encoded without two bytes length prefixed. The <code>*WithCount</code>
 * methods do the encoding like
 * {@link java.io.DataOutuptStream#readUTF() DataOutputStream.readUTF}.
 * </p>
 * <p>
 * See <a href="http://ietf.org/rfc/rfc2279.txt">RFC 2279</a> for details
 * about the UTF-8 transform format.
 * </p>
 * 
 * @author syoung
 */
public class UTF8 {

	private UTF8() {
	}

	/**
	 * Encode to UTF-8.
	 * 
	 * @param s
	 *            may not be <code>null</code>
	 * @return
	 */
	public static byte[] encode(String s) {
		if (s == null) {
			throw new IllegalArgumentException("Cannot convert null string to UTF-8");
		}

		// Shortcut for small sizes.
		int slen = s.length();
		if (slen == 0) {
			return new byte[0];
		} else if (slen == 1) {
			return encode(s.charAt(0));
		}

		byte[] result = new byte[getEncodedLength(s)];
		int pos = 0;
		for (int i = 0; i < slen; i++) {
			char c = s.charAt(i);
			if (c <= 0x007F) {
				result[pos++] = (byte) (c & 0xFF);
			} else if (c <= 0x07FF) {
				result[pos++] = (byte) (0xC0 | (c >> 6));
				result[pos++] = (byte) (0x80 | (c & 0x3F));
			} else {
				result[pos++] = (byte) (0xE0 | (c >> 12));
				result[pos++] = (byte) (0xC0 | (c >> 6));
				result[pos++] = (byte) (0x80 | (c & 0x3F));
			}
		}
		return result;
	}

	public static byte[] encode(char[] chars) {
		if (chars == null) {
			throw new IllegalArgumentException("Cannot convert null character array to UTF-8");
		}
		byte[] result = new byte[getEncodedLength(chars)];
		int pos = 0;
		for (int i = 0; i < chars.length; i++) {
			char c = chars[i];
			if (c <= 0x007F) {
				result[pos++] = (byte) (c & 0xFF);
			} else if (c <= 0x07FF) {
				result[pos++] = (byte) (0xC0 | (c >> 6));
				result[pos++] = (byte) (0x80 | (c & 0x3F));
			} else {
				result[pos++] = (byte) (0xE0 | (c >> 12));
				result[pos++] = (byte) (0xC0 | (c >> 6));
				result[pos++] = (byte) (0x80 | (c & 0x3F));
			}
		}
		return result;
	}

	public static byte[] encode(char c) {
		if (c <= 0x007F) {
			return new byte[] {(byte) (c & 0xFF)};
		} else if (c <= 0x07FF) {
			return new byte[] {
				(byte) (0xC0 | (c >> 6)),
				(byte) (0x80 | (c & 0x3F))};
		} else {
			return new byte[] {
				(byte) (0xE0 | (c >> 12)),
				(byte) (0xC0 | (c >> 6)),
				(byte) (0x80 | (c & 0x3F))};
		}
	}

	/**
	 * Calculate the number of bytes a string would require to be encoded as
	 * UTF-8.
	 * 
	 * @param s
	 *            may not be <code>null</code>
	 * @return
	 */
	public static int getEncodedLength(String s) {
		if (s == null) {
			throw new IllegalArgumentException("Cannot calculate UTF-8 length of null string");
		}

		int result = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c >= 0x0001) && (c <= 0x007F)) {
				result++;
			} else if (c > 0x07FF) {
				result += 3;
			} else {
				result += 2;
			}
		}
		return result;
	}

	/**
	 * Calculate the number of bytes an array of characters would require to be
	 * encoded as UTF-8.
	 * 
	 * @param chars
	 *            the bytes to encode, may not be <code>null</code>
	 */
	public static int getEncodedLength(char[] chars) {
		if (chars == null) {
			throw new IllegalArgumentException("Cannot calculate UTF-8 length of null array");
		}

		int result = 0;
		for (int i = 0; i < chars.length; i++) {
			char c = chars[i];
			if ((c >= 0x0001) && (c <= 0x007F)) {
				result++;
			} else if (c > 0x07FF) {
				result += 3;
			} else {
				result += 2;
			}
		}
		return result;
	}

	/**
	 * Write a character in UTF-8 format to any output stream.
	 * 
	 * @param out
	 * @param c
	 * @throws IOException
	 */
	public static void write(OutputStream out, char c) throws IOException {
		// Writing single bytes can be expensive but creating a tiny array
		// is just as painful and churns memory.
		if (c <= 0x007F) {
			out.write((byte) (c & 0xFF));
		} else if (c <= 0x07FF) {
			out.write((byte) (0xC0 | (c >> 6)));
			out.write((byte) (0x80 | (c & 0x3F)));
		} else {
			out.write((byte) (0xE0 | (c >> 12)));
			out.write((byte) (0xC0 | (c >> 6)));
			out.write((byte) (0x80 | (c & 0x3F)));
		}
	}

	/**
	 * @param out
	 * @param c
	 *            may not be <code>null</code>
	 * @throws IOException
	 */
	public static void write(OutputStream out, char[] c) throws IOException {
		if (c == null) {
			throw new IllegalArgumentException("Cannot convert null character array to UTF-8");
		}
		for (int i = 0; i < c.length; i++) {
			// FIXME do block write.
			UTF8.write(out, c[i]);
		}
	}

	/**
	 * Write a string as a sequence of UTF-8 characters as if writing each
	 * character of the string individually. Identical to <code>String.getBytes("UTF8")</code>.
	 * 
	 * @param out
	 * @param s
	 *            may not be <code>null</code>
	 * @throws IOException
	 */
	public static void write(OutputStream out, String s) throws IOException {
		if (s == null) {
			throw new IllegalArgumentException("Cannot convert null string to UTF-8");
		}

		// Shorcut small strings to avoid the penalty of creating a byte array
		// to write blockwise.
		int slen = s.length();
		if (slen < 15) {
			for (int i = 0; i < slen; i++) {
				write(out, s.charAt(i));
			}
			return;
		}

		out.write(encode(s));
	}

	/**
	 * Write a string as a sequence of UTF-8 characters prefix by a length.
	 * Identical to
	 * {@link java.io.DataOutuptStream#readUTF() DataOutputStream.readUTF}
	 * except with less resource usage.
	 * 
	 * @param out
	 * @param s
	 *            may not be <code>null</code>
	 * @throws IOException
	 */
	public static void writeWithLength(OutputStream out, String s)
		throws IOException {
		if (s == null) {
			throw new IllegalArgumentException("Cannot convert null string to UTF-8");
		}

		// Shorcut small strings to avoid the penalty of creating a byte array
		// to write blockwise.
		int slen = s.length();
		if (slen < 15) {
			int len = getEncodedLength(s);
			out.write((byte) ((len >>> 8) & 0xFF));
			out.write((byte) (len & 0xFF));
			for (int i = 0; i < slen; i++) {
				write(out, s.charAt(i));
			}
			return;
		}

		byte[] encoded = encode(s);
		if (encoded.length > 65535) {
			throw new UTFDataFormatException("Too many encoded bytes");
		}
		out.write((byte) ((encoded.length >>> 8) & 0xFF));
		out.write((byte) (encoded.length & 0xFF));
		out.write(encoded);

	}

}
