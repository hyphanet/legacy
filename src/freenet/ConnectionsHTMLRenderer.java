/*
 * Created on Apr 7, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet;

import freenet.client.http.ImageServlet.Dimension;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import freenet.OpenConnectionManager.PeerHandlersSnapshot;
import freenet.client.http.ImageServlet;
import freenet.node.Main;
import freenet.node.NodeReference;
import freenet.support.servlet.HtmlTemplate;


class ConnectionsHTMLRenderer extends HTMLRenderer {

	private final OpenConnectionManager manager;

    ConnectionsHTMLRenderer(OpenConnectionManager manager) {
		super(manager);
        this.manager = manager;
	}

	private void renderConnectionList(
		StringBuffer buffer,
		List lConnections) {
		Iterator it = lConnections.iterator();
		BaseConnectionHandler chPrev = null;
		// To be able to indicate repetitions
		String sep = "</td><td>";
		String sepAlignRight = "</td><td align = 'right'>";
		String sepAlignCenter = "</td><td align = 'center'>";
		String repetition = "<center>^^^</center>";
		while (it.hasNext()) {
			BaseConnectionHandler ch = (BaseConnectionHandler) it.next();
			buffer.append("\n<tr>");
			buffer.append("<td>");
			String imageURL =
				HtmlTemplate.defaultTemplateSet + "/arrow";
			if (ch.isOutbound())
				imageURL += "_outbound";
			else
				imageURL += "_inbound";
			if (ch.receiving() && 
			        (ch.blockedSendingTrailer() || ch.isSendingPacket())) //Can this happen yet?
				imageURL += "_both";
			else if (ch.receiving())
				imageURL += "_receiving";
			else if (ch.blockedSendingTrailer() || ch.isSendingPacket())
				imageURL += "_transmitting";
			else
				imageURL += "_sleeping";
			imageURL += ".png";
			Dimension size = ImageServlet.getSize(imageURL);
			buffer.append(
						  "<center><img src=\""
						  + "/servlet/images/"
						  + imageURL
						  + "\" alt=\"\" width=\""
						  + size.getWidth()
						  + "\" height=\""
						  + size.getHeight()
                          + "\" />"
						  + "</center></td>");
			if (viewLevel > 0) {
				int localPort = ch.getLocalPort();
				String localPortString = String.valueOf(localPort);
				if (localPort
					== BaseConnectionHandler.CONNECTION_TERMINATED_PORTNUMBER)
					localPortString = "Closed";
				if (localPort
					== BaseConnectionHandler.CHANNEL_CLOSED_PORTNUMBER)
					localPortString = "Channel closed";
				buffer.append("<TD>" + localPortString + "</TD>");
			}
			NodeReference n =
				Main.node.rt.getNodeReference(ch.peerIdentity());
			NodeReference nPrev =
				(chPrev == null)
					? null
					: Main.node.rt.getNodeReference(chPrev.peerIdentity());
			String s;
			if (n == null) {
				s = "not in RT";
			} else if (n.physical.length == 0) {
				s = "no addresses";
			} else {
				s = n.physical[1];
				// physical[even] contains something like 'tcp',
				// physical[odd] contains the associated address
			}
			if (viewLevel > 1) {
				buffer.append(
					"<td>"
						+ ((chPrev != null
							&& (chPrev
								.peerAddress()
								.toString()
								.compareTo(ch.peerAddress().toString())
								== 0))
							? repetition
							: ch.peerAddress().toString()));
				buffer.append(
					sep
						+ ((nPrev != null && n != null && nPrev.equals(n))
							? repetition
							: s));
			} else
				buffer.append(
					"<td align = 'right'>"
						+ (n == null
							? "<font color = #555555>"
								+ ch.peerAddress().toString()
								+ "</font>"
							: s));
			if (viewLevel > 1)
				buffer.append(
					sep
						+ ((chPrev != null
							&& chPrev.peerIdentity() == ch.peerIdentity())
							? repetition
							: ch.peerIdentity().toString().replaceAll(
								" ",
								"&nbsp;")));
			if (viewLevel > 0) {
				NodeReference nTarget = ch.targetReference();
				String peerVersion;
				if (nTarget == null)
					peerVersion = "&lt;Unknown&gt;";
				else
					peerVersion = nTarget.getVersion();
				buffer.append(sep + peerVersion.replaceAll(" ", "&nbsp;"));
			}
			//int x = ch.sendingCount();
			//if(x > 0) sending++;;
			//buffer.append(ch.sendingCount() + sep); Remove until there
			// can actually be other values than 0 and 1 here
			buffer.append(
				sepAlignRight
					+ ch.messagesSent()
					+ ":"
					+ ch.messagesReceived());
			ConnectionDataTransferAccounter acc = ch.getTransferAccounter();
			if (viewLevel > 0) {
				buffer.append(
					sepAlignRight
						+ renderQueueSize(
							viewLevel > 1,
							acc.totalDataSent()));
				if (ch.receiving())
					buffer.append(
						sepAlignRight
							+ renderQueueSize(
								viewLevel > 1,
								acc.receiveQueueSize()));
				else
					buffer.append(sepAlignRight + "-");
				buffer.append(
					sepAlignRight
						+ renderQueueSize(
							viewLevel > 1,
							acc.totalDataReceived()));
			} else {
				if (ch.blockedSendingTrailer() || ch.receiving())
					buffer.append(
						sepAlignRight + format(acc.sendQueueSize()));
				else
					buffer.append(sepAlignRight + "-");
				buffer.append(
					sepAlignRight
						+ renderQueueSize(
							false,
							acc.totalDataSent() + acc.totalDataReceived()));
			}
			buffer.append(
				sepAlignCenter
				+ timeFromMillis(ch.idleTime(), true, true));
				buffer.append(
					sepAlignCenter
						+ timeFromMillis(ch.runTime(), true, true));
			buffer.append("</td></tr>");
			chPrev = ch;
		}
	}

	private void renderConnectionsTableHeader(
		PrintWriter pw,
		HttpServletRequest req) {
		pw.print(
			renderConnectionsTableColumnHeader(
				req.getRequestURI(),
				iSortingMode,
				ConnectionHandlerComparator.OUTBOUND,
				"Type"));
		if (viewLevel > 0)
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.LOCAL_PORT,
					"Local port"));
		if (viewLevel > 1) {
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.PEER_ADDRESS,
					"Peer address"));
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.ROUTING_ADDRESS,
					"Routing address"));
		} else
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.ROUTING_ADDRESS,
					"Peer"));
		if (viewLevel > 1) {
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.PEER_IDENTITY,
					"Peer identity"));
		}
		if (viewLevel > 0)
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.PEER_NODE_VERSION,
					"Peer node version"));
		pw.print(
			renderConnectionsTableColumnHeader(
				req.getRequestURI(),
				iSortingMode,
				ConnectionHandlerComparator.MESSAGES,
				"Messages"));
		if (viewLevel > 0) {
			//pw.print(renderTableHeader(req.getRequestURI(),iSortingMode,ConnectionHandlerComparator.SENDQUEUE,"Send
			// queue"));
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.DATASENT,
					"Data sent"));
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.RECEIVEQUEUE,
					"Receive queue"));
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.DATARECEIVED,
					"Data received"));
		} else {
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.COMBINEDQUEUE,
					"Queue"));
			pw.print(
				renderConnectionsTableColumnHeader(
					req.getRequestURI(),
					iSortingMode,
					ConnectionHandlerComparator.COMBINED_DATA_TRANSFERED,
					"Data transferred"));
		}
		pw.print(
			renderConnectionsTableColumnHeader(
				req.getRequestURI(),
				iSortingMode,
				ConnectionHandlerComparator.IDLETIME,
				"Idletime"));
		pw.print(
			renderConnectionsTableColumnHeader(
				req.getRequestURI(),
				iSortingMode,
				ConnectionHandlerComparator.LIFETIME,
				"Lifetime"));
	}

	//Helper method for rendering the connection table header fields to
	// HTML
	private String renderConnectionsTableColumnHeader(
		String scriptName,
		int currentSorting,
		int clickSortingMode,
		String label) {
		String imageURL =
			HtmlTemplate.defaultTemplateSet
				+ "/s_ar_"
				+ (currentSorting < 0 ? "up" : "down")
				+ ".png";
		Dimension size = ImageServlet.getSize(imageURL);
		String sImgClause =
			"<img src=\"/servlet/images/"
				+ imageURL
				+ "\" alt=\"\" width=\""
				+ size.getWidth()
				+ "\" height=\""
				+ size.getHeight()
                + "\" />";
		return "<th><a href=\""
			+ scriptName
			+ "?setSorting="
			+ (currentSorting == clickSortingMode
				? ("-" + clickSortingMode)
				: new Long(clickSortingMode).toString())
			+ "\">"
			+ (Math.abs(currentSorting) == clickSortingMode
				? sImgClause
				: "")
			+ " "
			+ label.replaceAll(" ", "&nbsp;")
            + "</a></th>\n";
	}

	/**
	 * Ignores snap
	 */
	protected void doRenderBody(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlersSnapshot snap) {
		StringBuffer buffer = new StringBuffer();
		List lConnections = this.manager.getConnectionListSnapshot();
		//Build a sorter and sort
		ConnectionHandlerComparator sorter = null;
		// Magically mutate the requested sorting in certain
		// circumstances. Would be better if the iSortingMode
		// parameter could be an vector of integers specifying
		// the full sorting strategy
		if (Math.abs(iSortingMode)
			== ConnectionHandlerComparator.OUTBOUND) {
			// Special case if we are sorting on the link-icon. Sort
			// first by in/out and then by sending and then by
			// receiving
			sorter =
				new ConnectionHandlerComparator(
					iSortingMode,
					new ConnectionHandlerComparator(
						-ConnectionHandlerComparator.SENDING,
						new ConnectionHandlerComparator(
							-ConnectionHandlerComparator.RECEIVING)));
		} else if (
			Math.abs(iSortingMode)
			== ConnectionHandlerComparator.ROUTING_ADDRESS
			&& viewLevel == 0) {
				sorter =
					new ConnectionHandlerComparator(
						iSortingMode,
						new ConnectionHandlerComparator(
							ConnectionHandlerComparator.PEER_IDENTITY));
		}
		if (sorter == null)
			sorter = new ConnectionHandlerComparator(iSortingMode);
		Collections.sort(lConnections, sorter);
		renderConnectionList(buffer, lConnections);
		//renderOCMOverview(pw, req);
		pw.println("<table border=\"1\" cellspacing=\"0\">\n");
		pw.print("<tr>");
		renderConnectionsTableHeader(pw, req);
		pw.println(buffer.toString());
		pw.println("</table>");
	}
}
