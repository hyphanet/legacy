package freenet.node.http.infolets;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;

import freenet.OpenConnectionManager;
import freenet.node.Node;
import freenet.node.http.MultipleFileInfolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;

public class OpenConnections extends MultipleFileInfolet {

    private OpenConnectionManager connections;

    public String longName() {
        return "Open Connections";
    }

    public String shortName() {
        return "ocm";
    }
    
    public boolean visibleFor(HttpServletRequest req){
    	return SimpleAdvanced_ModeUtils.isAdvancedMode(req);
    }
    
    public void init(Node n) {
        connections = n.connections;
    }
    
    public void toHtml(PrintWriter pw, HttpServletRequest req) {
        connections.writeHtmlContents(pw,req);
    }
    
    public void toHtml(PrintWriter pw) { //this one should never be called since we have overridden the previous method
    //connections.writeHtmlContents(pw,null);
    }

	/* (non-Javadoc)
	 * @see freenet.node.http.MultipleFileInfolet#write(java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */
	public boolean write(String file, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        return connections.writeHtmlFile(file,req,resp);
	}
}
