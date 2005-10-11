package freenet;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import freenet.support.io.ReadInputStream;

import junit.framework.TestCase;

/**
 * @author syoung
 */
public class FieldSetTest extends TestCase {

	public FieldSetTest(String name) {
		super(name);
	}

	public static void main(String[] args) {
		junit.textui.TestRunner.run(FieldSetTest.class);
	}

	public void testOperations() {
		FieldSet fs = new FieldSet();

		assertEquals(0, fs.size());
		assertNull(fs.get(""));
		assertNull(fs.get("nonexistant"));
		assertNull(fs.get("doesnt.exist"));
		assertNull(fs.getSet("doesnt.exist"));

		fs.put("key", "value");
		assertEquals(1, fs.size());
		assertTrue("value".equals(fs.get("key")));
	}

	public void testReadEmptyStream() throws IOException {
		// FIXME Test disabled because ReadInputStream behaviour on end of file
		// makes it difficult for FieldSet to properly initialize with an empty
		// input stream.
		if (false) {
			ReadInputStream ris =
				new ReadInputStream(new ByteArrayInputStream(new byte[0]));
			FieldSet fs = new FieldSet(ris);
			ris.close();
		}
	}

	public void testRead() throws IOException {

		String simple =
			"one=1\na.b=a and b\n.b2.c=a and b and c\n\n";
		FieldSet fs =
			new FieldSet(
				new ReadInputStream(
					new ByteArrayInputStream(simple.getBytes())));
		assertEquals(2, fs.size());
		assertTrue("1".equals(fs.get("one")));

		FieldSet aSet = fs.getSet("a");
		assertNotNull(aSet);
		assertEquals(2, aSet.size());
		assertTrue("a and b".equals(aSet.getString("b")));

		FieldSet bSet = aSet.getSet("b2");
		assertNotNull(bSet);
		assertEquals(1, bSet.size());
		assertTrue("a and b and c".equals(bSet.getString("c")));

		// A blank line ends the FieldSet.
		String beforeTrailing = "\nshouldnt=be here\n";
		fs =
			new FieldSet(
				new ReadInputStream(
					new ByteArrayInputStream(beforeTrailing.getBytes())));
		assertEquals(0, fs.size());

	}

	// More complex case.
	public void testWithNodeData() throws IOException {
		String fields =
			"physical.tcp=foo.bar.cat.dog.blah.blah.example.com:52296"
				+ "\nARK.encryption=1234567890abcde123456782348175981758135135151353151351531351111"
				+ "\nARK.revision=1"
				+ "\nsignature=1234567890abcde123456782348175981758135135151353151235351531351111235612412142124"
				+ "\npresentations=3,1"
				+ "\nsessions=1"
				+ "\nidentity.y=1234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde1234567823481759817512351231231"
				+ "\nidentity.p=1234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde1234567823481759817512351231231"
				+ "\nidentity.g=1234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde123456782348175981751234567890abcde1234567823481759817512351231231"
				+ "\nidentity.q=1234567890abcde1234567823481759817512345"
				+ "\nversion=Fred,0.6,1.49,6430"
				+ "End\n\n";

		FieldSet fs =
			new FieldSet(
				new ReadInputStream(
					new ByteArrayInputStream(fields.getBytes())));
		assertEquals(7, fs.size());
		assertTrue("1".equals(fs.get("sessions")));

		FieldSet ark = fs.getSet("ARK");
		assertNotNull(ark);
		assertEquals(2, ark.size());
		assertTrue("1".equals(ark.get("revision")));

		FieldSet identity = fs.getSet("identity");
		assertNotNull(identity);
		assertEquals(4, identity.size());

		String str = fs.toString();
		assertNotNull(str);
		assertTrue(str.length() > 100);
	}

}
