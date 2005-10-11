package freenet.support;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import junit.framework.TestCase;

/**
 * @author syoung
 */
public class UTF8Test extends TestCase {

	public static void main(String[] args) {
		junit.textui.TestRunner.run(UTF8Test.class);
	}

	public void testSameAsGetBytes() throws IOException {
		String[] data = new String[] { "", "1", "abc", "UTF\u00a9abc", };
		for (int i = 0; i < data.length; i++) {
			byte[] result = UTF8.encode(data[i]);
			assertNotNull(result);
			assertTrue(Fields.byteArrayEqual(data[i].getBytes("UTF8"), result));
		}
	}
	
	public void testWriteWithLength() throws IOException {
		String str = "UTF\u00a9abc";
		
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(bos);
		dos.writeUTF(str);
		dos.flush();
		dos.close();
		byte[] dosResult = bos.toByteArray();
		
		bos.reset();
		UTF8.writeWithLength(bos, str);
		byte[] utf8Result = bos.toByteArray();
		
		assertTrue(Fields.byteArrayEqual(dosResult, utf8Result));
		
	}

	public void testGetEncodedLength() {
		String str = "UTF\u00a9abc";

		char[] chars = new char[str.length()];
		str.getChars(0, chars.length, chars, 0);

		int charEncLen = UTF8.getEncodedLength(chars);
		assertEquals(8, charEncLen);
		int strEncLen = UTF8.getEncodedLength(str);
		assertEquals(8, strEncLen);

	}

}
