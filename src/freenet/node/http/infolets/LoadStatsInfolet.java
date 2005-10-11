package freenet.node.http.infolets;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;

import freenet.node.Node;
import freenet.node.LoadStats;
import freenet.node.http.Infolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;

/**
 * @author ian
 */
public class LoadStatsInfolet extends Infolet {

    private LoadStats ls;

	public String longName() {
		return "Network Load";
	}

	public boolean visibleFor(HttpServletRequest req) {
		return SimpleAdvanced_ModeUtils.isAdvancedMode(req);
	}

	public String shortName() {
		return "loadstats";
	}

	public void init(Node n) {
        ls = n.loadStats;
	}

	public void toHtml(PrintWriter pw) {
		// DO NOT lock LoadStats while waiting for network I/O!
		StringWriter ssw = new StringWriter(2048);
		PrintWriter ppw = new PrintWriter(ssw);
        ls.dumpHtml(ppw);
		ppw.flush();
		pw.println(ssw.toString());
	}

}
