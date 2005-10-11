package freenet.support.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

/**
 * A template of text into which values in a Dictionary may be inserted. The
 * template is read from an InputStream and everything is pre-processed to make
 * the actual generation of the complete document as rapid as possible. Values
 * to be replaced in the template should be in the format <code>##VALUE##</code>.
 * Behavior is undefined if this syntax is abused (eg. having an odd number of
 * '##' in the template).
 * <p>
 * If a value that does not exist is replaced with an empty string.
 * </p>
 * <p>
 * Code using this class probably won't be thread safe (unless you create a new
 * HtmlTemplate every time you render the page which would be very inefficient)
 * so synchronize accordingly.
 * </p>
 * 
 * @author Ian Clarke
 */
public class HtmlTemplate implements TemplateElement {

	/** The default directory in which templates are stored */
	public final static String TEMPLATEDIR = "/freenet/node/http/templates/";

	public static String defaultTemplateSet = "aqua";

	/**
	 * Returns a new template
	 * 
	 * @param template
	 *            The name of a template within Freenet's template directory
	 * @return The newly created template
	 * @exception IOException
	 */
    public static HtmlTemplate createTemplate(String template) throws IOException {
        return new HtmlTemplate(TEMPLATEDIR + defaultTemplateSet + "/" + template);
	}

	/**
	 * A list of Fragments, each of which can either be a plain piece of text,
	 * or a variable to be replaced in the template
	 */
	private List fragments = new ArrayList();

	/**
	 * A hashtable which stores the desired Strings or TemplateElements for
	 * each given variable
	 */
    private final Map d;

	/**
	 * Construct an HtmlTemplate by cloning another one. We keep fragments
	 * exactly as is, except that d is deep copied.
	 */
	public HtmlTemplate(HtmlTemplate source) {
		fragments = source.fragments;
		d = new HashMap(source.d);
	}

	/**
	 * Constructor for the HtmlTemplate object
	 * 
	 * @param resource
	 * @exception IOException
	 */
	public HtmlTemplate(String resource) throws IOException {
		d = new HashMap();
		InputStream resourceStream = getClass().getResourceAsStream(resource);
		Reader reader;
		if (resourceStream != null) {
			reader = new InputStreamReader(resourceStream);
		} else {
			reader = new StringReader("Error reading HTML template '" + resource + "'");
		}
		read(reader);
		reader.close();
	}

	/**
	 * Constructor for the HtmlTempate object
	 * 
	 * @param template
	 * @exception IOException
	 */
	public HtmlTemplate(Reader template) throws IOException {
    	d = new HashMap();
		read(template);
	}

	/**
	 * Constructor for the HtmlTemplate object
	 * 
	 * @param template
	 * @exception IOException
	 */
	public void read(Reader template) throws IOException {

		// Parse the template into the fragments vector
		BufferedReader bTemplate = new BufferedReader(template);

		// We use a and b to keep track of the last and second last characters
		// to be read from the stream
		int a = bTemplate.read();
		int b = bTemplate.read();

		// The ele bool is true if and only if we are reading a ##variable##
		// rather than plain text.
		boolean ele = false;

		// Now parse through the string
		StringBuffer sb = new StringBuffer(5000);
		while (a != -1) {

			// If the last two characters read were "##" then...
			if ((a == '#') && (b == '#')) {

				String s = new String(sb.toString());
				//Detach the value we want (and not a single byte more) from
				// the sb:s underlying char[]
				if (ele) {
					// We just finished reading a variable
					fragments.add(new VarFragment(s));
				} else {
					// We just finished reading a string
					fragments.add(new StringFragment(s));
				}

				sb.setLength(0);

				ele = !(ele);

				// Read next pair of charactedrs
				a = bTemplate.read();
				// Handle EOF
				if (a == -1) {
					break;
				}
				b = bTemplate.read();
				if (b == -1) {
					break;
				}
			} else {
				// Add this char to our StringBuffer and read the next one.
				sb.append((char) a);
				a = b;
				b = bTemplate.read();
			}
		}

		if (ele) {
			fragments.add(new VarFragment(sb.toString()));
		} else {
			fragments.add(new StringFragment(sb.toString()));
		}
		sb.setLength(0);
	}

	/**
	 * Set a variable to a String value
	 * 
	 * @param var
	 *            The variable to set
	 * @param val
	 *            The value to set it to
	 */
	public void set(String var, String val) {
		d.put(var, val);
	}

	/**
	 * Set a variable to the output of a TemplateElement
	 * 
	 * @param var
	 *            The variable to set
	 * @param val
	 *            The TemplateElement which will produce the output to replace
	 *            the variable in the template.
	 */
	public void set(String var, TemplateElement val) {
		d.put(var, val);
	}

	/**
	 * Description of the Method
	 * 
	 * @param var
	 * @param val
	 * @param req
	 */
	public void set(String var, TemplateElement val, HttpServletRequest req) {
		set(var, val);
		d.put(var + "#req", req);
	}

	/**
	 * @param pw
	 */
	public void toHtml(PrintWriter pw) {
		toHtml(pw, null);
	}

	/**
	 * @param pw
	 * @param hsr
	 */
	public void toHtml(PrintWriter pw, HttpServletRequest hsr) {
		for (Iterator iter = fragments.iterator(); iter.hasNext();) {
			((Fragment) iter.next()).toHtml(pw, hsr, d);
		}
	}

	/**
	 * @author ian
	 */
	private interface Fragment {

		/**
		 * @param pw
		 * @param d
		 * @param hsr
		 */
		public void toHtml(PrintWriter pw, HttpServletRequest hsr, Map d);

	}

	/**
	 * @author ian
	 */
	private class StringFragment implements Fragment {

		private String s;

		/**
		 * @param s
		 */
		public StringFragment(String s) {
			this.s = s;
		}

		/**
		 * @param pw
		 * @param d
		 * @param hsr
		 */
		public void toHtml(PrintWriter pw, HttpServletRequest hsr, Map d) {
			pw.print(s);
		}

	}

	/**
	 * @author ian
	 */
	private class VarFragment implements Fragment {

		private String v;

		/**
		 * @param v
		 */
		public VarFragment(String v) {
			this.v = v;
		}

		/**
		 * @param pw
		 * @param d
		 * @param hsr
		 */
		public void toHtml(PrintWriter pw, HttpServletRequest hsr, Map d) {
			Object o = d.get(v);
			if (o == null) {
				// Do nothing
			} else if (o instanceof TemplateElement) {
				HttpServletRequest req = (HttpServletRequest) d.get(v + "#req");
				if (req == null) {
					((TemplateElement) o).toHtml(pw);
				} else {
					((TemplateElement) o).toHtml(pw, req);
				}
			} else {
				pw.print((String) o);
			}
		}

	}

}
