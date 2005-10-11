package freenet.node.http.infolets;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;

import freenet.Ticker;
import freenet.node.Node;
import freenet.node.http.Infolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;

/**
 * @author ian
 */
public class TickerContents extends Infolet {
    private Ticker tick;

    public String longName() {
		return "Pending Tasks";
	}

	public boolean visibleFor(HttpServletRequest req) {
		return SimpleAdvanced_ModeUtils.isAdvancedMode(req);
	}
	
	public String shortName() {
		return "tasks";
	}

	public void init(Node n) {
        tick = n.ticker();
	}

	public void toHtml(PrintWriter pw) {
		// DO NOT UNDER ANY CIRCUMSTANCES lock the Ticker while waiting for
		// network I/O!
		StringWriter ssw = new StringWriter(2048);
		PrintWriter ppw = new PrintWriter(ssw);
        tick.writeEventsHtml(ppw);
		ppw.flush();
		pw.println(ssw.toString());
	}
}
