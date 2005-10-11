package freenet.support.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import junit.framework.TestCase;
import freenet.Core;
import freenet.Key;
import freenet.support.HexUtil;
import freenet.support.KeyList;

/**
 * A simple unit test class for the KeyList.
 */
public class KeyListTest extends TestCase {
    
    public static final void main(String[] args) {
        SimpleTestRunner.main(
            new String[] { KeyListTest.class.getName() }
        );
    }

    
    private KeyList kl;
    private int coreBlockSize;

    public KeyListTest(String name) {
        super(name);
    }

    public void setUp() {
        kl = new KeyList(new Key[] {
            new Key("bbbbbbbbbbbb"),
            new Key("aaaaaaaaaaaa"),
            new Key("cccccccccccc"),
            new Key("dddddddddddd")
        });
        coreBlockSize = Core.blockSize;
        Core.blockSize = 256;
    }

    public void tearDown() {
        kl = null;
        Core.blockSize = coreBlockSize;
    }

    public void testOutput() {
        String expected = 
            "bbbbbbbbbbbb\n" +
            "aaaaaaaaaaaa\n" +
            "cccccccccccc\n" +
            "dddddddddddd\n";
        assertEquals("Testing list size", 4, kl.size());
        assertEquals("Testing length", expected.length(), kl.streamLength());
        assertEquals("Testing writeTo output", expected, getOutput());
    }

    public void testSort() {
        String expected = 
            "aaaaaaaaaaaa\n" +
            "bbbbbbbbbbbb\n" +
            "cccccccccccc\n" +
            "dddddddddddd\n";
        
        kl.sort();
        assertEquals("Testing forward sorting", expected, getOutput());
        expected = 
            "dddddddddddd\n" +
            "cccccccccccc\n" +
            "bbbbbbbbbbbb\n" +
            "aaaaaaaaaaaa\n";
        kl.setCompareBase(new Key("eeeeeeeeeeee"));
        kl.sort();
        assertEquals("Testing backward sorting", expected, getOutput());

    }

    public void testHashing() {
        // precomputed.
        String expected = "865f09ac7ee1abb97efb3e8c7bda42b784dc7bca";
        String actual = 
            HexUtil.bytesToHex(kl.cumulativeHash(freenet.crypt.SHA1.getInstance()));
        assertEquals("Testing hash value", expected, actual);
    }

    public void testXOR() {
        byte[] b = { 0x01, 0x01, 0x01, 0x01, 0x01, 0x01};
        String expected = HexUtil.bytesToHex(b);
        kl.xorTotal(b);
        // the chosen numbers really do hash XOR to nothing, try it...
        assertEquals("Testing XOR values pre sorting", expected, 
                     HexUtil.bytesToHex(b));
        kl.sort();
        kl.xorTotal(b);
        assertEquals("Testing that sorting didn't effect XOR", expected,
                     HexUtil.bytesToHex(b));
    }

    public void testPrune() {
        kl.addEntry(new Key("aaaaaaaaaaaa"));
        kl.sort();
        assertEquals("Testing number of entries before prune", 5 , kl.size());
        kl.prune();
        assertEquals("Testing number of entries after prune", 4, kl.size());

    }

    private String getOutput() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            kl.writeTo(out);
            out.close();
            return out.toString("UTF8");
        } catch (IOException e) {
            fail("Exception when writing stream: " + e);
            return ""; // actually fail throws an Error...
        }
    }
}
