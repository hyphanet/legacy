package freenet.support;

import java.util.Arrays;
import java.util.Random;

import junit.framework.TestCase;
import freenet.Core;
import freenet.crypt.Yarrow;

/**
 * Unit tests are tasty.
 * 
 * @author syoung
 */
public class HexUtilTest extends TestCase {

	private Random random;

	public static void main(String[] args) {
		junit.textui.TestRunner.run(HexUtilTest.class);
	}

	public void setUp() {
		if (Core.getRandSource() != null)
			random = Core.getRandSource();
		else
			random = new Yarrow();
	}

	public void tearDown() {
		random = null;
	}

	public void testHexBytes() {
		byte[][] vals = { new byte[] {
			},
				new byte[] { 0 },
				new byte[] { -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5 },
				new byte[] {(byte) 0x80, (byte) 0xff, 0x00, 0x01, 0x7f }
		};

		String[] strings = { "", "00", "fbfcfdfeff000102030405", "80ff00017f" };

		for (int i = 0; i < vals.length; i++) {
			assertEquals(
				"Testing encoding of array " + i,
				strings[i],
				HexUtil.bytesToHex(vals[i]));
			assertTrue(
				"Testing decoding of " + strings[i],
				Arrays.equals(vals[i], HexUtil.hexToBytes(strings[i])));
		}

		for (int i = 0; i < 1000; i++) {
			byte[] b = new byte[random.nextInt() & 0x3f];
			StringBuffer sb = new StringBuffer();
			for (int j = 0; j < b.length; j++) {
				b[j] = (byte) (random.nextInt() & 0xff);
				sb.append(b[j]);
				if (j < b.length - 1)
					sb.append(',');
			}
			assertTrue(
				"Checking encode/decode of array " + sb,
				Arrays.equals(b, HexUtil.hexToBytes(HexUtil.bytesToHex(b))));
		}
	}

}
