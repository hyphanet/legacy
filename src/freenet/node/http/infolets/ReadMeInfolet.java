package freenet.node.http.infolets;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;

import freenet.Core;
import freenet.node.Node;
import freenet.node.http.DistributionServlet;
import freenet.node.http.Infolet;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;

/**
 * Infolet for the README file
 * 
 * @author amphibian
 */
public class ReadMeInfolet extends Infolet {
	public String longName() {
		return "README file";
	}

	public boolean visibleFor(HttpServletRequest req) {
		return true;
	}

	public String shortName() {
		return "readme";
	}

    public void init(Node n) { }

	public void toHtml(PrintWriter pw) {
		String key = DistributionServlet.keys[DistributionServlet.README];
		File f = DistributionServlet.files[DistributionServlet.README];
		boolean readIt = false;
		if (f != null && f.canRead() && f.length() != 0) {
			pw.println("<pre>");
			try {
				FileInputStream is = new FileInputStream(f);
				InputStreamReader r = new InputStreamReader(is);
				char[] c = new char[4096];
				int x = 0;
				while ((x = r.read(c)) > 0) {
					String s = new String(c, 0, x);
					s = HTMLEncoder.encode(s);
					pw.print(s);
				}
				readIt = true;
				pw.println("</pre>");
			} catch (IOException e) {
				Core.logger.log(
					this,
					"IOException writing ReadMe: " + e,
					e,
					Logger.MINOR);
				pw.println("</pre>Could not read README");
				readIt = false;
			}
		}
		if (!readIt) {
			pw.println(
				"Click <a href=\"/"
					+ key
					+ "?mime=text/plain\">here</a>"
					+ " to read the README file.");
		}
		pw.flush();
	}
}
