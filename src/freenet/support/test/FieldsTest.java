package freenet.support.test;

import java.util.Random;

import junit.framework.TestCase;
import freenet.Core;
import freenet.crypt.Yarrow;
import freenet.support.Fields;

/**
 * Unit test for methods in Fields.
 *
 * @author oskar
 */
public class FieldsTest extends TestCase {

    public static final void main(String[] args) {
        SimpleTestRunner.main(
            new String[] { FieldsTest.class.getName() }
        );
    }
    
    private Random random;

    public FieldsTest(String name) {
        super(name);
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

    public void testLongs() {
        // typical numbers

        long[] vals = { 
            0, 1, -1, 2, 10, 16, 255, 99999, -99999, 1000000000,
            Long.MAX_VALUE, Long.MIN_VALUE
        };
        String[] strings = {
            "0", "1", "ffffffffffffffff","2","a","10","ff","1869f",
            "fffffffffffe7961", "3b9aca00", "7fffffffffffffff",
            "8000000000000000"
        };

        for (int i = 0; i < vals.length ; i++) {
            assertEquals("Testing decoding of " + strings[i], vals[i],
                         Fields.hexToLong(strings[i]));
        }

        // now for some random
        for (int i = 0 ; i < 1000 ; i++) {
            long l = random.nextLong();
            assertEquals("Checking encode/decode of " + l,
                         l, Fields.hexToLong(Long.toHexString(l)));
        }

        // check failure
        try {
            Fields.hexToLong("xxx");
            fail("Didn't throw NFE on decoding 'xxx'");
        } catch (NumberFormatException e) {
        }

        try {
            Fields.hexToLong("112233445566778899");
            fail("Didn't throw NFE on decoding 18 characters");
        } catch (NumberFormatException e) {
        }
    }
  
    private boolean arrayEquals(byte[] a, byte[] b) {
        if (a.length != b.length)
            return false;
        for (int i = 0 ; i < a.length ; i++) {
            if (a[i] != b[i])
                return false;
        }
        return true;
    }
    
}
