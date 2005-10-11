/*
 * Created on Apr 7, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet;

import freenet.client.http.ImageServlet.Dimension;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Hashtable;
import java.util.Iterator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.OpenConnectionManager.PeerHandlerDataSnapshot;
import freenet.OpenConnectionManager.PeerHandlersSnapshot;
import freenet.client.http.ImageServlet;
import freenet.node.Main;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.NGRoutingTable;
import freenet.node.rt.NodeEstimator;
import freenet.node.rt.RoutingTable;
import freenet.support.Logger;
import freenet.support.graph.Bitmap;
import freenet.support.graph.Color;
import freenet.support.graph.DibEncoder;
import freenet.support.graph.GDSList;
import freenet.support.servlet.HtmlTemplate;


class PeerHTMLRenderer extends HTMLRenderer {

	private Hashtable rtColors = new Hashtable();

	protected class Value {

		String subLabel;

		String data;

		String barColor;

		long barLength;

		Value(
			String subLabel,
			String data,
			String barColor,
			long barLength) {
			this.subLabel = subLabel;
			this.data = data;
			this.barColor = barColor;
			this.barLength = barLength;
		}
	}

	PeerHTMLRenderer(OpenConnectionManager manager) {
		super(manager);
		rtColors.put("tSuccessSearch", new Color(155, 0, 0));
		rtColors.put("rTransferSuccess", new Color(80, 80, 0));
		rtColors.put("pDNF", new Color(0, 130, 0));
		rtColors.put("tDNF", new Color(0, 0, 155));
		rtColors.put("combined", new Color(0, 0, 0));
	}

	protected void doRenderBody(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlersSnapshot snap) {
		Iterator it = snap.lPHData.iterator();
        if (it.hasNext()) {
            pw.println("<table>");
            while (it.hasNext()) {
                pw.println("<tr><td>");
                PeerHandlerDataSnapshot p = (PeerHandlerDataSnapshot) it.next();
                renderPeerHandler(pw, req, p, snap.maxValues);
                pw.println("</td></tr>");
            }
            pw.println("</table>");
        }
	}

	protected float normalize(float value, float max) {
		return (max == 0) ? value : value / max;
	}

	protected long calculateBarLength(float value, float max) {
		return calculateBarLength(value, max, max);
	}

	//Only differnce to the previous method is that this method accepts
	//Two distinct values to notmalize the bar lenght to. The largest of
	// the values will be used.
	//This can be very useful when bars comes in pairs which might be
	// compared to each other for one
	//reason or another
	protected long calculateBarLength(
		float value,
		float max,
		float siblingMax) {
		int maxBarWidth = 350;
		int minBarLength = 2;
		return Math.round(
			normalize(value, Math.max(max, siblingMax)) * maxBarWidth
				+ minBarLength);
	}

	protected void renderPeerHandler(
		PrintWriter pw,
		HttpServletRequest req,
		PeerHandlerDataSnapshot p,
		PeerHandlerDataSnapshot normalizeTo) {
		RoutingTable rt = Main.origRT;
		NodeEstimator e;
		if (rt instanceof NGRoutingTable) {
			e = ((NGRoutingTable) rt).getEstimator(p.identity);
		} else {
			e = null;
		}
		boolean inRoutingTable = e != null;
		HtmlTemplate template = null;

		// prepare the template
		try {
			if (inRoutingTable) {
				template =
					HtmlTemplate.createTemplate(
						"OpenConnectionManager/Peer/level"
							+ viewLevel
							+ "/peerInRT.tpl");
			} else {
				template =
					HtmlTemplate.createTemplate(
						"OpenConnectionManager/Peer/level"
							+ viewLevel
							+ "/peer.tpl");
			}
		} catch (IOException e1) {
			Core.logger.log(
				this,
				"Couldn't load templates",
				e1,
				Logger.NORMAL);
		}

		//Peer version
		String peerVersion = p.version;
		if (peerVersion.length() == 0)
			peerVersion = "&lt;Unknown&gt;";
		else if (viewLevel < 2) //Then add some stupidification
			peerVersion =
				peerVersion.substring(
					peerVersion.lastIndexOf(",") + 1,
					peerVersion.length());
		//Peer address
		String peerAddress = p.address;
		if (peerAddress.length() == 0)
			peerAddress = "&lt;Unknown&nbsp;peer&nbsp;address&gt;";
		else if (viewLevel == 0) { //Then add some stupidification
			int iSep = peerAddress.indexOf("/");
			if (iSep > 0)
				peerAddress =
					peerAddress.substring(iSep + 1, peerAddress.length());
			iSep = peerAddress.indexOf(":");
			if (iSep > 0)
				peerAddress = peerAddress.substring(0, iSep);
		} else {
			if (viewLevel == 1) { //Then add somewhat less stupidification
				int iSep = peerAddress.indexOf("/");
				if (iSep > 0)
					peerAddress =
						peerAddress.substring(
							iSep + 1,
							peerAddress.length());
			}
		}
		// now that we have the node's address and version, we can put them
		// into the template
		template.set("NODEADDRESS", peerAddress);
		template.set("NODEVERSION", peerVersion);
		if (viewLevel > 1) {
			// curious minds can have the not-so human-readable node
			// identity, too
			template.set(
				"NODEIDENTITY",
				p.identity.toString().replaceAll(" ", "&nbsp;"));
		}

		// identity for the routing graph
		if (inRoutingTable) {
			template.set(
				"IDENTITY",
				((DSAIdentity) p.identity).getYAsHexString());
		}

		// messages
		if (viewLevel > 0) {
			if (viewLevel > 1) {
				template.set("MESSAGESLIST", p.acc.toHTML(true));
			} else {
				template.set("MESSAGESLIST", p.acc.toHTML(false));
			}
		}

		// DataQueues
		if (viewLevel > 0) {
			String sendQueue =
				viewLevel > 1
					? String.valueOf(p.sendQueue)
					: format(p.sendQueue);
			String receiveQueue =
				viewLevel > 1
					? String.valueOf(p.receiveQueue)
					: format(p.receiveQueue);
			template.set("DATAQUEUEDOUT", sendQueue);
			template.set(
				"DATAQUEUEDOUTBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.sendQueue,
						normalizeTo.sendQueue,
						normalizeTo.receiveQueue)));
			template.set("DATAQUEUEDIN", receiveQueue);
			template.set(
				"DATAQUEUEDINBARLENGTH",
				Long.toString(
					calculateBarLength(
						0,
						normalizeTo.sendQueue,
						normalizeTo.sendQueue)));
		}
		// estimated routing time
		if (inRoutingTable) {
			GDSList gdsl = e.createGDSL(10, 0, null);
			template.set(
				"ESTIMATEDROUTINGTIMEMIN",
				String.valueOf(gdsl.lowest));
			template.set(
				"ESTIMATEDROUTINGTIMEMAX",
				String.valueOf(gdsl.highest));
		}
		// Data Transferred
		if (viewLevel == 0) {
			template.set(
				"DATATRANSFERRED",
				format(p.dataSent + p.dataReceived));
			template.set(
				"DATATRANSFERREDBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.dataSent + p.dataReceived,
						normalizeTo.dataSent + normalizeTo.dataReceived)));
		} else {
			String dataSent =
				viewLevel > 1
					? String.valueOf(p.dataSent)
					: format(p.dataSent);
			String dataReceived =
				viewLevel > 1
					? String.valueOf(p.dataReceived)
					: format(p.dataReceived);
			template.set("DATATRANSFERREDOUT", dataSent);
			template.set(
				"DATATRANSFERREDOUTBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.dataSent,
						normalizeTo.dataSent,
						normalizeTo.dataReceived)));
			template.set("DATATRANSFERREDIN", dataReceived);
			template.set(
				"DATATRANSFERREDOUTBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.dataReceived,
						normalizeTo.dataReceived,
						normalizeTo.dataSent)));
		}
		// pDNF
		if (inRoutingTable) {
			if (viewLevel > 0) {
				template.set(
					"PDNFMIN",
					e
						.getHTMLReportingTool()
						.getEstimator("pDNF")
						.lowestString());
				template.set(
					"PDNFMAX",
					e
						.getHTMLReportingTool()
						.getEstimator("pDNF")
						.highestString());
			}
		}
		// MessageQueue
		if (viewLevel > 0) {
			template.set(
				"MESSAGESQUEUED",
				String.valueOf(p.messagesQueued));
			template.set(
				"MESSAGESQUEUEDBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.messagesQueued,
						normalizeTo.messagesQueued)));
		}
		// tDNF
		if (inRoutingTable) {
			if (viewLevel > 0) {
				template.set(
					"TDNFMIN",
					e
						.getHTMLReportingTool()
						.getEstimator("tDNF")
						.lowestString());
				template.set(
					"TDNFMAX",
					e
						.getHTMLReportingTool()
						.getEstimator("tDNF")
						.highestString());
			}
		}
		// tSuccessSearch
		if (inRoutingTable) {
			if (viewLevel > 0) {
				template.set(
					"TSUCCESSFULSEARCHMIN",
					e
						.getHTMLReportingTool()
						.getEstimator("tSuccessSearch")
						.lowestString());
				template.set(
					"TSUCCESSFULSEARCHMAX",
					e
						.getHTMLReportingTool()
						.getEstimator("tSuccessSearch")
						.highestString());
			}
		}
		// Messages transferred
		if (viewLevel < 2) {
			if (viewLevel == 0) {
				template.set(
					"MESSAGESTRANSFERRED",
					String.valueOf(p.messagesSent + p.messagesSendFailed));
				template.set(
					"MESSAGESTRANSFERREDBARLENGTH",
					Long.toString(
						calculateBarLength(
							p.messagesSent + p.messagesSendFailed,
							normalizeTo.messagesSent
								+ normalizeTo.messagesSendFailed)));
			} else {
				template.set(
					"MESSAGESTRANSFERREDOUT",
					String.valueOf(p.messagesSent));
				template.set(
					"MESSAGESTRANSFERREDOUTBARLENGTH",
					Long.toString(
						calculateBarLength(
							p.messagesSent,
							normalizeTo.messagesSent,
							normalizeTo.messagesReceived)));
				template.set(
					"MESSAGESTRANSFERREDIN",
					String.valueOf(p.messagesReceived));
				template.set(
					"MESSAGESTRANSFERREDINBARLENGTH",
					Long.toString(
						calculateBarLength(
							p.messagesReceived,
							normalizeTo.messagesReceived,
							normalizeTo.messagesSent)));
			}
		} else {
			template.set("MESSAGESSENTOK", String.valueOf(p.messagesSent));
			template.set(
				"MESSAGESSENTOKBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.messagesSent,
						normalizeTo.messagesSent,
						normalizeTo.messagesSendFailed)));
			template.set(
				"MESSAGESSENTFAIL",
				String.valueOf(p.messagesSendFailed));
			template.set(
				"MESSAGESSENTFAILBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.messagesSendFailed,
						normalizeTo.messagesSendFailed,
						normalizeTo.messagesSent)));
		}
		// Trailer transfers (see muxing)
		template.set(
			"TRAILERSINTRANSITOUT",
			String.valueOf(p.sendingTrailers));
		template.set(
			"TRAILERSINTRANSITOUTBARLENGTH",
			Long.toString(
				calculateBarLength(
					p.sendingTrailers,
					normalizeTo.sendingTrailers,
					normalizeTo.sendingTrailers)));
		template.set(
			"TRAILERSINTRANSITIN",
			String.valueOf(p.receivingTrailers));
		template.set(
			"TRAILERSINTRANSITINBARLENGTH",
			Long.toString(
				calculateBarLength(
					p.receivingTrailers,
					normalizeTo.receivingTrailers,
					normalizeTo.receivingTrailers)));
		//Transfers
		//String transfers = String.valueOf(0);
		//renderPeerHandlerCell(pw,req,"Transfers
		// completed",transfers,"0000FF",100);
		//Idle time
		template.set("TIMEIDLE", timeFromMillis(p.idleTime, true, true));
		template.set(
			"TIMEIDLEBARLENGTH",
			Long.toString(
				calculateBarLength(
					p.idleTime,
					normalizeTo.idleTime,
					normalizeTo.lifeTime)));
		template.set("TIMELIFE", timeFromMillis(p.lifeTime, true, true));
		template.set(
			"TIMELIFEBARLENGTH",
			Long.toString(
				calculateBarLength(
					p.lifeTime,
					normalizeTo.lifeTime,
					normalizeTo.idleTime)));
		if (inRoutingTable) {
			if (viewLevel > 0) {
				// transfer rate
				template.set(
					"TRANSFERRATEMIN",
					e
						.getHTMLReportingTool()
						.getEstimator("rTransferSuccess")
						.lowestString());
				template.set(
					"TRANSFERRATEMAX",
					e
						.getHTMLReportingTool()
						.getEstimator("rTransferSuccess")
						.highestString());
			}
		}
		//Request balance (not handled.. just received/sent.. QR:d or not)
		template.set("REQUESTSOUT", String.valueOf(p.requestsSent));
		template.set(
			"REQUESTSOUTBARLENGTH",
			Long.toString(
				calculateBarLength(
					p.requestsSent,
					normalizeTo.requestsSent,
					normalizeTo.requestsReceived)));
		template.set("REQUESTSIN", String.valueOf(p.requestsReceived));
		template.set(
			"REQUESTSINBARLENGTH",
			Long.toString(
				calculateBarLength(
					p.requestsReceived,
					normalizeTo.requestsReceived,
					normalizeTo.requestsSent)));
		//Connection Attempts
		if (viewLevel == 1) {
			template.set(
				"CONNECTIONSUCCESS",
				new java.text.DecimalFormat("0.00").format(
					p.outboundConnectionSuccessRatio));
			template.set(
				"CONNECTIONSUCCESSBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.outboundConnectionSuccessRatio,
						normalizeTo.outboundConnectionSuccessRatio)));
		} else if (viewLevel > 1) {
			template.set(
				"CONNECTIONATTEMPTSTOTAL",
				String.valueOf(p.connectionAttempts));
			template.set(
				"CONNECTIONATTEMPTSTOTALBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.connectionAttempts,
						normalizeTo.connectionAttempts,
						normalizeTo.connectionSuccesses)));

			template.set(
				"CONNECTIONATTEMPTSSUCCESSES",
				String.valueOf(p.connectionSuccesses));
			template.set(
				"CONNECTIONATTEMPTSSUCCESSESBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.connectionSuccesses,
						normalizeTo.connectionSuccesses,
						normalizeTo.inboundConnectionsCount)));
		}
		//Open Connections, not very interesting with muxxing in place
		// really....
		if (viewLevel > 1) { //Only display at maximum detail level
			template.set(
				"CONNECTIONSOUT",
				String.valueOf(p.outboundConnectionsCount));
			template.set(
				"CONNECTIONSOUTBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.outboundConnectionsCount,
						normalizeTo.outboundConnectionsCount,
						normalizeTo.inboundConnectionsCount)));
			template.set(
				"CONNECTIONSIN",
				String.valueOf(p.inboundConnectionsCount));
			template.set(
				"CONNECTIONSINBARLENGTH",
				Long.toString(
					calculateBarLength(
						p.inboundConnectionsCount,
						normalizeTo.inboundConnectionsCount,
						normalizeTo.outboundConnectionsCount)));
			template.set(
				"REQUESTINTERVAL",
				String.valueOf(p.requestInterval) + "ms");
			template.set(
				"REQUESTINTERVALBARLENGTH",
				Long.toString(
					calculateBarLength(
						(float) (p.requestInterval),
						(float) (normalizeTo.requestInterval))));
		}
		template.toHtml(pw);
	}

	protected void renderRightHeader(
		PrintWriter pw,
		HttpServletRequest req) {
		renderSortingTool(pw, req);
	}

	protected void renderSortingTool(
		PrintWriter pw,
		HttpServletRequest req) {
		StringWriter sw = new StringWriter();
		PrintWriter psw = new PrintWriter(sw);
		titleBoxTemplate.set("TITLE", "Sort by:");
		psw.println(
			renderSortingLink(
				req.getRequestURI(),
				"Peer address",
				PeerHandler.PeerHandlerComparator.PEER_ADDRESS));
		psw.println(
			renderSortingLink(
				req.getRequestURI(),
				"Peer version",
				PeerHandler.PeerHandlerComparator.PEER_NODE_VERSION));
		if (viewLevel > 0) {
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Data queued out",
					PeerHandler.PeerHandlerComparator.SENDQUEUE));
			//pw.println(renderSortingLink(req.getRequestURI(),"Data
			// queued in",PeerHandler.PeerHandlerComparator.SENDQUEUE));
		}
		if (viewLevel == 0)
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Data transferred",
					PeerHandler
						.PeerHandlerComparator
						.COMBINED_DATA_TRANSFERED));
		else {
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Data sent",
					PeerHandler.PeerHandlerComparator.DATASENT));
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Data received",
					PeerHandler.PeerHandlerComparator.DATARECEIVED));
		}
		if (viewLevel > 0) {
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Small messages queued",
					PeerHandler.PeerHandlerComparator.QUEUED_MESSAGES));
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Large messages queued",
					PeerHandler
						.PeerHandlerComparator
						.QUEUED_TRAILERMESSAGES));
		}
		if (viewLevel == 0)
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Messages transferred",
					PeerHandler
						.PeerHandlerComparator
						.MESSAGES_HANDLED_COMBINED));
		else {
			if (viewLevel == 1) {
				psw.println(
					renderSortingLink(
						req.getRequestURI(),
						"Messages sent",
						PeerHandler
							.PeerHandlerComparator
							.MESSAGES_SENT_COMBINED));
				psw.println(
					renderSortingLink(
						req.getRequestURI(),
						"Messages received",
						PeerHandler
							.PeerHandlerComparator
							.MESSAGES_RECEIVED_COMBINED));
			} else {
				psw.println(
					renderSortingLink(
						req.getRequestURI(),
						"Messages succeessfully sent",
						PeerHandler
							.PeerHandlerComparator
							.MESSAGES_SENT_SUCCESSFULLY));
				psw.println(
					renderSortingLink(
						req.getRequestURI(),
						"Messages send failed",
						PeerHandler
							.PeerHandlerComparator
							.MESSAGES_SENDFAILURE));
				psw.println(
					renderSortingLink(
						req.getRequestURI(),
						"Messages succeessfully received",
						PeerHandler
							.PeerHandlerComparator
							.MESSAGES_RECEIVED_SUCCESSFULLY));
				psw.println(
					renderSortingLink(
						req.getRequestURI(),
						"Messages receive failed",
						PeerHandler
							.PeerHandlerComparator
							.MESSAGES_RECEIVEFAILURE));
			}
		}
		psw.println(
			renderSortingLink(
				req.getRequestURI(),
				"Idle time",
				PeerHandler.PeerHandlerComparator.IDLETIME));
		psw.println(
			renderSortingLink(
				req.getRequestURI(),
				"Life time",
				PeerHandler.PeerHandlerComparator.LIFETIME));
		psw.println(
			renderSortingLink(
				req.getRequestURI(),
				"Requests sent",
				PeerHandler.PeerHandlerComparator.REQUESTS_SENT));
		psw.println(
			renderSortingLink(
				req.getRequestURI(),
				"Requests received",
				PeerHandler.PeerHandlerComparator.REQUESTS_RECEIVED));
		if (viewLevel == 1)
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Outbound connection success ratio",
					PeerHandler
						.PeerHandlerComparator
						.CONNECTION_SUCCESS_RATIO));
		else if (viewLevel > 1) {
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Outbound connection attempts",
					PeerHandler.PeerHandlerComparator.CONNECTION_ATTEMPTS));
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Outbound connection successes",
					PeerHandler
						.PeerHandlerComparator
						.CONNECTION_SUCCESSES));
		}
		if (viewLevel > 10) {
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Outbound connections",
					PeerHandler
						.PeerHandlerComparator
						.CONNECTIONS_OPEN_OUTBOUND));
			psw.println(
				renderSortingLink(
					req.getRequestURI(),
					"Inbound connections",
					PeerHandler
						.PeerHandlerComparator
						.CONNECTIONS_OPEN_INBOUND));
		}
		titleBoxTemplate.set("CONTENT", sw.toString());
		titleBoxTemplate.toHtml(pw);
	}

	protected String renderSortingLink(
		String scriptName,
		String label,
		int clickSortingMode) {
		String imageURL =
			HtmlTemplate.defaultTemplateSet
				+ "/s_ar_"
				+ (iSortingMode < 0 ? "up" : "down")
				+ ".png";
		Dimension size = ImageServlet.getSize(imageURL);
		String sImgClause;
		if(size == null) {
		    Core.logger.log(this, "renderSortingLink cannot get dimensions for "+
		            imageURL, new Exception(), Logger.ERROR);
		    sImgClause = "";
		} else {
		    sImgClause =
		        "<img src=\"/servlet/images/"
					+ imageURL
					+ "\" alt=\"\" width=\""
					+ size.getWidth()
					+ "\" height=\""
					+ size.getHeight()
                    + "\" />";
		}
		return "<p class=\"ocmSortingLink\"><a href='"
			+ scriptName
			+ "?setSorting="
			+ (iSortingMode == clickSortingMode
				? ("-" + clickSortingMode)
				: new Long(clickSortingMode).toString())
			+ "'>"
			+ (Math.abs(iSortingMode) == clickSortingMode ? sImgClause : "")
			+ " "
			+ label.replaceAll(" ", "&nbsp;")
			+ "</a></p>";
	}

	private boolean sendPeerRoutingGraph(
		String graph,
		HttpServletRequest req,
		HttpServletResponse resp)
		throws IOException {
		String id = req.getParameter("identity");
		if (id == null) {
			resp.sendError(
				HttpServletResponse.SC_BAD_REQUEST,
				"No identity specified");
			return true;
		}
		DSAIdentity i = null;
		try {
			i = new DSAIdentity(freenet.crypt.Global.DSAgroupC, id);
		} catch (Throwable t) {
			Core.logger.log(
				this,
				"Caught exception creating Identity from " + id,
				t,
				Logger.NORMAL);
			resp.sendError(
				HttpServletResponse.SC_BAD_REQUEST,
				"Invalid identity specified");
			return true;
		}
		// FIXME: if nodes generate their own groups, this will need to be
		// revised... group would be humongous, so maybe we could use
		// fingerprint
		RoutingTable rt = Main.origRT;
		if (!(rt instanceof NGRoutingTable)) {
			resp.sendError(
				HttpServletResponse.SC_BAD_REQUEST,
				"Not an NGRoutingTable");
			return true;
		}
		NGRoutingTable ngrt = (NGRoutingTable) rt;
		int width = 400;
		String pwidth = req.getParameter("width");
		if (pwidth != null) {
			try {
				width = Integer.parseInt(pwidth);
			} catch (NumberFormatException ex) {
			    // User error, use default
			}
		}
		int height = 140;
		String pheight = req.getParameter("height");
		if (pheight != null) {
			try {
				height = Integer.parseInt(pheight);
			} catch (NumberFormatException ex) {
			    // User error, use default
			}
		}
		resp.setContentType("image/bmp");
		Bitmap bmp = new Bitmap(width, height);
		GDSList gdsl;
		if (graph.equalsIgnoreCase("all")
			|| graph.equalsIgnoreCase("combined")) {
			NodeEstimator est = ngrt.getEstimator(i);
			if (est == null) {
				resp.sendError(
					HttpServletResponse.SC_BAD_REQUEST,
					"Invalid identity '" + i + "' specified");
				return true;
			}
			if (graph.equalsIgnoreCase("all"))
				gdsl = est.createGDSL2(width, rtColors);
			else
				//graph == "combined"
				gdsl = est.createGDSL(width, 0, new Color(0, 0, 0));
		} else {
			KeyspaceEstimator e = ngrt.getEstimator(i, graph);
			if (e == null) {
				resp.sendError(
					HttpServletResponse.SC_BAD_REQUEST,
					"Invalid graph '"
						+ graph
						+ "' or identity '"
						+ i
						+ "' specified");
				return true;
			}
			gdsl =
				e.createGDSL(
					width,
					false,
					(Color) rtColors.get(graph));
		}
		if(gdsl != null)
		    gdsl.drawGraphsOnImage(bmp);
		DibEncoder.drawBitmap(bmp, resp);
		return true;
	}

	public boolean renderFile(
		String file,
		HttpServletRequest req,
		HttpServletResponse resp)
		throws IOException {
		return sendPeerRoutingGraph(file, req, resp);
	}
}
