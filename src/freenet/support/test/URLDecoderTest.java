package freenet.support.test;
import junit.framework.*;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;

/**
 * A test class for the URLDecoder included with Freenet
 * @author mda
 */

public class URLDecoderTest extends TestCase {
    public static final void main(String [] args) {
        String [] foo = new String[] { URLDecoderTest.class.getName() };
        freenet.support.test.SimpleTestRunner.main(foo);
    } // End main() 

    public URLDecoderTest(String name) {
        super(name);
    } // End constructor 

    public void testURLDecoderIllegalStrings() {
        URLDecoder decoder = new URLDecoder();

        boolean exceptionThrown = false;
        
        // All of the following test cases should generate exceptions.
        String [] testCases = new String [] {
            "Illegal+hex+number+%XX",
            "Missing+hex+number+%",
            "Weird+hex+number+%-5",
            "Mess+with+number+parser+%+0",
            "Mess+with+number+parser+%+999999",
            "Zero+hex+number+%00",
        };

        for(int x=0; x<testCases.length; x++) {
            exceptionThrown = false;

            try {
                String result = URLDecoder.decode(testCases[x]);

                // Exception should have already been thrown.
                /*
                System.out.println("Huh??? Apparently \"" + testCases[x] + 
                                   "\" can be decoded into \"" + 
                                   result + "\"");
                */
            } catch(URLEncodedFormatException e) {
                exceptionThrown = true;
            } // End catch

            assertTrue("Decoding \"" + testCases[x] + "\" should throw an " +
                       "exception.", exceptionThrown);
        } // End for
    } // End testURLDecoderIllegalStrings()

    public void testURLDecoder() {
        URLDecoder decoder = new URLDecoder();
        
        String [] testCases = new String [] {
            "A+string+with+spaces+not+plusses",
            "Safe+character+test+$-_.!*'(),",
            "Illegal+character+test: {[<@#^&=\\|\">]}",
            "%42%43%44%45%46%47%48%49%50%51%52%53%54%55%56%57%58%59%60%61%62%63%64%65%66%67%68",
        };
        
        String [] expectedResults = new String [] {
            "A string with spaces not plusses",
            "Safe character test $-_.!*'(),",
            "Illegal character test: {[<@#^&=\\|\">]}",
            "BCDEFGHIPQRSTUVWXY`abcdefgh",
        };

        for(int x=0; x<testCases.length; x++) {
            String dec = "";
            
            try { 
                dec = URLDecoder.decode(testCases[x]);
            } catch(URLEncodedFormatException e) {
                System.out.println("Caught exception: " + e);
            } // End catch

            String msg = new String("Testing that \"" + 
                                    testCases[x] + "\" actually decodes to \""+
                                    expectedResults[x] + "\"");
            assertTrue(msg, expectedResults[x].equals(dec));
        } // End for
    } // End testURLDecoder
} // End class URLDecoder
