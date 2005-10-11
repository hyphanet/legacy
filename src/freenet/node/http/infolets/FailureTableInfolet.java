package freenet.node.http.infolets;
import freenet.node.http.Infolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;
import freenet.node.Node;
import freenet.node.FailureTable;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;

/**
 * Prints the contents of the FailureTable.
 */

public class FailureTableInfolet extends Infolet {

    private FailureTable ft;

    public String longName() {
        return "Failure Table";
    }

    public String shortName() {
        return "ftable";
    }

    public void init(Node n) {
        ft = n.ft;
    }
    
    public boolean visibleFor(HttpServletRequest req){
	return SimpleAdvanced_ModeUtils.isAdvancedMode(req);
    }
    
    public void toHtml(PrintWriter pw) {
        pw.println("<h3>Table of recently failed keys.</h3>");
        pw.println("The node keeps a list of keys that recently could not be "
                   + "found, and automatically ends requests for them with " 
                   + "the same or lower HTL.<br /><br />");
	// DO NOT lock FT while waiting for network I/O
	StringWriter ssw = new StringWriter(2048);
	PrintWriter ppw = new PrintWriter(ssw);
        ft.writeHtml(ppw);
	ppw.flush();
	pw.println(ssw.toString());
    }
}

