/* -*- Mode: java; c-basic-indent: 4; indent-tabs-mode: nil -*- */
package freenet.client.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.ContactCounter;
import freenet.Core;
import freenet.DSAIdentity;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.diagnostics.Diagnostics;
import freenet.diagnostics.DiagnosticsFormat;
import freenet.diagnostics.FieldSetFormat;
import freenet.diagnostics.GraphDiagnosticsFormat;
import freenet.diagnostics.GraphHtmlDiagnosticsFormat;
import freenet.diagnostics.GraphRange;
import freenet.diagnostics.GraphRangeDiagnosticsFormat;
import freenet.diagnostics.HtmlDiagnosticsFormat;
import freenet.diagnostics.HtmlIndexFormat;
import freenet.diagnostics.RowDiagnosticsFormat;
import freenet.node.LoadStats;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.NodeReference;
import freenet.node.ds.FSDataStore;
import freenet.node.rt.CPAlgoRoutingTable;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.NGRoutingTable;
import freenet.node.rt.NodeEstimator;
import freenet.node.rt.RTDiagSnapshot;
import freenet.node.rt.RecentRequestHistory;
import freenet.node.rt.RoutingTable;
import freenet.support.KeyHistogram;
import freenet.support.KeySizeHistogram;
import freenet.support.Logger;
import freenet.support.PropertyArray;
import freenet.support.StringMap;
import freenet.support.URLEncodedFormatException;
import freenet.support.graph.BitmapEncoder;
import freenet.support.graph.Color;
import freenet.support.graph.DibEncoder;
import freenet.support.graph.MinGraphDataSet;
import freenet.support.io.WriteOutputStream;
import freenet.support.servlet.HtmlTemplate;
import freenet.support.servlet.http.HttpServletResponseImpl;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.HeapSorter;

/*
 * This code is distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * Servlet to display and allow downloading of node references as the node is
 * running.
 * <p>
 * Example freenet.conf segment to run this servlet:
 * 
 * <pre>
 * 
 *   nodestatus.class=freenet.client.http.NodeStatusServlet # Change port number if you like. nodestatus.port=8889 # Make sure that the servlet is listed in the services line. services=fproxy,nodestatus
 *  
 * </pre>
 * 
 * </p>
 * 
 * @author giannij
 */
public class NodeStatusServlet extends HttpServlet {

    private static final NumberFormat nfp;

    private static final NumberFormat nf0;

    private static final NumberFormat nf1;

    private static final NumberFormat nf03;

    private static final NumberFormat nf3;
    static {
        nfp = NumberFormat.getPercentInstance();
        nfp.setMinimumFractionDigits(0);
        nfp.setMaximumFractionDigits(1);
        nf0 = NumberFormat.getInstance();
        nf0.setMinimumFractionDigits(0);
        nf0.setMaximumFractionDigits(0);
        nf0.setGroupingUsed(false);
        nf1 = NumberFormat.getInstance();
        nf1.setMaximumFractionDigits(1);
        nf1.setMinimumFractionDigits(1);
        nf1.setGroupingUsed(false);
        nf03 = NumberFormat.getInstance();
        nf03.setMinimumFractionDigits(0);
        nf03.setMaximumFractionDigits(3);
        nf03.setGroupingUsed(false);
        nf3 = NumberFormat.getInstance();
        nf3.setMaximumFractionDigits(3);
        nf3.setMinimumFractionDigits(3);
        nf3.setGroupingUsed(false);
    }

    public void init() {
        ServletContext context = getServletContext();
        node = (Node) context.getAttribute("freenet.node.Node");
        init(node);
    }

    public void init(Node n) {
        node = n;
        if (node != null) {
            rt = node.rt;
            diagnostics = Node.diagnostics;
            inboundContacts = Node.inboundContacts;
            outboundContacts = Node.outboundContacts;
            inboundRequests = Node.inboundRequests;
            outboundRequests = Node.outboundRequests;
            loadStats = node.loadStats;
        }
        try {
            simpleTemplate = HtmlTemplate.createTemplate("SimplePage.html");
            infoTemplate = HtmlTemplate.createTemplate("InfoServletTmpl.html");
        } catch (IOException e) {
            Core.logger.log(this, "Couldn't load templates", e, Logger.NORMAL);
        }
    }

    HtmlTemplate simpleTemplate = null;

    HtmlTemplate infoTemplate = null;

    private final static String MSG_NO_REQUEST_DIST = "# Data for the inbound request distribution isn't being logged. \n"
            + "#  To enable logging set: \n"
            + "#   logInboundRequestDist=true \n"
            + "# in your freenet.conf / freenet.ini file.";

    private final static String MSG_NO_INBOUND_INSERT_DIST = "# Data for the inbound insert request distribution isn't being logged. \n"
            + "#  To enable logging set: \n"
            + "#   logInboundInsertRequestDist=true \n"
            + "# in your freenet.conf / freenet.ini file.";

    private final static String MSG_NO_INSERT_SUCCESS_DIST = "# Data for the distribution of externally originated successfully inserted keys isn't being logged\n"
            + "#  To enable logging set: \n"
            + "#   logSuccessfulInsertRequestDist=true \n"
            + "# in your freenet.conf / freenet.ini file.";

    private final static String MSG_OOPS = "# Coding error!";

    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        if ((rt == null)) {
            sendError(
                    resp,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Couldn't access the freenet.node.Node instance.  "
                            + "This servlet must be run in the same JVM as the node.");
            return;
        }

        try {
            String uri = freenet.support.URLDecoder.decode(req.getRequestURI());
            String baseURL = req.getContextPath() + req.getServletPath();
            if ((baseURL.length() > 0) && (!baseURL.endsWith("/"))) {
                baseURL += "/";
            }

            // FIXME: this is a monster. Most of the histograms could be
            // parameterised and code reduced significantly

            if (uri.endsWith("/loadStats.txt")) {
                sendLoadStats(resp);
                return;
            }

            if (uri.endsWith("/inboundContacts.txt")) {
                sendPerHostStats(resp, "inboundContacts");
                return;
            }

            if (uri.endsWith("/outboundContacts.txt")) {
                sendPerHostStats(resp, "outboundContacts");
                return;
            }

            if (uri.endsWith("/inboundRequests.txt")) {
                sendPerHostStats(resp, "inboundRequests");
                return;
            }

            if (uri.endsWith("/outboundRequests.txt")) {
                sendPerHostStats(resp, "outboundRequests");
                return;
            }

            if (uri.endsWith("connected_version_histogram.txt")) {
                sendVersionHistogram(resp, false, false);
                return;
            }

            if (uri.endsWith("connected_version_data.txt")) {
                sendVersionHistogram(resp, true, false);
                return;
            }

            if (uri.endsWith("version_histogram.txt")) {
                sendVersionHistogram(resp, false, true);
                return;
            }

            if (uri.endsWith("version_data.txt")) {
                sendVersionHistogram(resp, true, true);
                return;
            }

            if (uri.endsWith("key_histogram.txt")) {
                sendRTHistogram(resp, false, false);
                return;
            }

            if (uri.endsWith("key_histogram_detail.txt")) {
                sendRTHistogram(resp, false, true);
                return;
            }

            if (uri.endsWith("ds_histogram.txt")) {
                sendDSHistogram(resp, false, false);
                return;
            }

            if (uri.endsWith("ds_histogram_detail.txt")) {
                sendDSHistogram(resp, false, true);
                return;
            }

            if (uri.endsWith("ds_size_histogram.txt")) {
                sendDSSizeHistogram(resp, false);
                return;
            }

            if (uri.endsWith("inbound_request_histogram.txt")) {
                int[] bins = null;
                if (Node.requestDataDistribution != null) {
                    bins = Node.requestDataDistribution.getBins();
                }
                sendKeyHistogram(
                        resp,
                        false,
                        bins,
                        "Histogram of requested keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_REQUEST_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_insert_histogram.txt")) {
                int[] bins = null;
                if (Node.requestInsertDistribution != null) {
                    bins = Node.requestInsertDistribution.getBins();
                }
                sendKeyHistogram(
                        resp,
                        false,
                        bins,
                        "Histogram of insert attempted keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_INBOUND_INSERT_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_request_histogram_detail.txt")) {
                int[] bins = null;
                if (Node.requestDataDistribution != null) {
                    bins = Node.requestDataDistribution.getBiggerBins();
                }
                sendKeyHistogram(
                        resp,
                        false,
                        bins,
                        "Histogram of requested keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_REQUEST_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_insert_histogram_detail.txt")) {
                int[] bins = null;
                if (Node.requestInsertDistribution != null) {
                    bins = Node.requestInsertDistribution.getBiggerBins();
                }
                sendKeyHistogram(
                        resp,
                        false,
                        bins,
                        "Histogram of attempted insert keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_INBOUND_INSERT_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_success_histogram.txt")) {
                int[] bins = null;
                if (Node.successDataDistribution != null) {
                    bins = Node.successDataDistribution.getBins();
                }
                sendKeyHistogram(
                        resp,
                        false,
                        bins,
                        "Histogram of successful externally requested keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_REQUEST_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_insert_success_histogram.txt")) {
                int[] bins = null;
                if (Node.successInsertDistribution != null) {
                    bins = Node.successInsertDistribution.getBins();
                }
                sendKeyHistogram(
                        resp,
                        false,
                        bins,
                        "Histogram of externally originated successfully inserted keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_INSERT_SUCCESS_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_success_histogram_detail.txt")) {
                int[] bins = null;
                if (Node.successDataDistribution != null) {
                    bins = Node.successDataDistribution.getBiggerBins();
                }
                sendKeyHistogram(
                        resp,
                        false,
                        bins,
                        "Histogram of successfully requested keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_REQUEST_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_insert_success_histogram_detail.txt")) {
                int[] bins = null;
                if (Node.successInsertDistribution != null) {
                    bins = Node.successInsertDistribution.getBiggerBins();
                }
                sendKeyHistogram(
                        resp,
                        false,
                        bins,
                        "Histogram of externally originated successfully inserted keys.",
                        "This count has nothing to do with the keys in your datastore",
                        MSG_NO_INSERT_SUCCESS_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("key_histogram_data.txt")) {
                sendRTHistogram(resp, true, false);
                return;
            }

            if (uri.endsWith("key_histogram_data_detail.txt")) {
                sendRTHistogram(resp, true, true);
                return;
            }

            if (uri.endsWith("ds_histogram_data.txt")) {
                sendDSHistogram(resp, true, false);
                return;
            }

            if (uri.endsWith("ds_histogram_data_detail.txt")) {
                sendDSHistogram(resp, true, true);
                return;
            }

            if (uri.endsWith("ds_size_histogram_data.txt")) {
                sendDSSizeHistogram(resp, true);
                return;
            }

            if (uri.endsWith("inbound_request_histogram_data.txt")) {
                int[] bins = null;
                if (Node.requestDataDistribution != null) {
                    bins = Node.requestDataDistribution.getBins();
                }

                sendKeyHistogram(
                        resp,
                        true,
                        bins,
                        "Histogram of requested keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_REQUEST_DIST, "keys", false);

                return;
            }

            if (uri.endsWith("inbound_insert_histogram_data.txt")) {
                int[] bins = null;
                if (Node.requestInsertDistribution != null) {
                    bins = Node.requestInsertDistribution.getBins();
                }

                sendKeyHistogram(
                        resp,
                        true,
                        bins,
                        "Histogram of attempted insert keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_INBOUND_INSERT_DIST, "keys", false);

                return;
            }

            if (uri.endsWith("inbound_request_histogram_data_detail.txt")) {
                int[] bins = null;
                if (Node.requestDataDistribution != null) {
                    bins = Node.requestDataDistribution.getBiggerBins();
                }

                sendKeyHistogram(
                        resp,
                        true,
                        bins,
                        "Histogram of requested keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_REQUEST_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_insert_histogram_data_detail.txt")) {
                int[] bins = null;
                if (Node.requestInsertDistribution != null) {
                    bins = Node.requestDataDistribution.getBiggerBins();
                }

                sendKeyHistogram(
                        resp,
                        true,
                        bins,
                        "Histogram of attempted insert keys.",
                        "This count has nothing to do with the keys in your datastore.",
                        MSG_NO_INBOUND_INSERT_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_success_histogram_data.txt")) {
                int[] bins = null;
                if (Node.successDataDistribution != null) {
                    bins = Node.successDataDistribution.getBins();
                }

                sendKeyHistogram(
                        resp,
                        true,
                        bins,
                        "Histogram of successfully requested keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_REQUEST_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_insert_success_histogram_data.txt")) {
                int[] bins = null;
                if (Node.successInsertDistribution != null) {
                    bins = Node.successInsertDistribution.getBins();
                }

                sendKeyHistogram(
                        resp,
                        true,
                        bins,
                        "Histogram of externally originated successfully inserted keys",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_INSERT_SUCCESS_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("inbound_success_histogram_data_detail.txt")) {
                int[] bins = null;
                if (Node.successDataDistribution != null) {
                    bins = Node.successDataDistribution.getBiggerBins();
                }

                sendKeyHistogram(
                        resp,
                        true,
                        bins,
                        "Histogram of successfully requested keys.",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_REQUEST_DIST, "keys", false);
                return;
            }

            if (uri
                    .endsWith("inbound_insert_success_histogram_data_detail.txt")) {
                int[] bins = null;
                if (Node.successInsertDistribution != null) {
                    bins = Node.successInsertDistribution.getBiggerBins();
                }

                sendKeyHistogram(
                        resp,
                        true,
                        bins,
                        "Histogram of externally originated successfully inserted keys",
                        "This count has nothing to do with keys in your datastore",
                        MSG_NO_INSERT_SUCCESS_DIST, "keys", false);
                return;
            }

            if (uri.endsWith("psuccess_data.txt")) {
                sendPSuccessList(resp, false, false);
                return;
            }

            if (uri.endsWith("psuccess_data_detail.txt")) {
                sendPSuccessList(resp, true, false);
                return;
            }

            if (uri.endsWith("psuccess_insert_data.txt")) {
                sendPSuccessList(resp, false, true);
                return;
            }

            if (uri.endsWith("psuccess_insert_data_detail.txt")) {
                sendPSuccessList(resp, true, true);
                return;
            }

            if (uri.endsWith("myref.txt")) {
                sendMyNodeReference(resp);
                return;
            }

            if (uri.endsWith("noderefs.txt")) {
                sendRefList(req, resp);
            } else if (uri.endsWith("nodestatus.html")) {
                sendStatusPage(resp);
            } else if (uri.endsWith("tickerContents.html")) {
                sendTickerContents(resp);
            } else if (uri.endsWith("nodeDetails.html")) {
                String id = req.getParameter("identity");
                if (id != null) {
                    DSAIdentity i = null;
                    try {
                        i = new DSAIdentity(freenet.crypt.Global.DSAgroupC, id);
                    } catch (Throwable t) {
                        Core.logger.log(this, "Caught " + t
                                + " creating Identity from " + id,
                                Logger.NORMAL);
                        sendIndexPage(resp, baseURL);
                    }
                    // FIXME: if nodes generate their own groups, this will
                    // need to be revised... group would be humongous, so maybe
                    // we could use fingerprint
                    if (i != null) sendNodePage(i, req, resp);
                } else {
                    sendIndexPage(resp, baseURL);
                }
            } else if (uri.endsWith("nodeGraph.bmp")) {
                String id = req.getParameter("identity");
                if (id != null) {
                    DSAIdentity i = null;
                    try {
                        i = new DSAIdentity(freenet.crypt.Global.DSAgroupC, id);
                    } catch (Throwable t) {
                        Core.logger.log(this, "Caught " + t
                                + " creating Identity from " + id,
                                Logger.NORMAL);
                        sendIndexPage(resp, baseURL);
                    }
                    // FIXME: if nodes generate their own groups, this will
                    // need to be revised... group would be humongous, so maybe
                    // we could use fingerprint
                    if (i != null) sendNodeGraph(i, req, resp);
                } else {
                    sendIndexPage(resp, baseURL);
                }
            } else if (uri.endsWith("global.bmp")) {
                sendGlobalGraph(req, resp);
            } else if (uri.endsWith("routing.bmp")) {
                sendRoutingGraph(req, resp);
            } else if (uri.endsWith("routing.html")) {
                sendRoutingPage(req, resp);
            } else if (uri.endsWith("ocmContents.html")) {
                sendOcmContents(resp, req);
            } else if (uri.endsWith("diagnostics/index.html")) {
                sendDiagnosticsIndex(resp);
            } else if (uri.indexOf(baseURL + "diagnostics/graphs") != -1) {
                sendGraphData(req, resp);
            } else if (uri.indexOf(baseURL + "diagnostics/") != -1) {
                // REDFLAG: clean up handling of bad urls
                int pos = uri.indexOf(baseURL + "diagnostics/")
                        + (baseURL + "diagnostics/").length();
                int endName = uri.indexOf("/", pos);
                String varName = uri.substring(pos, endName);
                String period = uri.substring(endName + 1);
                sendVarData(resp, varName, period);
            } else {
                sendIndexPage(resp, baseURL);
            }
        } catch (URLEncodedFormatException uee) {
            // hmmm... underwhelming
            throw new IOException(uee.toString());
        }
    }

    private void reportNodeStats(PrintWriter pw) throws IOException {
        if (node == null) { return; }

        StringWriter contentSW = new StringWriter();
        PrintWriter contentPW = new PrintWriter(contentSW);

        HtmlTemplate box = null;
        try {
            box = HtmlTemplate.createTemplate("box.tpl");
        } catch (IOException e) {
            Core.logger.log(this, "Couldn't load template box.tpl", e,
                    Logger.NORMAL);
            throw e;
        }

        contentPW.println("<p>");
        contentPW.println("Uptime: &nbsp; " + getUptime() + " <br />");
        if (diagnostics != null)
                contentPW.println("Current routingTime:  "
                        + (long) diagnostics.getValue("routingTime",
                                Diagnostics.MINUTE, Diagnostics.MEAN_VALUE)
                        + "ms. <br />");

        // Thread load
        int jobs = node.activeJobs();

        // It's not just thread based. There's also a hard
        // rate limit.
        StringBuffer whyRejectingRequests = new StringBuffer(500);
        boolean rejectingRequests = node.rejectingRequests(
                whyRejectingRequests, true);
        boolean rejectingMostRequests = rejectingRequests ? false : node
                .rejectingRequests(whyRejectingRequests, false);
        StringBuffer whyRejectingConnections = new StringBuffer(500);
        boolean rejectingConnections = node
                .rejectingConnections(whyRejectingConnections);

        if (jobs > -1) {
            String cssClass = "normal";
            String comment = "";
            if (rejectingConnections) {
                cssClass = "warning";
                comment = " &nbsp; <b> [Rejecting incoming connections and requests!] </b> ";
            } else if (rejectingRequests) {
                cssClass = "warning";
                comment = " &nbsp; <b> [QueryRejecting all incoming requests!] </b> ";
            } else if (rejectingMostRequests) {
                cssClass = "unimportant";
                comment = " &nbsp; <b> [QueryRejecting most incoming requests]</b> ";
            }

            String msg = Integer.toString(jobs);

            int maxthreads = node.getThreadFactory().maximumThreads();
            int available = node.availableThreads();
            if (maxthreads > 0) {
                msg += " &nbsp; &nbsp; ("
                        + nfp.format((float) jobs / maxthreads) + ") ";
            }

            msg += " <span class=\"" + cssClass + "\"> " + comment
                    + " </span><br />";

            contentPW.println("Pooled threads running jobs:  " + msg);
            contentPW.println("Pooled threads which are idle: " + available
                    + "<br />");

            if (rejectingConnections || rejectingRequests) {
                contentPW
                        .println("It's normal for the node to sometimes reject connections or requests");
                contentPW
                        .println("for a limited period.  If you're seeing rejections continuously the node");
                contentPW
                        .println("is overloaded or something is wrong (i.e. a bug).");
            }

        }
        StringBuffer why = new StringBuffer(500);
        float f = node.estimatedLoad(why, true);
        contentPW.println("Current estimated load for rate limiting:  "
                + nfp.format(f) + ". <br />" + why.toString() + "<br />");
        contentPW.println("Current estimated load for QueryRejecting: "
                + nfp.format(node.estimatedLoad(false)) + ". <br />");

        contentPW.println("</p>");
        box.set("CONTENT", contentSW.toString());
        box.toHtml(pw);
    }

    private final static String appendIntervalToMsg(long count, String msg,
            String singular, String plural) {

        if (count == 1) {
            return msg + " " + Long.toString(count) + " " + singular;
        } else {
            return msg + " " + Long.toString(count) + " " + plural;
        }
    }

    private String getUptime() {

        long deltat = (System.currentTimeMillis() - Node.startupTimeMs) / 1000;

        long days = deltat / 86400l;

        deltat -= days * 86400l;

        long hours = deltat / 3600l;

        deltat -= hours * 3600l;

        long minutes = deltat / 60l;

        String msg = null;
        msg = appendIntervalToMsg(days, "", "day, &nbsp; ", "days, &nbsp;");
        msg = appendIntervalToMsg(hours, msg, "hour, &nbsp; ", "hours, &nbsp; ");
        msg = appendIntervalToMsg(minutes, msg, "minute", "minutes");

        if (msg.length() == 0) { return " &nbsp; < 1 minute "; }

        return msg;
    }

    private void sendIndexPage(HttpServletResponse resp, String baseURL)
            throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");

        StringWriter menuSW = new StringWriter();
        PrintWriter menuPW = new PrintWriter(menuSW);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        HtmlTemplate infoTemplate = new HtmlTemplate(this.infoTemplate);
        infoTemplate.set("TITLE", "Node Status Info");

        // Knows to send list items.
        reportNodeStats(pw);

        // write the menu
        sendMenu(menuPW, baseURL);

        infoTemplate.set("MENU", menuSW.toString());
        infoTemplate.set("BODY", sw.toString());
        infoTemplate.toHtml(resp.getWriter());
        resp.flushBuffer();
    }

    private void sendMenu(PrintWriter pw, String baseURL) {

        StringWriter menuSW = new StringWriter();
        PrintWriter menuPW = new PrintWriter(menuSW);

        try {
            HtmlTemplate menuTemplate = HtmlTemplate
                    .createTemplate("titleBox.tpl");
            menuTemplate.set("TITLE", "Node Status");
            menuPW.println("    <h3>Routing</h3>");
            menuPW.println("    <ul>");
            menuPW.println("    <li><a href=\"" + baseURL
                    + "nodestatus.html\"> Node Reference Status </a></li>");
            if (Main.origRT instanceof NGRoutingTable)
                    menuPW.println("    <li><a href=\"" + baseURL
                            + "routing.html\"> Routing Summary </a></li>");
            menuPW.println("    </ul>");

            menuPW.println("    <h3>Diagnostics</h3>");
            menuPW
                    .println("    <ul><li> <a href=\""
                            + baseURL
                            + "diagnostics/index.html\"> Diagnostics Values </a></li></ul>");

            menuPW.println("    <h3>Histograms</h3>");
            menuPW.println("        <ul>");
            if (Main.origRT instanceof CPAlgoRoutingTable) {
                menuPW.println("            <li> <a href=\"" + baseURL
                        + "key_histogram.txt\">" + "Routing table</a>"
                        + " <a class=\"histogramDetailLink\" href=\"" + baseURL
                        + "key_histogram_detail.txt\">(detail)</a> ");
                menuPW
                        .println("            <a class=\"histogramDetailLink\" href= \""
                                + baseURL
                                + "key_histogram_data.txt\">(flat ascii)</a>"
                                + " <a class=\"histogramDetailLink\" href=\""
                                + baseURL
                                + "key_histogram_data_detail.txt\">(detail)</a></li>");
            }
            menuPW.println("            <li> <a href=\"" + baseURL
                    + "inbound_request_histogram.txt\">"
                    + "Inbound requests</a>"
                    + " <a class=\"histogramDetailLink\" href=\"" + baseURL
                    + "inbound_request_histogram_detail.txt\">(detail)</a> ");
            menuPW
                    .println("            <a class=\"histogramDetailLink\" href= \""
                            + baseURL
                            + "inbound_request_histogram_data.txt\">(flat ascii)</a>"
                            + " <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "inbound_request_histogram_data_detail.txt\">(detail)</a></li>");

            menuPW.println("            <li> <a href=\"" + baseURL
                    + "inbound_insert_histogram.txt\">" + "Inbound inserts</a>"
                    + " <a class=\"histogramDetailLink\" href=\"" + baseURL
                    + "inbound_insert_histogram_detail.txt\">(detail)</a> ");
            menuPW
                    .println("            <a class=\"histogramDetailLink\" href= \""
                            + baseURL
                            + "inbound_insert_histogram_data.txt\">(flat ascii)</a>"
                            + " <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "inbound_insert_histogram_data_detail.txt\">(detail)</a></li>");

            menuPW.println("            <li> <a href=\"" + baseURL
                    + "ds_histogram.txt\">" + "Datastore</a>"
                    + " <a class=\"histogramDetailLink\" href=\"" + baseURL
                    + "ds_histogram_detail.txt\">(detail)</a> ");
            menuPW
                    .println("            <a class=\"histogramDetailLink\" href= \""
                            + baseURL
                            + "ds_histogram_data.txt\">(flat ascii)</a>"
                            + " <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "ds_histogram_data_detail.txt\">(detail)</a></li>");

            menuPW
                    .println("            <li> <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "ds_size_histogram.txt\">"
                            + "Key size</a> ");
            menuPW.println("<a class=\"histogramDetailLink\" href= \""
                    + baseURL
                    + "ds_size_histogram_data.txt\">(flat ascii)</a></li>");

            menuPW.println("            <li> <a href=\"" + baseURL
                    + "inbound_success_histogram.txt\">"
                    + "Successful requests</a>"
                    + " <a class=\"histogramDetailLink\" href=\"" + baseURL
                    + "inbound_success_histogram_detail.txt\">(detail)</a> ");
            menuPW
                    .println("            <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "inbound_success_histogram_data.txt\">(flat ascii)</a>"
                            + " <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "inbound_success_histogram_data_detail.txt\">(detail)</a></li>");

            menuPW
                    .println("            <li> <a href=\""
                            + baseURL
                            + "inbound_insert_success_histogram.txt\">"
                            + "Successful inserts</a>"
                            + " <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "inbound_insert_success_histogram_detail.txt\">(detail)</a> ");
            menuPW
                    .println("            <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "inbound_insert_success_histogram_data.txt\">(flat ascii)</a>"
                            + " <a class=\"histogramDetailLink\" href=\""
                            + baseURL
                            + "inbound_insert_success_histogram_data_detail.txt\">(detail)</a></li>");

            menuPW.println("            <li> <a href=\"" + baseURL
                    + "psuccess_data.txt\">"
                    + "Request success probability</a>"
                    + " <a class=\"histogramDetailLink\" href=\"" + baseURL
                    + "psuccess_data_detail.txt\">(detail)</a></li>");

            menuPW.println("            <li> <a href=\"" + baseURL
                    + "psuccess_insert_data.txt\">"
                    + "Insert success probability</a>"
                    + " <a class=\"histogramDetailLink\" href=\"" + baseURL
                    + "psuccess_insert_data_detail.txt\">(detail)</a></li>");
            menuPW.println("            <li> <a href=\"" + baseURL
                    + "version_histogram.txt\">" + "Node versions</a>"
                    + "            <a class=\"histogramDetailLink\" href=\""
                    + baseURL + "version_data.txt\">(flat ascii)</a></li>");
            menuPW.println("            <li> <a href=\"" + baseURL
                    + "connected_version_histogram.txt\">"
                    + "Connected node versions</a>"
                    + "            <a class=\"histogramDetailLink\" href=\""
                    + baseURL
                    + "connected_version_data.txt\">(flat ascii)</a></li>");
            menuPW.println("        </ul>");

            menuPW.println("    <h3>Contact attempts</h3>");
            menuPW.println("    <ul>");
            menuPW.println("    <li> <a href=\"" + baseURL
                    + "inboundContacts.txt\"> Inbound</a> </li>");
            menuPW.println("    <li> <a href=\"" + baseURL
                    + "outboundContacts.txt\"> Outbound</a> </li>");
            menuPW.println("    </ul>");

            menuPW.println("    <h3>Requests</h3>");
            menuPW.println("    <ul>");
            menuPW.println("    <li> <a href=\"" + baseURL
                    + "inboundRequests.txt\"> Inbound</a> </li>");
            menuPW.println("    <li> <a href=\"" + baseURL
                    + "outboundRequests.txt\"> Outbound</a> </li>");
            menuPW.println("    </ul>");

            menuPW.println("    <h3>Open Connections</h3>");
            menuPW.println("    <ul>");
            menuPW.println("    <li> <a href=\"" + baseURL
                    + "ocmContents.html\"> Open Connections</a> </li>");
            menuPW.println("    </ul>");

            menuPW.println("    <h3>Ticker</h3>");
            menuPW.println("    <ul>");
            menuPW.println("    <li> <a href=\"" + baseURL
                    + "tickerContents.html\"> Ticker Contents </a> </li>");
            menuPW.println("    </ul>");

            menuPW.println("    <h3>Load stats</h3>");
            menuPW.println("    <ul>");
            menuPW.println("    <li> <a href=\"" + baseURL
                    + "loadStats.txt\">Network load</a> </li>");
            menuPW.println("    </ul>");

            menuTemplate.set("CONTENT", menuSW.toString());
            menuTemplate.toHtml(pw);
        } catch (IOException e) {
            Core.logger.log(this, "Couldn't load template titlebox.tpl", e,
                    Logger.NORMAL);
        }

    }

    private final static String drawLine(int freq) {
        String ret = "";
        for (int i = 0; i < freq; i++) {
            ret += "=";
        }
        return ret;
    }

    private final static int[] fillBins(Key[] keys, boolean detail) {
        // Thelema, I changed this back because it makes it hard to see at a
        // glance
        // what's going on. --gj
        // One *can't* see at a glance what's going on.
        // 16 buckets makes the histogram *very* deceptive,
        // as the groups you're comparing over are too big
        int[] bins;
        if (detail) {
            bins = new int[256];
        } else {
            bins = new int[16];
        }

        int i = 0;
        for (i = 0; i < keys.length; i++) {
            int binNumber;

            if (detail) {
                // most significant byte.
                binNumber = (keys[i].getVal()[0] & 0xff);
            } else {
                // most significant nibble
                binNumber = (keys[i].getVal()[0] & 0xff) >>> ((byte) 4);
            }
            bins[binNumber]++;
        }
        return bins;
    }

    private String peakValue(int[] bins, int index, float mean, String[] names) {
        // hmmm... Allow edges to count as peaks?
        int nextCount = 0;
        int prevCount = 0;

        if (index > 0) {
            prevCount = bins[index - 1];
        }

        if (index < bins.length - 1) {
            nextCount = bins[index + 1];
        }

        if ((bins[index] > prevCount) && (bins[index] > nextCount)) { return (names != null ? names[index]
                : Integer.toString(index, 16))
                + " --> (" + (bins[index] / mean) + ")\n"; }

        return null;
    }

    private void sendStats(PrintWriter pw, int[] bins, String[] names) {
        int max = 0;
        int sum = 0;
        int i = 0;
        for (i = 0; i < bins.length; i++) {
            if (bins[i] > max) {
                max = bins[i];
            }
            sum += bins[i];
        }

        float mean = ((float) sum) / bins.length;

        if (names != null) {
            pw.println("mean: " + mean);
            pw.println("");
        }

        if (mean < 1.0f) { return; }

        String text = "";
        for (i = 0; i < bins.length; i++) {
            String peakValue = peakValue(bins, i, mean, names);
            if (peakValue != null) {
                text += peakValue;
            }
        }

        if (text.length() != 0) {
            pw.println("peaks (count/mean)");
            pw.println(text);
        }
    }

    private void sendRTHistogram(HttpServletResponse resp, boolean justData,
            boolean detail) throws IOException {
        final Key[] keys = rt.getSnapshot(false).keys();
        int[] bins = fillBins(keys, detail);
        sendKeyHistogram(resp, justData, bins,
                "Histogram of keys in in fred's Routing table",
                "This count has nothing to do with keys in your datastore, "
                        + "these keys are used for routing", MSG_OOPS
                /* bins should always be non-null */
                , "keys", false);

    }

    private void sendVersionHistogram(HttpServletResponse resp,
            boolean justData, boolean includeUnconnected) throws IOException {
        final PropertyArray refData = rt.getSnapshot(false).refData();
        TreeMap map = new TreeMap();
        int longestString = 0;
        int shortestString = Integer.MAX_VALUE;
        int versionRow = refData.getPos("Node Version");
        Vector rows = refData.values();
        if (versionRow != -1) {
            int iConnRow = refData.getPos(PROP_INBOUND_CONNECTIONS);
            int oConnRow = refData.getPos(PROP_OUTBOUND_CONNECTIONS);
            int connRow = refData.getPos(PROP_CONNECTIONS);
            boolean canCheckConnected = includeUnconnected ? false
                    : ((iConnRow != -1 && oConnRow != -1) || (connRow != -1));

            for (int j = 0; j < rows.size(); j++) {
                Object[] o = (Object[]) rows.get(j);
                String s = (String) o[versionRow];
                if (s != null) {
                    int conns = 0;
                    if (canCheckConnected) {
                        if (iConnRow != -1)
                                conns += ((Integer) o[iConnRow]).intValue();
                        if (oConnRow != -1)
                                conns += ((Integer) o[oConnRow]).intValue();
                        if (connRow != -1)
                                conns += ((Integer) o[connRow]).intValue();
                        if (conns == 0) continue;
                    }
                    if (s.length() > longestString) longestString = s.length();
                    if (s.length() < shortestString)
                            shortestString = s.length();
                    Integer i = (Integer) (map.get(s));
                    if (i != null) {
                        i = new Integer(i.intValue() + 1);
                    } else
                        i = new Integer(1);
                    map.put(s, i);
                }
            }
        }
        Set s = map.keySet();
        String[] names = (String[]) s.toArray(new String[s.size()]);
        int[] bins = new int[map.size()];
        for (int i = 0; i < map.size(); i++) {
            bins[i] = ((Integer) (map.get(names[i]))).intValue();
        }
        if (shortestString != longestString) {
            for (int x = 0; x < names.length; x++) {
                String st = names[x];
                while (st.length() < longestString)
                    st += ' ';
                names[x] = st;
            }
        }
        sendKeyHistogram(
                resp,
                justData,
                bins,
                names,
                includeUnconnected ? "Histogram of node versions in fred's Routing table"
                        : "Histogram of versions of currently connected nodes in fred's Routing table",
                "", MSG_OOPS, "nodes", false);
    }

    private void sendDSHistogram(HttpServletResponse resp, boolean justData,
            boolean detail) throws IOException {

        FSDataStore ds = (FSDataStore) node.ds;
        KeyHistogram histogram = ds.getHistogram();

        sendKeyHistogram(resp, justData, detail ? histogram.getBiggerBins()
                : histogram.getBins(),
                "Histogram of keys in in fred's data store",
                "These are the keys to the data in your node's "
                        + "local cache (DataStore)", MSG_OOPS /*
                                                               * bins should
                                                               * always be
                                                               * non-null
                                                               */
                , "keys", false);

    }

    private void sendDSSizeHistogram(HttpServletResponse resp, boolean justData)
            throws IOException {

        FSDataStore ds = (FSDataStore) node.ds;
        KeySizeHistogram histogram = ds.getSizeHistogram();

        sendKeyHistogram(resp, justData, histogram.getBins(),
                "Histogram of sizes of keys in fred's data store",
                "These are the numbers of keys in your DataStore"
                        + " of (roughly) each size", MSG_OOPS /*
                                                               * bins should
                                                               * always be
                                                               * non-null
                                                               */
                , "keys", true);
    }

    private void sendPSuccessList(HttpServletResponse resp, boolean detail,
            boolean inserts) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        PrintWriter pw = resp.getWriter();
        pw.println("Probability of success of an incoming "
                + (inserts ? "insert" : "request"));
        if (inserts) {
            boolean b = false;
            if (Node.successInsertDistribution == null) {
                pw.println(MSG_NO_INSERT_SUCCESS_DIST);
                b = true;
            }
            if (Node.requestInsertDistribution == null) {
                pw.println(MSG_NO_INBOUND_INSERT_DIST);
                b = true;
            }
            if (b) {
                resp.flushBuffer();
                return;
            }
        }
        DateFormat df = DateFormat.getDateTimeInstance();
        String date = df.format(new Date(System.currentTimeMillis()));
        pw.println(date);
        float pMax = 0;
        int iMax = Node.binLength(detail);
        for (int i = 0; i < iMax; i++) {
            float p = Node.pSuccess(i, detail, inserts);
            if (Float.isNaN(p)) {
                pw.println(Integer.toString(i, 16) + " | -");
            } else {
                pw.println(Integer.toString(i, 16) + " | " + p);
            }
            if (p > pMax) pMax = p;
        }

        pw.println("");
        pw.println("Max: " + pMax);
        int x = Node.binMostSuccessful(detail, inserts);
        if (x != -1) pw.println("Most successful: " + Integer.toString(x, 16));
        resp.flushBuffer();
    }

    private void sendKeyHistogram(HttpServletResponse resp, boolean justData,
            int[] bins, String title, String comment, String complaint,
            String commodity, boolean exponential) throws IOException {
        sendKeyHistogram(resp, justData, bins, null, title, comment, complaint,
                commodity, exponential);
    }

    private void sendKeyHistogram(HttpServletResponse resp, boolean justData,
            int[] bins, String[] names, String title, String comment,
            String complaint, String commodity, boolean exponential)
            throws IOException {

        if (bins == null) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/plain");
            PrintWriter pw = resp.getWriter();
            pw.println(complaint);
            resp.flushBuffer();
            return;
        }

        int maximum = 0;
        int count = 0;
        int i = 0;
        for (i = 0; i < bins.length; i++) {
            count += bins[i];
            if (maximum < bins[i]) {
                maximum = bins[i];
            }
        }

        double scale = 1.0f;
        if (maximum > 64) {
            // REDFLAG: test
            // Scale factor for ASCII limits line lengths to 64
            scale = 64.0f / maximum;
        }

        DateFormat df = DateFormat.getDateTimeInstance();
        String date = df.format(new Date(System.currentTimeMillis()));

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        PrintWriter pw = resp.getWriter();

        if (justData) {
            pw.println("# " + title);
            pw.println("# " + date);
            pw.println("# " + commodity + ": " + count);

        } else {
            pw.println(title);
            pw.println(comment);
            pw.println(date);
            pw.println(commodity + ": " + count);
            pw.println("scale factor: " + scale
                    + " (This is used to keep lines < 64 characters)");
            pw.println("");
        }
        for (i = 0; i < bins.length; i++) {
            String line;
            if (names != null) {
                line = names[i];
            } else if (exponential) {
                line = shortPowerString(10 + i);
                if (i == (bins.length - 1)) {
                    line += "+";
                }

            } else {
                line = Integer.toString(i, 16);
            }

            if (justData) {
                line += "\t" + bins[i];
            } else {
                if (!exponential || (i != (bins.length - 1))) line += " ";
                while (line.length() < 5) {
                    line = " " + line;
                }

                line += "|" + drawLine(((int) (bins[i] * scale)));
            }
            pw.println(line);
        }

        pw.println("");

        if (!justData) {
            sendStats(pw, bins, names);
        }

        resp.flushBuffer();
    }

    private String shortPowerString(int power) {
        int x = power % 10;
        if (x == 10) x = 0;
        String ret = Integer.toString(1 << x);
        char SI[] = { 'k', 'M', 'G', 'T', 'P', 'E'};
        if (power >= 10) {
            ret += SI[(power / 10) - 1];
        }

        return ret;
    }

    private void sendNodePage(Identity i, HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        RoutingTable rt = Main.origRT;
        if (rt instanceof NGRoutingTable) {
            NGRoutingTable ngrt = (NGRoutingTable) rt;
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            HtmlTemplate template = new HtmlTemplate(this.simpleTemplate);
            resp.setContentType("text/html");

            ngrt.toHtml(i, pw, "nodeGraph.bmp?identity="
                    + ((DSAIdentity) i).getYAsHexString() + "&estimator=");
            NodeEstimator ne = ngrt.getEstimator(i);
            if (ne == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                        "No such estimator");
                return;
            }
            NodeReference ref = ne.getReference();
            String name = ne.getIdentity().fingerprintToString();
            if (ref != null) name = ref.firstPhysicalToString() + " : " + name;
            template.set("TITLE", "Node status: " + name);
            template.set("BODY", sw.toString());
            template.toHtml(resp.getWriter());
            resp.flushBuffer();
        } else {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Not an NGRoutingTable");
        }
    }

    private void sendNodeGraph(Identity i, HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        RoutingTable rt = Main.origRT;
        if (rt instanceof NGRoutingTable) {
            NGRoutingTable ngrt = (NGRoutingTable) rt;
            int width = 640;
            String pwidth = req.getParameter("width");
            if (pwidth != null) {
                try {
                    width = Integer.parseInt(pwidth);
                } catch (NumberFormatException ex) {
                    width = 640;
                }
            }
            int height = 480;
            String pheight = req.getParameter("height");
            if (pheight != null) {
                try {
                    height = Integer.parseInt(pheight);
                } catch (NumberFormatException ex) {
                    height = 480;
                }
            }
            String graph = req.getParameter("estimator");
            if (graph == null)
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "No graph name specified");
            if (graph.equalsIgnoreCase("composite")) {
                NodeEstimator e = ngrt.getEstimator(i);
                if (e == null)
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "Invalid identity '" + i + "' specified");
                else {
                    resp.setContentType("image/bmp");
                    e.getHTMLReportingTool().drawCombinedGraphBMP(width,
                            height, resp);
                }
            } else {
                if (graph.equalsIgnoreCase("overlayedcomposite")) {
                    ngrt.drawGraphBMP(width, height, resp, i);
                } else {
                    KeyspaceEstimator e = ngrt.getEstimator(i, graph);

                    String clip = req.getParameter("clippoints");
                    boolean clippoints = false;
                    if (clip != null
                            && (clip.equalsIgnoreCase("true") || clip
                                    .equalsIgnoreCase("yes")))
                            clippoints = true;
                    resp.setContentType("image/bmp");
                    e.getHTMLReportingTool().drawGraphBMP(width, height,
                            !clippoints, resp);
                }
            }
        } else {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Not an NGRoutingTable");
        }
    }

    private void sendGlobalGraph(HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        RoutingTable rt = Main.origRT;
        if (rt instanceof NGRoutingTable) {
            NGRoutingTable ngrt = (NGRoutingTable) rt;
            String type = req.getParameter("type");
            KeyspaceEstimator e = null;
            if (type != null) {
                if (type.equalsIgnoreCase("rate"))
                    e = ngrt.getGlobalTransferRateEstimator();
                else if (type.equalsIgnoreCase("dnf"))
                    e = ngrt.getSingleHopDataNotFoundEstimator();
                else if (type.equalsIgnoreCase("transferfailed"))
                        e = ngrt.getSingleHopTransferFailedEstimator();
            }
            if (e == null) e = ngrt.getGlobalSearchTimeEstimator();
            int width = 640;
            String pwidth = req.getParameter("width");
            if (pwidth != null) {
                try {
                    width = Integer.parseInt(pwidth);
                } catch (NumberFormatException ex) {
                    width = 640;
                }
            }
            int height = 200;
            String pheight = req.getParameter("height");
            if (pheight != null) {
                try {
                    height = Integer.parseInt(pheight);
                } catch (NumberFormatException ex) {
                    height = 480;
                }
            }
            String clip = req.getParameter("clippoints");
            boolean clippoints = false;
            if (clip != null
                    && (clip.equalsIgnoreCase("true") || clip
                            .equalsIgnoreCase("yes"))) clippoints = true;
            resp.setContentType("image/bmp");
            e.getHTMLReportingTool().drawGraphBMP(width, height, !clippoints,
                    resp);
        } else {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Not an NGRoutingTable");
        }
    }

    Color[] routingGraphColors = new Color[] { new Color(255, 0, 0),
            new Color(0, 255, 0), new Color(0, 0, 255), new Color(255, 255, 0),
            new Color(255, 0, 255), new Color(0, 255, 255), new Color(0, 0, 0),
            new Color(128, 128, 128), new Color(128, 0, 0),
            new Color(0, 128, 0), new Color(0, 0, 128), new Color(128, 128, 0),
            new Color(128, 0, 128), new Color(0, 128, 128)};

    // Holds the dataset used for generating the graph
    // only refreshed inside sendRoutingPage()
    private int mGDSCount = 5;

    private MinGraphDataSet routingDataSet = null;

    private void sendRoutingGraph(HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        RoutingTable rt = Main.origRT;
        if (rt instanceof NGRoutingTable) {
            NGRoutingTable ngrt = (NGRoutingTable) rt;
            int width = 640;
            String pwidth = req.getParameter("width");
            if (pwidth != null) {
                try {
                    width = Integer.parseInt(pwidth);
                } catch (NumberFormatException ex) {
                }
            }
            int height = 200;
            String pheight = req.getParameter("height");
            if (pheight != null) {
                try {
                    height = Integer.parseInt(pheight);
                } catch (NumberFormatException ex) {
                }
            }

            resp.setContentType("image/bmp");
            if (routingDataSet == null)
                    routingDataSet = ngrt.createMGDS(width / 5, mGDSCount);
            ngrt.drawRoutingBMP(routingDataSet, width, height,
                    routingGraphColors, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Not an NGRoutingTable");
        }
    }

    private void sendRoutingPage(HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        String pdepth = req.getParameter("depth");
        if (pdepth != null) {
            try {
                mGDSCount = Integer.parseInt(pdepth);
            } catch (NumberFormatException ex) {
            }
        }

        RoutingTable rt = Main.origRT;
        if (rt instanceof NGRoutingTable) {
            NGRoutingTable ngrt = (NGRoutingTable) rt;

            routingDataSet = ngrt.createMGDS(128, mGDSCount);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/html");

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            HtmlTemplate simpleTemplate = new HtmlTemplate(this.simpleTemplate);

            long now = System.currentTimeMillis();
            DateFormat df = DateFormat.getDateTimeInstance();
            String date = df.format(new Date(now));

            simpleTemplate.set("TITLE", "Routing summary page: " + date);

            //graphical representation
            pw.println("<img src=\"routing.bmp\" />");

            //ranges
            pw.println("<table><tr><td>&nbsp;</td>");
            for (int i = 0; i < mGDSCount; i++)
                pw.println("<th>" + i + "</th>");
            pw.println("<th>Global</th></tr>\n<tr><th>Minimum</th>");
            for (int i = 0; i < mGDSCount; i++)
                pw.println("<td>" + routingDataSet.getMin(i) + "</td>");
            pw.println("<td>" + routingDataSet.getMin()
                    + "</td></tr>\n<tr><th>Maximum</th>");
            for (int i = 0; i < mGDSCount; i++)
                pw.println("<td>" + routingDataSet.getMax(i) + "</td>");
            pw.println("<td>" + routingDataSet.getMax()
                    + "</td></tr>\n</table>\n");

            //node list (with colors)
            pw.println("<h2> Nodes included in graph </h2>");
            Object[] sources = routingDataSet.getSources();
            int i;
            for (i = 0; i < sources.length && i < routingGraphColors.length; i++) {
                pw.println("<p> <span style=\"color:#"
                        + routingGraphColors[i].toHexString() + "\">"
                        + (String) (sources[i]) + "</span> </p>");
            }
            for (; i < sources.length; i++) { //print the rest of the sources
                // without color, if we haven't
                // run out
                pw.println("<p>" + (String) (sources[i]) + "</p>");
            }

            simpleTemplate.set("BODY", sw.toString());

            simpleTemplate.toHtml(resp.getWriter());
            resp.flushBuffer();
        } else
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Not an NGRoutingTable");
    }

    private void sendStatusPage(HttpServletResponse resp) throws IOException {
        long now = System.currentTimeMillis();
        DateFormat df = DateFormat.getDateTimeInstance();
        String date = df.format(new Date(now));

        RTDiagSnapshot status = rt.getSnapshot(false);
        final StringMap tableData = status.tableData();
        final PropertyArray refData = status.refData();
        final RecentRequestHistory.RequestHistoryItem[] rh = status
                .recentRequests();
        String typeName = null;

        if (tableData != null) {
            typeName = (String) tableData.value("Implementation");
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        HtmlTemplate template = new HtmlTemplate(this.simpleTemplate);

        template.set("TITLE", "Routing Table status: " + date);

        makePerTableData(tableData, pw);

        makeRefDownloadForm(refData, pw);

        pw.println("<p><b>Search time estimator (with recent events):</b></p>");
        pw
                .println("<p><div class=\"graph\"><img src=\"global.bmp\" /></div></p>");
        pw
                .println("<p><b>Search time estimator (zoomed in vertically):</b></p>");
        pw
                .println("<p><div class=\"graph\"><img src=\"global.bmp?clippoints=true\" /></div></p>");
        pw.println("<p><b>Transfer rate estimator:</b></p>");
        pw
                .println("<p><div class=\"graph\"><img src=\"global.bmp?type=rate\" /></div></p>");
        pw.println("<p><b>Probability of DNF estimator:</b></p>");
        pw
                .println("<p><div class=\"graph\"><img src=\"global.bmp?type=dnf\" /></div></p>");
        pw
                .println("<p><b>Probability of transfer failed (given transfer started) estimator:</b></p>");
        pw
                .println("<p><div class=\"graph\"><img src=\"global.bmp?type=transferfailed\" /></div></p>");

        pw.println("<br />");

        pw.println("<table class=\"reftable\">");

        makeTableHeader(refData, pw);

        if (refData != null) makeRefRowEntries(refData, pw, typeName);

        pw.println("</table>");

        pw.println("<table>");
        String[] classes = { "okay", "normal", "warning"};
        String[] meanings = { "OK", "No connections", "Backed Off"};
        for (int x = 0; x < classes.length; x++) {
            StringBuffer s = new StringBuffer("<tr><td>");
            s.append("<span class=\"").append(classes[x]).append("\">");
            if (classes[x].equals("normal"))
                s.append("default");
            else
                s.append(classes[x]);
            s.append("</span>");
            s.append("<td><b>").append(meanings[x]).append("</b></td></tr>");
            pw.println(s.toString());
        }

        pw.println("</table>");

        if (rh != null && rh.length > 0) {
            pw.println("<h2>Recent Requests:</h2>");
            pw.println("<table border=\"0\">\n");
            pw.println("<tr><th>Key</th><th>HTL</th><th>Size</th></tr>\n");
            for (int i = 0; i < rh.length; i++) {
                pw.println("<tr><td><pre>" + rh[i].key.toString()
                        + "</pre></td><td>" + rh[i].HTL + "</td><td>"
                        + rh[i].size + "</td></tr>\n");
            }
            pw.println("</table></pre>");
        }

        template.set("BODY", sw.toString());
        template.toHtml(resp.getWriter());
        resp.flushBuffer();
    }

    public static void makePerTableData(StringMap data, PrintWriter pw) {

        final String[] keys = data.keys();
        final Object[] values = data.values();
        if ((keys == null) && (values == null)) { return; }
        pw.println("<table>");
        for (int i = 0; i < keys.length; i++) {
            pw.println("<tr><td>" + keys[i] + "</td><td>" + values[i]
                    + "</td></tr>");
        }
        pw.println("</table>");
    }

    private static final String PROP_NODEREF = "NodeReference";

    private static final String PROP_INBOUND_CONNECTIONS = "Open Inbound Connections";

    private static final String PROP_OUTBOUND_CONNECTIONS = "Open Outbound Connections";

    private static final String PROP_CONNECTIONS = "Open Connections";

    private void makeRefDownloadForm(PropertyArray refs, PrintWriter pw) {
        pw.println("in");
        if (refs == null) { return; }

        boolean noConns = (refs.value(PROP_INBOUND_CONNECTIONS) == null)
                || (refs.value(PROP_OUTBOUND_CONNECTIONS) == null);

        if (noConns) { return; }
        pw.println("<p><a href=\"myref.txt\">This node's reference</a></p>");
        pw.println("<form action=\"noderefs.txt\" method=\"Get\">");

        pw.println(" <input type=\"submit\" value=\"Download References\">");
        pw.println("</form>");
    }

    private static final Long ZERO = new Long(0);

    private static final Boolean TRUE = new Boolean(true);

    private void makeRefRowEntries(PropertyArray pa, PrintWriter pw,
            String typeName) {

        if (pa == null) return;

        String[] keys = pa.keys();
        for (Enumeration e = pa.values().elements(); e.hasMoreElements();) {
            final Object[] values = (Object[]) e.nextElement();

            ////////////////////////////////////////////////////////////
            // Hooks to provide nicer formatting for RoutingTable
            // implementations that we know about.

            boolean failing = true;
            boolean hasOpenConns = false;

            int cfPos = pa.getPos("Consecutive Failures");
            if (cfPos != -1) {
                if (values[cfPos].equals(ZERO)) {
                    values[cfPos] = "none";
                    failing = false;
                }
            }

            int ooPos = pa.getPos("Open Outbound Connections");
            int oiPos = pa.getPos("Open Inbound Connections");
            if (ooPos != -1 && oiPos != -1) {
                values[ooPos] = values[ooPos].toString() + "/" + values[oiPos];
                if (values[ooPos].equals("0/0")) {
                    values[ooPos] = "<span class=\"warning\">0/0</span>";
                } else {
                    hasOpenConns = true;
                    failing = false;
                    values[ooPos] = "<span class=\"okay\">" + values[ooPos]
                            + "</span>";
                }
            }

            int ltPos = pa.getPos("Last Attempt");
            if (ltPos != -1) {
                long lastTry = ((Long) values[ltPos]).longValue();
                if (lastTry <= 0 || lastTry >= (1000 * 1000 * 1000)) {
                    if (lastTry > 0)
                            Core.logger.log(this,
                                    "lastTry has ridiculous value " + lastTry
                                            + " in formatRef", new Exception(
                                            "debug"), Logger.NORMAL);
                    values[ltPos] = "never";
                } else {
                    values[ltPos] = values[ltPos].toString() + " secs. ago";
                }
            }

            NodeReference ref = (NodeReference) values[pa
                    .getPos("NodeReference")];

            Identity i = (Identity) values[pa.getPos("Identity")];

            int atPos = pa.getPos("Connection Attempts");
            int suPos = pa.getPos("Successful Connections");
            if (atPos != -1 && suPos != -1) {
                long attempts = ((Long) values[atPos]).longValue();
                long successful = ((Long) values[suPos]).longValue();
                if (attempts > 0 && successful > 0) {
                    // percent successful
                    values[suPos] = values[suPos].toString()
                            + " &nbsp; &nbsp; ("
                            + (long) (100 * successful / (double) attempts)
                            + "%)";
                }
            }

            long arkVer = 0;

            int avPos = pa.getPos("ARK Version");
            int afPos = pa.getPos("Fetching ARK");
            if (avPos != -1 && afPos != -1) {
                arkVer = ((Long) values[avPos]).longValue();
                String v = "";

                if (values[afPos].equals(TRUE))
                    v = "unimportant"; // Fetching ARK
                else if (failing)
                    v = "warning"; // Failing
                else if (suPos != -1 && !values[suPos].equals(ZERO))
                        v = "okay"; // Working fine

                if (values[afPos].equals(TRUE)) {
                    try {
                        if (ref.getARKURI(arkVer + 1) == null) {
                            values[afPos] = "broken"; // it will fail soon
                            // enough
                        } else {
                            values[afPos] = "<a href=\"/"
                                    + ref.getARKURI(arkVer + 1).toString(false)
                                    + "\">yes</a>";
                        }
                    } catch (freenet.KeyException e1) {
                        Core.logger.log(this, "NodeStatusServlet got " + e1,
                                e1, Logger.ERROR);
                        values[afPos] = "really broken!";
                    }
                } else {
                    values[afPos] = "no";
                }

                // Black means not connected yet

                int coPos = pa.getPos("Contact Probability");
                if (v.length() != 0 && coPos != -1) {
                    values[coPos] = " <span class=\"" + v + "\"> "
                            + values[coPos] + "</span> ";
                }
            }

            // clean up version
            int nvPos = pa.getPos("Node Version");
            if (nvPos != -1) {
                String verString = (String) values[nvPos];
                int pos = verString.lastIndexOf(",");
                if (pos > -1 && pos < verString.length() - 1) {
                    values[nvPos] = verString.substring(pos + 1);
                }
            }

            int auPos = pa.getPos("ARK URL");
            if (auPos != -1 && values[auPos] != null && arkVer != 0
                    && avPos != -1) {
                values[avPos] = "<a href=\"/" + values[auPos] + "\">"
                        + values[avPos] + "</a>";
            }

            int lePos = pa.getPos("Last Estimate");
            if (lePos != -1) values[lePos] = values[lePos].toString() + "ms";

            int egPos = pa.getPos("Estimate Graph");
            if (egPos != -1)
                    values[egPos] = "<img src=\"nodeGraph.bmp?identity="
                            + ((DSAIdentity) i).getYAsHexString()
                            + "&estimator=composite&width=60&height=20\" width=\"60\" height=\"20\" alt=\"\" />";

            //            refValues[7] = ((String)refValues[7]).replaceAll(" ","&nbsp;");
            // does this do anything? --Thelema

            int cnPos = pa.getPos("Connection Fail Time");
            if (cnPos != -1) values[cnPos] = values[cnPos].toString() + "ms";

            int csPos = pa.getPos("Connection Success Time");
            if (csPos != -1) values[csPos] = values[csPos].toString() + "ms";

            boolean isNGR = false;

            if (typeName.equals("freenet.node.rt.NGRoutingTable"))
                    isNGR = true;

            int adPos = pa.getPos("Address");
            if (adPos != -1 && isNGR)
                    values[adPos] = "<a href=\"nodeDetails.html?identity="
                            + ((DSAIdentity) i).getYAsHexString() + "\">"
                            + values[adPos] + "</a>";

            int mtPos = pa.getPos("Minimum Transfer Rate");
            if (mtPos != -1)
                    values[mtPos] = ((String) values[mtPos]).replaceAll(" ",
                            "&nbsp;");

            int xtPos = pa.getPos("Maximum Transfer Rate");
            if (xtPos != -1)
                    values[xtPos] = ((String) values[xtPos]).replaceAll(" ",
                            "&nbsp;");

            boolean backedOff = false;

            int buPos = pa.getPos("Backed Off Until");
            if (buPos != -1) {
                long l = ((Long) (values[buPos])).longValue();
                if (l == 0)
                    values[buPos] = "live";
                else {
                    backedOff = true;
                    values[buPos] = Long.toString(l / 1000) + " seconds";
                }
            }

            int mriPos = pa.getPos("Minimum Request Interval");
            if (mriPos != -1) {
                double d = ((Double) values[mriPos]).doubleValue();
                values[mriPos] = Integer.toString((int) d) + "ms";
            }

            int ariPos = pa.getPos("Average Request Interval");
            if (mriPos != -1) {
                double d = ((Double) values[ariPos]).doubleValue();
                values[ariPos] = Integer.toString((int) d) + "ms";
            }

            int lrPos = pa.getPos("Last Request Time");
            if (lrPos != -1) {
                long l = ((Long) values[lrPos]).longValue();
                values[lrPos] = Long.toString(l) + "ms";
            }

            if (adPos != -1) {
                if (backedOff)
                        values[adPos] = "<span class=\"warning\">"
                                + values[adPos] + "</span>";
                if (hasOpenConns && !backedOff)
                        values[adPos] = "<span class=\"okay\">" + values[adPos]
                                + "</span>";
            }

            pw.println("<tr>");
            for (int j = 0; j < values.length; j++) {
                // Skip noderef values since we can't render them.
                if (keys[j].equals("NodeReference")) {
                    continue;
                }
                if (keys[j].equals("ARK URL")) {
                    continue;
                }
                if (keys[j].equals("Keys")) {
                    continue;
                }
                if (keys[j].equals("Open Inbound Connections")) {
                    continue; // we aggregate it with the previous one
                }
                if (keys[j].equals("Identity")) {
                    continue;
                }
                pw.println("    <td> " + values[j] + " </td>");

            }
            pw.println("</tr>");
        }

    }

    private void makeTableHeader(PropertyArray pa, PrintWriter pw) {
        if (pa == null) return;

        pw.println("<tr>");
        final String[] keys = pa.keys();

        if (keys == null) {
            pw.println("</tr>");
            return;
        }

        for (int j = 0; j < keys.length; j++) {
            String val = keys[j];
            if (val == null) continue;

            // Skip noderef values since we can't render them.
            if (keys[j].equals("NodeReference")) //TODO: generalize this and
                    // share a String[] with the
                    // data printing code
                    continue;

            if (keys[j].equals("ARK URL")) continue;

            if (keys[j].equals("Keys")) continue;

            if (keys[j].equals("Open Outbound Connections")) {
                pw.println("    <td> <b> " + "Open Connections (out/in)"
                        + " </b> </td>");
                continue;
            }

            if (keys[j].equals("Backoff Remaining")) {
                pw.println("    <td> <b> " + "Backoff Remaining (ms)"
                        + " </b> </td>");
                continue;
            }

            if (keys[j].equals("Open Inbound Connections")) continue; // we
            // aggregate
            // it
            // with
            // the
            // previous
            // one

            if (keys[j].equals("Identity")) continue;

            pw.println("    <td> <b> " + keys[j] + " </b> </td>");
        }
        pw.println("</tr>");
    }

    private void sendRefList(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        long minConnections = 0;
        try {
            String minConnectionsAsString = req.getParameter("minConnections");
            if (minConnectionsAsString != null) {
                minConnectionsAsString = freenet.support.URLDecoder
                        .decode(minConnectionsAsString);
                minConnections = Long.parseLong(minConnectionsAsString);
            }
        } catch (NumberFormatException nfe) {
        } catch (URLEncodedFormatException uefe) {
        }

        if (minConnections < 0) {
            minConnections = 0;
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");

        boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        final PropertyArray refs = rt.getSnapshot(false).refData();
        if (refs == null) {
            resp.getWriter().println(""); // so that document is not empty.
            resp.flushBuffer();
            if (logDEBUG)
                    Core.logger.log(this,
                            "sendRefList returning empty because no refs",
                            Logger.DEBUG);
            return;
        }

        // REDFLAG: doc key / type assumptions

        int nrPos = refs.getPos(PROP_NODEREF);
        int iConPos = refs.getPos(PROP_INBOUND_CONNECTIONS);
        int oConPos = refs.getPos(PROP_OUTBOUND_CONNECTIONS);

        WriteOutputStream out = null;
        out = new WriteOutputStream(resp.getOutputStream());

        int count = 0;
        Vector values = refs.values();
        for (Enumeration e = values.elements(); e.hasMoreElements();) {
            Object[] row = (Object[]) e.nextElement();

            NodeReference ref = (NodeReference) row[nrPos];
            if (ref == null) continue;
            if (ref.noPhysical()) {
                if (logDEBUG)
                        Core.logger.log(this,
                                "sendRefList skipping node because noPhysical",
                                Logger.NORMAL);
                continue; // not much use without a physical addr
            }
            if (iConPos != -1 || oConPos != -1) {
                int conns = 0;
                if (iConPos != -1)
                        conns += ((Integer) row[iConPos]).intValue();
                if (oConPos != -1)
                        conns += ((Integer) row[oConPos]).intValue();
                if (conns <= 0) {
                    if (logDEBUG)
                            Core.logger.log(this, "Skipping: " + ref + ": "
                                    + conns + " conns", Logger.DEBUG);
                    continue;
                }
            }

            FieldSet fs = ref.getFieldSet();
            FieldSet estimator = rt.estimatorToFieldSet(ref.getIdentity());
            if (estimator != null) fs.put("Estimator", estimator);
            fs.writeFields(out);
            count++;
        }

        // Don't send an empty document error in browser.
        if (count == 0) {
            out.write('\n');
        }

        resp.flushBuffer();
    }

    private void sendError(HttpServletResponse resp, int status,
            String detailMessage) throws IOException {

        // get status string
        String statusString = status + " "
                + HttpServletResponseImpl.getNameForStatus(status);

        // show it
        if (Core.logger.shouldLog(Logger.DEBUG, this))
                Core.logger.log(this, "Sending HTTP error: " + statusString,
                        Logger.DEBUG);
        resp.setStatus(status);
        resp.setContentType("text/html");
        HtmlTemplate template = new HtmlTemplate(this.simpleTemplate);
        template.set("TITLE", statusString);
        template.set("BODY", detailMessage);
        template.toHtml(resp.getWriter());
        resp.flushBuffer();
    }

    ///////////////////////////////////////////////////////////
    // Support for printing contents of ticker and ocm.

    private final void sendTickerContents(HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/html");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        HtmlTemplate template = new HtmlTemplate(this.simpleTemplate);

        template.set("TITLE", "Freenet Node Ticker Contents");
        node.ticker().writeEventsHtml(pw);

        template.set("BODY", sw.toString());
        template.toHtml(resp.getWriter());
        resp.flushBuffer();
    }

    private final void sendOcmContents(HttpServletResponse resp,
            HttpServletRequest req) throws IOException {
        resp.setContentType("text/html");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        HtmlTemplate template = new HtmlTemplate(this.simpleTemplate);

        template.set("TITLE", "Open ConnectionManager Contents");
        node.connections.writeHtmlContents(pw, req);

        template.set("BODY", sw.toString());
        template.toHtml(resp.getWriter());
        resp.flushBuffer();
    }

    ////////////////////////////////////////////////////////////
    // Support for sending Diagnostics.

    private final void sendVarData(HttpServletResponse resp, String varName,
            String period) throws IOException {
        PrintWriter pw = resp.getWriter();

        DiagnosticsFormat format;
        boolean html = false;
        if (period.equalsIgnoreCase("occurrences")) {
            html = true;
            resp.setContentType("text/html");
            format = new HtmlDiagnosticsFormat(-1);
        } else if (period.equalsIgnoreCase("raw")) {
            resp.setContentType("text/plain");
            format = new RowDiagnosticsFormat();
        } else if (period.startsWith("raw")) {
            resp.setContentType("text/plain");
            if (period.substring(3).equalsIgnoreCase("occurences"))
                format = new RowDiagnosticsFormat(-1);
            else
                format = new RowDiagnosticsFormat(Diagnostics.getPeriod(period
                        .substring(3)));

        } else if (period.equalsIgnoreCase("fieldset")) {
            resp.setContentType("text/plain");
            format = new FieldSetFormat();
        } else {
            try {
                resp.setContentType("text/html");
                html = true;
                format = new HtmlDiagnosticsFormat(Diagnostics
                        .getPeriod(period));
            } catch (IllegalArgumentException e) {
                sendError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Unknown period type given.");
                return;
            }
        }

        try {
            if (html) {
                pw
                        .println("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.0 Transitional//EN\">");
                pw.println("<html><head><title>" + varName + "(" + period
                        + ")</title></head><body>");
            }
            pw.println(diagnostics.writeVar(varName, format));
            if (html) pw.println("</body></html>");
        } catch (NoSuchElementException e) {
            sendError(resp, HttpServletResponse.SC_BAD_REQUEST,
                    "No such diagnostics field");
            return;
        }

        resp.flushBuffer();
    }

    private final void sendGraphData(HttpServletRequest req,
            HttpServletResponse resp) {
        try {
            String varName = req.getParameter("var");

            if (varName == null) {
                // variable is mandatory, bounce our confused user to the
                // diagnostics
                // index so they can pick one
                //
                sendDiagnosticsIndex(resp);
                return;
            }

            GraphRange gr;

            String period = req.getParameter("period");

            if (period == null) period = "minute";

            final int p = period.equalsIgnoreCase("occurrences") ? -1
                    : Diagnostics.getPeriod(period);

            int type;

            try {
                type = Integer.parseInt(req.getParameter("type"));
            } catch (NumberFormatException e) {
                type = 0; // 0 == guess
            }

            try {
                // try getting the range out of the query string
                gr = new GraphRange(req.getParameter("range"));
            } catch (IllegalArgumentException e) {
                // failing that, parse it from the data we're formatting
                GraphRangeDiagnosticsFormat grdf = new GraphRangeDiagnosticsFormat(
                        p, type);

                diagnostics.writeVar(varName, grdf);

                gr = grdf.getRange();
            }

            type = gr.getType();

            String ctype = req.getParameter("content-type");

            if (ctype != null) try {
                ctype = freenet.support.URLDecoder.decode(ctype);
            } catch (URLEncodedFormatException e) {
                ctype = null;
            }

            BitmapEncoder be = BitmapEncoder.createByMimeType(ctype);

            if (be == null) {
                // default to html
                PrintWriter pw = resp.getWriter();

                resp.setContentType("text/html");

                String itype = req.getParameter("image-type");
                // todo: get default from config
                if (itype == null) {
                    itype = new DibEncoder().getMimeType();
                }

                pw.println(diagnostics.writeVar(varName,
                        new GraphHtmlDiagnosticsFormat(p, type, gr, itype)));
            } else {
                // output the image
                OutputStream os = resp.getOutputStream();

                resp.setContentType(be.getMimeType());

                diagnostics.writeVar(varName, new GraphDiagnosticsFormat(p, be,
                        os, type, gr));
            }

            resp.flushBuffer();
        } catch (Throwable t) {
            Core.logger.log(this, "Grapher threw.", t, Logger.ERROR);
        }
    }

    private final void sendDiagnosticsIndex(HttpServletResponse resp)
            throws IOException {

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        resp.setContentType("text/html");

        HtmlTemplate simpleTemplate = new HtmlTemplate(this.simpleTemplate);

        simpleTemplate.set("TITLE", "Freenet Node Diagnostics Variables");

        DiagnosticsFormat indexFormat = new HtmlIndexFormat();
        Core.diagnostics.writeVars(pw, indexFormat);
        simpleTemplate.set("BODY", sw.toString());

        simpleTemplate.toHtml(resp.getWriter());
        resp.flushBuffer();
    }

    private final void sendLoadStats(HttpServletResponse resp)
            throws IOException {

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        loadStats.dump(resp.getWriter());
        resp.flushBuffer();
    }

    private final int getActive(ContactCounter.Record[] contacts) {
        int activeCount = 0;
        for (int i = 0; i < contacts.length; i++) {
            activeCount += contacts[i].activeContacts;
        }
        return activeCount;
    }

    private final void sendPerHostStats(HttpServletResponse resp, String kind)
            throws IOException {

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        PrintWriter pw = resp.getWriter();
        ContactCounter.Record[] contacts = null;
        if (kind.equals("inboundContacts")) {
            if (inboundContacts != null) {
                contacts = inboundContacts.getSnapshot();
                pw.println("# unique contacts: " + contacts.length
                        + ", active connections: " + getActive(contacts));

                pw
                        .println("# format: <att> <succ> <%(att/succ)> <act> <%(act/succ)> <address>");
                pw
                        .println("# <att=contact attempts> <succ=succesful contacts> <act=active connections>");
                pw.println("#");
            } else {
                pw.println("# Inbound contacts are not being logged.");
                pw.println("# To enable logging set:");
                pw.println("#   logInboundContacts=true");
                pw.println("# in your freenet.conf / freenet.ini file.");
                resp.flushBuffer();
                return;
            }
        } else if (kind.equals("outboundContacts")) {
            if (outboundContacts != null) {
                contacts = outboundContacts.getSnapshot();
                pw.println("# unique contacts: " + contacts.length
                        + ", live connections: " + getActive(contacts));

                pw
                        .println("# format: <att> <succ> <%(att/succ)> <live> <%(live/succ)> <address>");
                pw
                        .println("# <att=contact attempts> <succ=succesful contacts> <live=live connections>");
                pw.println("#");
            } else {
                pw.println("# Outbound contacts are not being logged.");
                pw.println("# To enable logging set:");
                pw.println("#   logOutboundContacts=true");
                pw.println("# in your freenet.conf / freenet.ini file.");
                resp.flushBuffer();
                return;
            }
        } else if (kind.equals("outboundRequests")) {
            if (outboundRequests != null) {
                contacts = outboundRequests.getSnapshot();
                pw.println("# unique hosts: " + contacts.length);
                pw.println("# format: <requests> <address>");
                pw.println("#");
            } else {
                pw.println("# Outbound requests are not being logged.");
                pw.println("# To enable logging set:");
                pw.println("#   logOutboundRequests=true");
                pw.println("# in your freenet.conf / freenet.ini file.");
                resp.flushBuffer();
                return;
            }
        } else {
            if (inboundRequests != null) {
                contacts = inboundRequests.getSnapshot();
                pw.println("All inbound requests ever received, " + "from "
                        + contacts.length + " unique hosts:\n");
                pw
                        .println("Receive\tAccept\tAcc/Rcv\tSucceed\tSuc/Acc\tHost Address\tVersion\n");
            } else {
                pw.println("# Inbound requests are not being logged.");
                pw.println("# To enable logging set:");
                pw.println("#   logInboundRequests=true");
                pw.println("# in your freenet.conf / freenet.ini file.");
                resp.flushBuffer();
                return;
            }
        }

        if (contacts != null) {
            // compute inbound request stats by host version
            TreeMap hreq = new TreeMap();
            TreeMap hacc = new TreeMap();
            TreeMap hsuc = new TreeMap();

            // Sort by count.
            HeapSorter.heapSort(new ArraySorter(contacts));

            for (int i = 0; i < contacts.length; i++) {
                if (kind.equals("outboundRequests")) {
                    pw.println(contacts[i].totalContacts + "\t"
                            + contacts[i].addr);

                } else {
                    String vstr = contacts[i].version;
                    if (vstr == null) vstr = "(null)";
                    int stripper = vstr.lastIndexOf(',');
                    if (stripper != 0) vstr = vstr.substring(stripper + 1);
                    pw
                            .println(contacts[i].totalContacts
                                    + "\t"
                                    + contacts[i].successes
                                    + "\t"
                                    + nf3
                                            .format((double) contacts[i].successes
                                                    / (double) contacts[i].totalContacts)
                                    + "\t"
                                    + contacts[i].activeContacts
                                    + "\t"
                                    + nf3
                                            .format(((double) contacts[i].successes > 0) ? ((double) contacts[i].activeContacts / (double) contacts[i].successes)
                                                    : 0) + "\t"
                                    + contacts[i].addr + "\t " + vstr);
                    // add the request count into the map
                    hreq.put(vstr, new Integer(contacts[i].totalContacts
                            + (hreq.containsKey(vstr) ? ((Integer) hreq
                                    .get(vstr)).intValue() : 0)));
                    // add the accept count into the map
                    hacc.put(vstr, new Integer(contacts[i].successes
                            + (hacc.containsKey(vstr) ? ((Integer) hacc
                                    .get(vstr)).intValue() : 0)));
                    // add the DataFound count into the map
                    hsuc.put(vstr, new Integer(contacts[i].activeContacts
                            + (hsuc.containsKey(vstr) ? ((Integer) hsuc
                                    .get(vstr)).intValue() : 0)));
                }
            }

            // dump the requests by version (in decreasing order)
            if (kind.equals("inboundRequests")) {
                pw.println("\nData by host version\n");
                String hvkey = new String();
                TreeMap reverseMap = new TreeMap();
                Iterator hviter = hreq.keySet().iterator();
                while (hviter.hasNext()) {
                    hvkey = (String) hviter.next();
                    reverseMap.put(hreq.get(hvkey), hvkey);
                }
                hviter = reverseMap.keySet().iterator();
                String table = new String();
                while (hviter.hasNext()) {
                    Integer key = (Integer) hviter.next();
                    String ver = (String) reverseMap.get(key);
                    int req, acc, suc;
                    req = key.intValue();
                    acc = ((Integer) hacc.get(ver)).intValue();
                    suc = ((Integer) hsuc.get(ver)).intValue();
                    table = req
                            + "\t"
                            + acc
                            + "\t"
                            + nf3.format((double) acc / (double) req)
                            + "\t"
                            + suc
                            + "\t"
                            + nf3
                                    .format((acc > 0) ? ((double) suc / (double) acc)
                                            : 0) + "\t" + ver + "\n" + table;
                }
                pw.println(table);
            }

        }

        resp.flushBuffer();
    }

    private final void sendMyNodeReference(HttpServletResponse resp)
            throws IOException {
        NodeReference myRef = node.getNodeReference();

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        WriteOutputStream out = new WriteOutputStream(resp.getOutputStream());
        myRef.getFieldSet().writeFields(out);
        resp.flushBuffer();
    }

    private Node node = null;

    private RoutingTable rt = null;

    private Diagnostics diagnostics = null;

    private ContactCounter inboundContacts = null;

    private ContactCounter outboundContacts = null;

    private ContactCounter inboundRequests = null;

    private ContactCounter outboundRequests = null;

    private LoadStats loadStats = null;
}