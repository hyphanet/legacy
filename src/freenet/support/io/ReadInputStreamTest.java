package freenet.support.io;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import junit.framework.TestCase;

/**
 * @author syoung
 */
public class ReadInputStreamTest extends TestCase {

	public ReadInputStreamTest() {
		super();
	}

	public ReadInputStreamTest(String name) {
		super(name);
	}

	public static void main(String[] args) {
		junit.textui.TestRunner.run(ReadInputStreamTest.class);
	}

	/**
	 * Ensure the trailer is left intact. This is tornado-free software.
	 */
	public void testIntact() throws IOException {
		String str = "abcXdef";
		InputStream bis = new ByteArrayInputStream(str.getBytes());
		ReadInputStream ris = new ReadInputStream(bis);

		String head = ris.readToEOF('X');
		assertTrue("abc".equals(head));
		ris.close();

		assertEquals('d', bis.read());
		assertEquals('e', bis.read());
		assertEquals('f', bis.read());
	}

	public void testExpect() throws IOException {
		String[] strs = { "abc", "abcX", "abcXXXX", "abcX\n" };
		for (int i = 0; i < strs.length; i++) {
			String str = strs[i];
			InputStream bis = new ByteArrayInputStream(str.getBytes());
			ReadInputStream ris = new ReadInputStream(bis);

			String head = ris.readToEOF('X');
			assertTrue("abc".equals(head));
			ris.close();
		}
	}

	public void testEOF() throws IOException {
		String str = "abc";
		InputStream bis = new ByteArrayInputStream(str.getBytes());
		ReadInputStream ris = new ReadInputStream(bis);

		// Read to the end of stream then attempt to read past it.
		String head = ris.readToEOF('Z');
		assertTrue("abc".equals(head));
		try {
			ris.readToEOF('Z');
			assertTrue("Should have thrown an EOFException", false);
		} catch (EOFException eof) {
			// Good.
		}
		ris.close();
	}
}
