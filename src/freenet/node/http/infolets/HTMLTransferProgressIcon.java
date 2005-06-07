/*
 * Created on Aug 31, 2004
 *
 */
package freenet.node.http.infolets;

import freenet.client.http.ImageServlet;
import freenet.client.http.ImageServlet.Dimension;
import freenet.support.servlet.HtmlTemplate;

/**
 * @author Iakin
 */
public class HTMLTransferProgressIcon {

	private final int type;
	private final String altText;
	private final String titleText;
	
	public final static int ICONTYPE_WAITING=0;
	public final static int ICONTYPE_TRANSFERING=1;
	public final static int ICONTYPE_SUCCESS=2;
	public final static int ICONTYPE_FAILURE=3;
	public final static int ICONTYPE_RETRY=4;
	public final static int ICONTYPE_RETRY2=5;
	public final static int ICONTYPE_PROGRESS=6;
	public final static int ICONTYPE_REFRESH=7;
	
	/**
	 * @param type indicates what typw of progress icon that is desired.
	 * Should be one of the above values.
	 */
	public HTMLTransferProgressIcon(int type) {
		this(type, "");
	}
	
	public HTMLTransferProgressIcon(int type, String altText) {
		this(type, altText, altText);
	}
	
	public HTMLTransferProgressIcon(int type, String altText, String titleText) {
		this.type = type;
		this.altText = altText;
		this.titleText = titleText;
	}
	
	
	public String render() {
		switch (type) {
		case ICONTYPE_SUCCESS: {
			Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/success.png");
			return "<img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/success.png\" alt=\"" + altText + "\" title=\"" + titleText + "\" width=\"" + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img>";
		}
		case ICONTYPE_WAITING: {
			Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/waiting.png");
			return "<img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/waiting.png\" alt=\"" + altText + "\" title=\"" + titleText + "\" width=\"" + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img>";
		}
		case ICONTYPE_FAILURE: {
			Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/failed.png");
			return "<img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/failed.png\" alt=\"" + altText + "\" title=\"" + titleText + "\" width=\"" + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img>";
		}
		case ICONTYPE_TRANSFERING: {
			Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/transfer.png");
			return "<img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/transfer.png\" alt=\"" + altText + "\" title=\"" + titleText + "\" width=\"" + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img>";
		}
		case ICONTYPE_RETRY: {
			Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/retry.png");
			return "<img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/retry.png\" alt=\"" + altText + "\" title=\"" + titleText + "\" width=\"" + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img>";
		}
		case ICONTYPE_RETRY2: {
			Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/retry2.png");
			return "<img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/retry2.png\" alt=\"" + altText + "\" title=\"" + titleText + "\" width=\"" + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img>";
		}
		case ICONTYPE_PROGRESS: {
			Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/progress.png");
			return "<img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/progress.png\" alt=\"" + altText + "\" title=\"" + titleText + "\" width=\"" + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img>";
		}
		case ICONTYPE_REFRESH: {
			Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/refresh.png");
			return "<img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/refresh.png\" alt=\"" + altText + "\" title=\"" + titleText + "\" width=\"" + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img>";
		}
		}
		return null;
	}
}
