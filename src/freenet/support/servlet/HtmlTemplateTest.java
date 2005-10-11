package freenet.support.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import junit.framework.TestCase;

/**
 * Simple unit test for the HTML templating mechanism.
 * 
 * @author syoung
 */
public class HtmlTemplateTest extends TestCase {

	public static void main(String[] args) {
		junit.textui.TestRunner.run(HtmlTemplateTest.class);
	}

	public void testBasic() throws IOException {
		String body = "Body body oh no body, fee fi fo foddy.";
		String title = "Template Title";

		// Create template.
		HtmlTemplate simplePageTmp =
			HtmlTemplate.createTemplate("SimplePage.html");
		simplePageTmp.set("TITLE", title);
		simplePageTmp.set("BODY", body);

		// Best guess at how big the result will be.
		StringWriter strw = new StringWriter(5000);
		PrintWriter pw = new PrintWriter(strw);
		simplePageTmp.toHtml(pw);
		String result = strw.toString();
		pw.close();

		assertNotNull(result);
		assertTrue(result.indexOf(body) != -1);
		assertTrue(result.indexOf(title) != -1);
	}

	public void testShort() throws IOException {

		String[] cases = { "", "a", "ab", "abc", "-", "--", "##VALUE##" };
		for (int i = 0; i < cases.length; i++) {
			StringReader reader = new StringReader(cases[i]);
			HtmlTemplate template = new HtmlTemplate(reader);
			template.set("VALUE", "##VALUE##");

			StringWriter strw = new StringWriter(10);
			PrintWriter pw = new PrintWriter(strw);
			template.toHtml(pw);
			assertEquals(cases[i], strw.toString());
			pw.close();
		}
	}

}
