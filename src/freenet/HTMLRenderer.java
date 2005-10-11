package freenet;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.NumberFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.OpenConnectionManager.PeerHandlersSnapshot;
import freenet.client.http.ImageServlet;
import freenet.client.http.ImageServlet.Dimension;
import freenet.message.DataRequest;
import freenet.support.Logger;
import freenet.support.servlet.HtmlTemplate;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.ThrottledAsyncTCPWriteManager;
import freenet.transport.tcpConnection;


abstract class HTMLRenderer {

	private final OpenConnectionManager manager;

    protected int iSortingMode = 0;
    
    private NumberFormat nf = NumberFormat.getInstance();

	//Keep track of what HTML mode the user last requested. Obsoleted
	protected int viewLevel = 0;

	HtmlTemplate titleBoxTemplate;

	HTMLRenderer(OpenConnectionManager manager) {
		try {
			titleBoxTemplate = HtmlTemplate.createTemplate("titleBox.tpl");
		} catch (IOException e) {
			Core.logger.log(
				this,
				"Couldn't load titleBox.tpl",
				e,
				Logger.NORMAL);
		}
        this.manager = manager;
	}

	public final void render(PrintWriter pw, HttpServletRequest req) {
		if (req.getParameter("setLevel") != null)
			try {
				viewLevel = Integer.parseInt(req.getParameter("setLevel"));
			} catch (NumberFormatException e) {
			    // User sent us an invalid string, use the default
			}
		if (req.getParameter("setSorting") != null) {
			try {
				iSortingMode =
					Integer.parseInt(req.getParameter("setSorting"));
				//TODO: catch casting errors
			} catch (NumberFormatException e) { /* iSorting=0; */
			}
		}
		PeerHandlersSnapshot snap = 
		    manager.getPeerHandlersSnapshot(iSortingMode);
		renderOverview(pw, req, snap);
		doRenderBody(pw, req, snap);
	}

	public boolean renderFile(
		String file,
		HttpServletRequest req,
		HttpServletResponse resp) throws IOException {
	    // Silence compile warnings: subclass impls may well throw an IOE
	    if(false) throw new IOException();
	    
		return false;
	}

	protected abstract void doRenderBody(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlersSnapshot snap);

	protected void renderLeftHeader(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlersSnapshot snap) {
		renderConnectionsSummary(pw, req, snap);
	}

	protected void renderRightHeader(
		PrintWriter pw,
		HttpServletRequest req) {
		pw.println(renderConnectionIconLegend());
	}

	protected void renderSecondOverviewRow(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlersSnapshot snap) {
		renderMessagesSummary(pw, req, snap);
	}

	protected String timeFromMillis(
		long millis,
		boolean bMinutes,
		boolean bSeconds) {
		String sRetval = null;
		long minutes = Math.round(Math.floor(millis / 60000));
		long seconds =
			Math.round(Math.floor((millis - minutes * 60000) / 1000));
		if (minutes > 0 && bMinutes)
			sRetval = String.valueOf(minutes);
		if (bSeconds)
			if (sRetval != null)
				sRetval += ":"
					+ (seconds < 10
						? ("0" + seconds)
						: String.valueOf(seconds));
			else
				sRetval = seconds + " s";
		return sRetval == null ? "" : sRetval;
	}

	protected String format(long bytes) {
		if (bytes == 0)
			return "None";
		if (bytes > (2L << 32))
			return (nf.format(bytes >> 30) + " GiB").replaceAll(
				" ",
				"&nbsp;");
		if (bytes > (2 << 22))
			return (nf.format(bytes >> 20) + " MiB").replaceAll(
				" ",
				"&nbsp;");
		if (bytes > (2 << 12))
			return (nf.format(bytes >> 10) + " KiB").replaceAll(
				" ",
				"&nbsp;");
		return (nf.format(bytes) + " Bytes").replaceAll(" ", "&nbsp;");
	}

	//Helper method for rendering the view level selection link to HTML
	protected String renderViewLevelSelectorLink(String scriptName) {
		StringBuffer retval = new StringBuffer(70);
		if (viewLevel == 2)
			retval.append(
				"<small><a href = '"
					+ scriptName
					+ "?setLevel=1'>[Less details]</a></small>");
		else if (viewLevel == 0)
			retval.append(
				"<small><a href = '"
					+ scriptName
					+ "?setLevel=1'>[More details]</a></small>");
		else {
			retval.append(
				"<small><a href = '"
					+ scriptName
					+ "?setLevel=2'>[More details]</a></small>");
			retval.append(
				"<small><a href = '"
					+ scriptName
					+ "?setLevel=0'>[Less details]</a></small>");
		}
		return retval.toString();
	}

	protected String renderQueueSize(boolean detailed, long queueSize) {
		return detailed ? String.valueOf(queueSize) : format(queueSize);
	}

	protected String renderConnectionIconLegend() {
		StringBuffer retval = new StringBuffer(500);
        retval.append("<table>\n");
		retval.append(
            "<tr><td colspan = \"2\"><b>Outbound&nbsp;connections&nbsp;legend</b></td>\n"
                + "<td colspan = \"2\"><b>Inbound&nbsp;connections&nbsp;legend</b></td></tr>\n");
		Dimension size =
			ImageServlet.getSize(
				HtmlTemplate.defaultTemplateSet
					+ "/arrow_outbound_sleeping.png");
		retval.append(
			"<tr><td><img src=\"/servlet/images/"
				+ HtmlTemplate.defaultTemplateSet
				+ "/arrow_outbound_sleeping.png\" alt=\"\" "
				+ sizeHTML(size)
                + " /></td>\n<td>Idle</td>\n");
		size =
			ImageServlet.getSize(
				HtmlTemplate.defaultTemplateSet
					+ "/arrow_inbound_sleeping.png");
		retval.append(
			"<td><img src=\"/servlet/images/"
				+ HtmlTemplate.defaultTemplateSet
				+ "/arrow_inbound_sleeping.png\" alt=\"\" "
				+ sizeHTML(size)
                + " /></td>\n<td>Idle</td></tr>\n");
		size =
			ImageServlet.getSize(
				HtmlTemplate.defaultTemplateSet
					+ "/arrow_outbound_transmitting.png");
		retval.append(
			"<tr><td><img src=\"/servlet/images/"
				+ HtmlTemplate.defaultTemplateSet
				+ "/arrow_outbound_transmitting.png\" alt=\"\" "
				+ sizeHTML(size)
                + " /></td>\n"
                + "<td>Transmitting data</td>\n");
		size =
			ImageServlet.getSize(
				HtmlTemplate.defaultTemplateSet
					+ "/arrow_inbound_transmitting.png");
		retval.append(
			"<td><img src=\"/servlet/images/"
				+ HtmlTemplate.defaultTemplateSet
				+ "/arrow_inbound_transmitting.png\" alt=\"\" "
				+ sizeHTML(size)
                + " /></td>\n"
                + "<td>Transmitting data</td></tr>\n");
		size =
			ImageServlet.getSize(
				HtmlTemplate.defaultTemplateSet
					+ "/arrow_outbound_receiving.png");
		retval.append(
			"<tr><td><img src=\"/servlet/images/"
				+ HtmlTemplate.defaultTemplateSet
				+ "/arrow_outbound_receiving.png\" alt=\"\" "
				+ sizeHTML(size)
                + " /></td>\n"
                + "<td>Receiving data</td>\n");
		size =
			ImageServlet.getSize(
				HtmlTemplate.defaultTemplateSet
					+ "/arrow_inbound_receiving.png");
		retval.append(
			"<td><img src=\"/servlet/images/"
				+ HtmlTemplate.defaultTemplateSet
				+ "/arrow_inbound_receiving.png\" alt=\"\" "
				+ sizeHTML(size)
                + " /></td>\n"
                + "<td>Receiving data</td></tr>\n");
		size =
			ImageServlet.getSize(
				HtmlTemplate.defaultTemplateSet
					+ "/arrow_outbound_both.png");
		retval.append(
			"<tr><td><img src=\"/servlet/images/"
				+ HtmlTemplate.defaultTemplateSet
				+ "/arrow_outbound_both.png\" alt=\"\" "
				+ sizeHTML(size)
                + " /></td>\n"
                + "<td>Receiving&nbsp;and&nbsp;transmitting&nbsp;data</td>\n");
		size =
			ImageServlet.getSize(
				HtmlTemplate.defaultTemplateSet
					+ "/arrow_inbound_both.png");
		retval.append(
			"<td><img src=\"/servlet/images/"
				+ HtmlTemplate.defaultTemplateSet
				+ "/arrow_inbound_both.png\" alt=\"\" "
				+ sizeHTML(size)
                + " /></td>\n"
                + "<td>Receiving&nbsp;and&nbsp;transmitting&nbsp;data</td></tr>\n");
		retval.append("</table>");
		return retval.toString();
	}

	/**
     * @param size
     * @return
     */
    private String sizeHTML(Dimension size) {
        return "width=\""
        	+ size.getWidth()
        	+ "\" height=\""
        	+ size.getHeight()
        	+ "\"";
    }

	protected void renderMessagesSummary(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlersSnapshot snap) {
		if (viewLevel == 0) {
			pw.println(
				"Number of requests (sent/received) "
					+ snap.allMessagesTransfered.getMessagesSentByType(
						DataRequest.messageName)
					+ "/"
					+ snap.allMessagesTransfered.getMessagesReceivedByType(
						DataRequest.messageName));
		} else {
			pw.print(snap.allMessagesTransfered.toHTML(viewLevel > 1));
		}
	}

	protected void renderConnectionsSummary(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlersSnapshot snap) {
		List lConnections = this.manager.getConnectionListSnapshot();
		int outboundConnectionsCount = 0;
		int inboundConnectionsCount = 0;
		long sendQueueSize = this.manager.totalSendQueueSize();
		long totalDataSent = 0;
		long receiveQueueSize = 0;
		long totalDataReceived = 0;
		int sendingConnectionsCount = 0;
		int receivingConnectionsCount = 0;
		int transferringConnectionsCount = 0;
		HashSet uniquePeers = new HashSet();
		Iterator it = lConnections.iterator();
		// To be able to indicate repetitions
		while (it.hasNext()) {
			BaseConnectionHandler ch = (BaseConnectionHandler) it.next();
			boolean sending = ch.blockedSendingTrailer() || ch.isSendingPacket();
			boolean receiving = ch.receiving();
			if (sending)
				sendingConnectionsCount++;
			if (receiving)
				receivingConnectionsCount++;
			if (sending || receiving)
				transferringConnectionsCount++;
			if (ch.isOutbound()) {
				outboundConnectionsCount++;
			} else {
				inboundConnectionsCount++;
			}
			ConnectionDataTransferAccounter acc = ch.getTransferAccounter();
			receiveQueueSize += acc.receiveQueueSize();
			totalDataSent += acc.totalDataSent();
			totalDataReceived += acc.totalDataReceived();
			if (!uniquePeers.contains(ch.peerIdentity().toString()))
				uniquePeers.add(ch.peerIdentity().toString());
		}
		pw.println("<table border=\"0\" cellspacing=\"1\">\n");
		pw.println(
				   "<tr><td>Connections&nbsp;open&nbsp;(Inbound/Outbound/Limit)</td><td>"
				   + (inboundConnectionsCount + outboundConnectionsCount)
				   + "&nbsp;("
				   + inboundConnectionsCount
				   + "/"
				   + outboundConnectionsCount
				   + "/"
				   + this.manager.getMaxNodeConnections()
				   + ")</td></tr>");
		pw.println(
				   "<tr><td>Transfers&nbsp;active&nbsp;(Transmitting/Receiving)</td><td>"
				   + (snap.totalTrailersSending
					  + snap.totalTrailersReceiving)
				   + "&nbsp;("
				   + (snap.totalTrailersSending)
				   + "/"
				   + (snap.totalTrailersReceiving)
				   + ")</td></tr>");
		ThrottledAsyncTCPWriteManager wsl = tcpConnection.getWSL();
		long totalWrittenBytes =
			wsl.getTotalTransferedPseudoThrottlableBytes()
			+ wsl.getTotalTransferedThrottlableBytes();
		ThrottledAsyncTCPReadManager rsl = tcpConnection.getRSL();
		long totalReadBytes =
			rsl.getTotalTransferedPseudoThrottlableBytes()
			+ rsl.getTotalTransferedThrottlableBytes();
		if (viewLevel > 0) {
			pw.println(
					   "<tr><td>Data waiting to be transmitted/received</td><td>"
					   + format(sendQueueSize)
					   + "/"
					   + format(receiveQueueSize)
					   + "</td></tr>");
			pw.println(
					   "<tr><td>Amount of data transmitted/received over currently open connections</td>"
					   + "<td>"
					   + format(totalDataSent)
					   + "/"
					   + format(totalDataReceived)
					   + "</td></tr>");
			pw.println(
					   "<tr><td>Total amount of data transmitted/received</td><td>"
					   + (format(totalWrittenBytes)
						  + "/"
						  + format(totalReadBytes)
						  + "</td></tr>"));
			pw.println(
					   "<tr><td>Number of distinct nodes connected</td><td>"
					   + uniquePeers.size()
					   + "</td></tr>");
		} else {
			pw.println(
					   "<tr><td>Data waiting to be transferred</td><td>"
					   + format(sendQueueSize + receiveQueueSize)
					   + "</td></tr>");
			pw.println(
					   "<tr><td>Total amount of data transferred</td><td>"
					   + format(totalWrittenBytes + totalReadBytes)
					   + "</td></tr>");
		}
		pw.println("</table>\n");
	}

	protected void renderOverview(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlersSnapshot snap) {
		renderTitle(pw, req);
		pw.println(
			"<table class=\"ocmOverview\"><tr><td class=\"ocmOverviewLeftHeader\">");
		renderLeftHeader(pw, req, snap);
		pw.println("</td><td class=\"ocmOverviewRightHeader\">");
		renderRightHeader(pw, req);
		pw.println("</td></tr></table>");
		renderSecondOverviewRow(pw, req, snap);
	}

	private void renderTitle(PrintWriter pw, HttpServletRequest req) {
		if (this.manager.getPeerHandlerHTMLMode())
			pw.println(
				"<h2>Peers <small><a href = '"
					+ req.getRequestURI()
					+ "?setMode=Connection'>[Switch to connections mode]</a></small></h2>");
		else
			pw.println(
				"<h2>Connections <small><a href = '"
					+ req.getRequestURI()
					+ "?setMode=Peer'>[Switch to peers mode]</a></small></h2>");
		pw.println("<p>" + new Date() + "</p>");
		pw.println(
			"<p class=\"ocmViewLevelSelector\">"
				+ renderViewLevelSelectorLink(req.getRequestURI())
				+ "</p>");
	}
}
