package freenet.client;

import java.net.MalformedURLException;

import junit.framework.TestCase;

/**
 * @author syoung
 */
public class FreenetURITest extends TestCase {

	public static void main(String[] args) {
		junit.textui.TestRunner.run(FreenetURITest.class);
	}

	public void testParse() {

		String[] noExceptions =
			{
				"freenet:KSK@foo//bar",
				"freenet:KSK@test.html",
				"freenet:test.html",
				"freenet:SSK@OR360C6NBt9yCgNRjowEzSQK05YPAgM/moviecentral/28//#f064555d2793c6a42c1dc1f39d13bd32" };
		for (int i = 0; i < noExceptions.length; i++) {
			// Just checking that it doesn't throw any exceptions parsing
			// legal values.
			try {
				new FreenetURI(noExceptions[i]);
			} catch (MalformedURLException mue) {
				assertTrue("Error parsing freenet URI", false);
			}
		}

	}

}