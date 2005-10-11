package freenet.node.http.infolets;
import freenet.node.http.Infolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;
import freenet.node.Node;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;

public class NodeStatusInfolet extends Infolet  {
    public String longName()  {
        return "Node Status Interface";
    }
    
    public String shortName()  {
        return "nodestatus";
    }

    public void init(Node n)  { }
    
    public void toHtml(PrintWriter pw)  {
        pw.println("If this appears somethings gone wrong.");
    }
    
	public boolean visibleFor(HttpServletRequest req){
		return SimpleAdvanced_ModeUtils.isAdvancedMode(req);
	}

    public String target(String base, String container) {
	return "/servlet/" + shortName() + "/";
	// FIXME?
    }
}
