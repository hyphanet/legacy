package freenet.support.test;
import junit.framework.*;
import freenet.support.BoyerMoore;
import java.util.Vector;

/**
 * A test class for the URLDecoder included with Freenet
 * @author mda
 */

public class BoyerMooreTest extends TestCase {
    public static final void main(String [] args) {
        String [] foo = new String[] { BoyerMooreTest.class.getName() };
        freenet.support.test.SimpleTestRunner.main(foo);
    } // End main() 

    public BoyerMooreTest(String name) {
        super(name);
    } // End constructor

    public void testBoyerMoore() {
        BoyerMoore bm = new BoyerMoore();

        String [] patterns = new String [] {
            "FOOBAR",                                            
            "\\/",                                               
            "@@!@@",
            "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            "on",
            "no",
            "Match the whole string exactly",
            "en",
            "\n\r\t",
            "\"\"",
        };
        
        String [] haystack = new String [] {
            "FOOBARFOOBARFOOBAZFOOBAFOOBA",
            "\\\\\\\\/\\///\\/\\/",
            "@@!@!@@!!@@!@!!@!@@!@!@@!@@!!@@!@@@!@",
            "X",
            "Overlapping: ononononononononononononononon",
            "Overlapping: ononononononononononononononon",
            "Match the whole string exactly",
            "teneerFFreenet",
            "\n\r\n\r\t\t\n\r\t\n\r",
            "\"Quoted \"Text\"\"\"",
        };
        
        for(int x=0; x<patterns.length; x++) {
            String h = haystack[x];
            String p = patterns[x];

            // System.out.println("Searching for " + p + " in " + h);

            bm.compile(p);
            Vector results = bm.searchAll(h.getBytes(), 0, h.length());
            // System.out.print("Got " + results.size() + " results: ");

            /* Verify that results are true */
            for(int y=0; y<results.size(); y++) {
                Integer i   = (Integer)results.elementAt(y);
                int element = i.intValue();

                assertTrue("Real answer >= 0?", (element >= 0));

                int plen    = element + patterns[x].length();
                boolean c   = patterns[x].equals(h.substring(element, plen));

                // System.out.print(" " + element);
                assertTrue("Really found match?", c);
            } // End for

            // System.out.println();

            int partialMatch = bm.partialMatch();
            
            if(partialMatch > 0) {
                // System.out.println("Partial match found at end of string: "+
                //                    partialMatch);
                
                // A partial match must be less than the length of the string.
                // Otherwise it's not a partial match.
                assertTrue("Legal partial match?",
                           (partialMatch < p.length()));

                String hp = h.substring((h.length() - partialMatch), 
                                        h.length());
                String pp = p.substring(0, partialMatch);

                assertTrue("Found partial match @ end of string", 
                           hp.equals(pp));

                // NOTE: I was going to write a test here checking that no
                // larger partial match could be found at the end of the 
                // string.  I'm not doing this though because there's nothing
                // in the documentation that says the algorithm should return
                // the largest possible substring at the end of the string.
                // Example: when searching for "@@!@@" in 
                // "!@@@!@" the algorithm returns 1, which is correct, but
                // 4 would have also been correct.
            } // End if
        } // End for

    } // End testBoyerMoore()
} // End class BoyerMooreTest
