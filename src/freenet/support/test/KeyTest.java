package freenet.support.test;

import junit.framework.TestCase;
import freenet.Key;
import freenet.support.HexUtil;

/**
 * A test class for the Key class included with Freenet
 * 
 * @author M. David Allen <mda@idatar.com>
 */
public class KeyTest extends TestCase {
    public static final void main(String [] args) {
        String [] foo = new String[] { KeyTest.class.getName() };
        freenet.support.test.SimpleTestRunner.main(foo);
    } // End main() 

    public KeyTest(String name) {
        super(name);
    } // End constructor 

    public void testKeyComparisons() {
        String [] hexstrings = new String [] {
            // Randomly typed in hex strings.  The only pattern is that
            // the first digit increases with increasing index into the 
            // array.  That way we know which way they should compare.
            "0A131214FF1231249901001EEABBCDEFF0010013440111",
            "1213131949995528818148FBA9B9A939A9F9D9A9B9C911",
            "2229249108093510375135BF3113FB31CFC13BF1ACF1BA",
            "3ABCD80A890D8D098CD098B8BA080B8A8C8C8D098A8D8A",
            "4877A777B7A7C809806B55B5A5C5C5D5D5A5A44A44D4CA",
            "5A55A5D6D67B7B7B7F7FF7F7F7FF77F7F7FA7A7F7A7FA7",
            "6A8A5A4AA32A1AA900A09A8A7A6A5A4B44D4C44CD4D4CD",
            "79A6D979D79ADDB9ABCBAC76B9A7859A4C3B2A28C2A82B",
            "85C04C04B4BA404B40D4D40B0A49A8A4B8AB38DD8338BA",
            "9882AD38D93ADB39C9AB93AC82B217A718D83AD82B2A8D",
            "AAB94D38A27DA16DB712AD83DB9ABD39ADB9ABDABDABAD",
            "BBAB5D47ADB33BA2D126A7B3D84ABA5B9895DB59AB5BDA",
            "C33827616133489450B5D9A4D94A8BDA3DBA882DB27A8A",
            "D94AD49BDABD94A0B0D5ABD50BDA0449934DA93BD93C99",
            "E827271728398D934D993D93BB93C9C3B93AC9B9A9D92B",
            "F40A4D939AD39B93ADB389282D1B1D78293D33A90B987A"
        };

        Key [] keys = new Key [hexstrings.length];

        for(int x=0; x<hexstrings.length; x++) {
            // Create a bunch of new keys...
            keys[x] = new Key(HexUtil.hexToBytes(hexstrings[x]));
        } // End for

        // Now compare the keys every which way...
        for(int x=0; x<keys.length; x++) {
            int prev = x - 1;
            int next = x + 1;

            if(prev < 0)            prev = -1;
            if(next >= keys.length) next = -1;
            
            if(prev != -1 && next != -1) {
                // Key pair comparisons.
                assertTrue((keys[x] + " < " + keys[next]),
                           keys[x].compareTo(keys[next]) < 0);
                assertTrue((keys[next] + " > " + keys[x]),
                           keys[next].compareTo(keys[x]) > 0);
                assertTrue((keys[x] + " == " + keys[x]),
                           keys[x].compareTo(keys[x]) == 0);
            } else if(prev == -1 && next != -1) {
                assertTrue((keys[x] + " < " + keys[next]),
                           keys[x].compareTo(keys[next]) < 0);
                assertTrue((keys[next] + " > " + keys[x]),
                           keys[next].compareTo(keys[x]) > 0);
            } else if(prev != -1 && next == -1) {
                assertTrue((keys[x] + " > " + keys[prev]),
                           keys[x].compareTo(keys[prev]) > 0);
                assertTrue((keys[prev] + " < " + keys[x]),
                           keys[prev].compareTo(keys[x]) < 0);
            } else {
                // This can't happen.
            } // End else
        } // End for
    } // End testKeyComparisons()

    public void testKeys() {

        // What happens when we give it a bogus hex value?
        Key shouldFail = null;

        try {
            shouldFail = new Key("XD:ALKJF:ADSKNE@!#!#$!#&#&&#!*#**!#$!!!!!");
        } catch(NumberFormatException e) {  }
        
        assertTrue("Illegal hex string", shouldFail == null);
        shouldFail = null;

        try {
            shouldFail = new Key("");
        } catch(Exception e) {  }

        /* 
           assertTrue("Zero-length strings aren't good keys",
           (shouldFail == null));
        */
        assertTrue("Minimum size is non-zero", Key.LOG2_MINSIZE > 0);
        shouldFail = null;

        StringBuffer accumulator = new StringBuffer("F");
        
        // Create a hex string that's 1 bigger than the maximum size and then
        // feed that to the Key constructor.
        while(accumulator.length() <= Key.LOG2_MAXSIZE) {
            accumulator.append("F");
        } // End while

        try {
            shouldFail = new Key(HexUtil.hexToBytes(accumulator.toString()));
        } catch(Exception e) {  } 

        /* 
        assertTrue("Too big of a key",
                   ((accumulator.length() > Key.LOG2_MAXSIZE) &&
                    (shouldFail == null)));
        */
    } // End testKey
} // End class KeyTest
