package freenet.node.http.infolets;

import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;

import freenet.node.Main;
import freenet.node.Node;
import freenet.node.http.Infolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;

/**
 * Infolet for the CLI manual page
 * 
 * @author ian
 */
public class ManualInfolet extends Infolet {
    
	public String longName() {
		return "Command Line Info";
	}

	public boolean visibleFor(HttpServletRequest req) {
		return SimpleAdvanced_ModeUtils.isAdvancedMode(req);
	}

	public String shortName() {
		return "cli";
	}

	public void init(Node n) {
	}

	public void toHtml(PrintWriter pw) {
		Main.manual(pw);
	}
}
