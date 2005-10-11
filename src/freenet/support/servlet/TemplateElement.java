package freenet.support.servlet;

import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;

/**
 * @author ian
 */
public interface TemplateElement {

	/**
	 * Render this element to a PrintWriter.
	 * 
	 * @param pw
	 *            Where the element's contents should be written
	 */
	public void toHtml(PrintWriter pw);

	/**
	 * @param pw
	 *            Where the element's contents should be written
	 * @param req
	 *            The HttpServletRequest which caused this Element to be
	 *            rendered
	 * @see #toHtml(PrintWriter pw)
	 */
	public void toHtml(PrintWriter pw, HttpServletRequest req);
	
}
