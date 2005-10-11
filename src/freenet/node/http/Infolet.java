package freenet.node.http;

import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;

import freenet.node.Node;
import freenet.support.servlet.TemplateElement;

/**
 * An Infolet takes a Node object and a PrintWriter, and sends a fragment of
 * HTML to the printwriter describing some aspect of the node's status
 * 
 * @author ian
 */
public abstract class Infolet implements TemplateElement {

	/**
	 * @return The long name for the Infolet (as appears in menu and page title
	 */
	public abstract String longName();

	/**
	 * @return Wheter or not the Infolet wants to be visible in the menu for
	 *         the given request. Always visible unless overridden
	 */
	public boolean visibleFor(HttpServletRequest req) {
		return true;
	}

	/**
	 * @return Short alphanumeric name for Infolet for use in a URL
	 */
	public abstract String shortName();

	/**
	 * Initialise the Infolet with a Node object, called shortly after node
	 * startup
	 * 
	 * @param n
	 */
	public abstract void init(Node n);

	/**
	 * Send a fragment of HTML describing some aspect of the Node's status to
	 * the PrintWriter
	 * 
	 * @param pw
	 */
	public abstract void toHtml(PrintWriter pw);

	/**
	 * As above, but where a HttpServletRequest object is also passed to the
	 * Infolet. If not overridden, this will simply call the above method/
	 * 
	 * @param pw
	 * @param req
	 */
	public void toHtml(PrintWriter pw, HttpServletRequest req) {
		toHtml(pw);
	}

	public String target(String base, String container) {
		return base + container + "/" + shortName();
	}

}
