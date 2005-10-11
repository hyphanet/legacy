package freenet.support.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import junit.framework.TestCase;

/**
 * @author syoung
 */
public class WriteOutputStreamTest extends TestCase {

	public static void main(String[] args) {
		junit.textui.TestRunner.run(WriteOutputStreamTest.class);
	}

	public void testEmpty() throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		WriteOutputStream wos = new WriteOutputStream(bos);
		wos.writeUTF("");
		wos.close();
		byte[] result = bos.toByteArray();
		assertEquals(0, result.length);
	}

	public void testTwoByteCharacters() throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		WriteOutputStream wos = new WriteOutputStream(bos);
		wos.writeUTF("UTF\u00a9abc"); // 00a9 is the copyright symbol
		wos.close();

		byte[] result = bos.toByteArray();
		assertEquals(8, result.length);
		assertEquals('F', result[2]);
		assertEquals((byte) 0xc2, result[3]);
		assertEquals((byte) 0xa9, result[4]);
		assertEquals('a', result[5]);
	}

}
