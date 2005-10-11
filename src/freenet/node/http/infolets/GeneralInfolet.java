package freenet.node.http.infolets;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.NumberFormat;

import freenet.Core;
import freenet.Version;
import freenet.diagnostics.Diagnostics;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.http.Infolet;
import freenet.node.rt.NGRoutingTable;
import freenet.support.LimitCounter;
import freenet.support.Logger;
import freenet.support.servlet.HtmlTemplate;
import freenet.transport.tcpConnection;

/**
 * This is the Infolet which is displayed by default
 *
 * @author ian
 */
public class GeneralInfolet extends Infolet {

    private Node node;

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

    private HtmlTemplate titleBoxTemplate;

    /**
     *  Return long name of the infolet
     *
     * @return    String longName
     */
    public String longName() {
        return "General Information";
    }

    /**
     *  Return short name of infolet
     *
     * @return    String shortName
     */
    public String shortName() {
        return "general";
    }

    /**
     *  Initialize the node reference and create the title Box template
     *
     * @param n
     *            The Node object this page describes.
     */
    public void init(Node n) {
        try {
            titleBoxTemplate = HtmlTemplate.createTemplate("titleBox.tpl");
        } catch (IOException e) {
            Node.logger.log( this, "Cannot read title box template", e, Logger.ERROR );
        }
        node = n;
    }

    /**
     *  Create the General Information web page
     *
     * @param pw
     *            The PrintWriter where the page gets written.
     */
    public void toHtml(PrintWriter pw) {
        StringBuffer sb = new StringBuffer(500);
        sb.append("From here you can view information about what is going ");
        sb.append("on inside your node.  Select from the options in the ");
        sb.append("menu to the left.");
        // reentrancy
        HtmlTemplate titleBoxTemplate = new HtmlTemplate(this.titleBoxTemplate);
        titleBoxTemplate.set("CONTENT", sb.toString());
        titleBoxTemplate.set("TITLE", "Node Information");
        titleBoxTemplate.toHtml(pw);
        sb = new StringBuffer(500);
        sb.append("<table>");
        sb.append("<tr><th>Node Version</th><td>" + Version.nodeVersion + "</td></tr>");
        sb.append("<tr><th>Protocol Version</th><td>" + Version.protocolVersion + "</td></tr>");
        sb.append("<tr><th>Build Number</th><td>" + Version.buildNumber + "</td></tr>");
        sb.append("<tr><th>CVS Revision</th><td>" + Version.cvsRevision + "</td></tr>");
        sb.append("</table>");
        titleBoxTemplate.set("TITLE", "Version Information");
        titleBoxTemplate.set("CONTENT", sb.toString());
        titleBoxTemplate.toHtml(pw);
        StringBuffer uptime = new StringBuffer();

        long deltat = (System.currentTimeMillis() - Node.startupTimeMs + 500) / 1000;

        if (deltat < 60) {
            uptime.append("&nbsp;< 1 minute ");
        } else {
            long days = deltat / 86400l;
            deltat -= days * 86400l;
            long hours = deltat / 3600l;
            deltat -= hours * 3600l;
            long minutes = deltat / 60l;

            if (days > 0) {
                uptime.append(days).append(days == 1 ? " day" : " days").append(" ");
            }
            if (hours > 0) {
                uptime.append(hours).append(hours == 1 ? " hour" : " hours").append(" ");
            }
            uptime.append(" ").append(minutes).append(minutes == 1 ? " minute" : " minutes");
        }
        sb = new StringBuffer(500);
        sb.append("&nbsp;" + uptime);
        titleBoxTemplate.set("TITLE", "Uptime");
        titleBoxTemplate.set("CONTENT", sb.toString());
        titleBoxTemplate.toHtml(pw);

        sb = new StringBuffer(500);
        sb.append("<table>\n");
        sb.append("<tr><td nowrap>Current routingTime</td><td>"
                + (long) Core.diagnostics.getValue("routingTime", Diagnostics.MINUTE, Diagnostics.MEAN_VALUE) + "ms</td></tr>\n");

        sb.append("<tr><td nowrap>Current messageSendTimeRequest</td><td>"
                + (long) Core.diagnostics.getValue("messageSendTimeRequest", Diagnostics.MINUTE, Diagnostics.MEAN_VALUE) + "ms</td></tr>\n");
    
        // Thread load
        int jobs = node.activeJobs();
        int available = node.availableThreads();
        StringBuffer whyLoad = new StringBuffer(500);
        float f = node.estimatedLoad(whyLoad, true);
        // It's not just thread based.  There's also a hard
        // rate limit.
        StringBuffer whyRejectingRequests = new StringBuffer(500);
        boolean rejectingRequests = node.rejectingRequests(whyRejectingRequests, true);
        boolean rejectingMostRequests = node.rejectingRequests(whyRejectingRequests, true);
        StringBuffer whyRejectingConnections = new StringBuffer(500);
        boolean rejectingConnections = node.rejectingConnections(whyRejectingConnections);

        String color = "black";
        String comment = "";
    
        if (jobs > -1) {
            if (rejectingConnections) {
                color = "red";
                comment = "<b>[Rejecting incoming connections and requests!]</b>";
            } else if (rejectingRequests) {
                color = "red";
                comment = "<b>[QueryRejecting all incoming requests!]</b>";
            } else if (rejectingMostRequests) {
                color = "blue";
                comment = "<b>[QueryRejecting most incoming requests]</b>";
            }

            String msg = Integer.toString(jobs);

            int maximumThreads = node.getThreadFactory().maximumThreads();
            if (maximumThreads > 0) {
                msg += " (" + nfp.format( ((float) jobs) / maximumThreads ) + ")";
            }

            sb.append("<tr><td nowrap>Pooled threads running jobs</td><td>" + msg + "</td></tr>\n");
            sb.append("<tr><td nowrap>Pooled threads which are idle</td><td>" + available + "</td></tr>\n");
            LimitCounter outboundRequestLimit = Node.outboundRequestLimit;
            if(outboundRequestLimit != null)
                    sb.append("<tr><td nowrap>Outbound request quota used</td><td>" + outboundRequestLimit.toString() + "</td></tr>\n");
      
            if (Core.diagnostics != null && (Node.outputBandwidthLimit != 0) && (Node.doOutLimitCutoff || Node.doOutLimitConnectCutoff)) {
				double sent = node.getBytesSentLastMinute()/60;
                double limit = Node.outputBandwidthLimit;
                sb.append("<tr><td nowrap>Current upstream bandwidth usage</td><td>" + nf0.format(sent) + " bytes/second ("
                        + nfp.format(sent / limit) + ")</td></tr>\n");
            }
            if (rejectingConnections) {
                sb.append("<tr><td>Reason for refusing connections:</td><td>");
                sb.append(whyRejectingConnections);
                sb.append("</td></tr>\n");
            }
            if (rejectingRequests) {
                sb.append("<tr><td>Reason for QueryRejecting requests:</td><td>");
                sb.append(whyRejectingRequests);
                sb.append("</td></tr>\n");
            }
            if (rejectingConnections || rejectingRequests) {
                sb.append("<tr><td></td><td>");
                sb.append("It's normal for the node to sometimes reject connections or requests ");
                sb.append("for a limited period.  If you're seeing rejections continuously the node ");
                sb.append("is overloaded or something is wrong (i.e. a bug).</td></tr>\n");
            }
        }
        sb.append("<tr><td>Current estimated load for QueryReject purposes</td><td>" + (int) (node.estimatedLoad(false) * 100) + "%</td></tr>\n");
        
        String msg = "<tr><td nowrap>Current estimated load for rate limiting</td><td>";
        if (comment.length()!=0) {
            comment = " <span style=\"color:" + color + "\">" + comment + "</span><br />";
        }
    
        sb.append(msg + nfp.format(f) + comment + "</td></tr>\n");
        sb.append("<tr><td>Reason for load:</td><td>" + whyLoad.toString() + "</td></tr>\n");
        sb.append("<tr><td>Estimated external pSearchFailed (based only on QueryRejections due to load):</td><td>"
                + Node.myPQueryRejected.currentValue() + "</td></tr>\n");
        sb.append("<tr><td>Current estimated requests per hour:</td><td>" + node.getActualRequestsPerHour() + "</td></tr>\n");
        sb.append("<tr><td>Current global quota (requests per hour):</td><td>" + node.getGlobalQuota() + "</td></tr>\n");
        double maxGlobalQuota = node.getMaxGlobalQuota();
        if(maxGlobalQuota < Double.MAX_VALUE)
            sb.append("<tr><td>Current global quota limit from bandwidth (requests per hour):</td><td>" + maxGlobalQuota + "</td></tr>\n");
        if(Node.logInputBytes)
            sb.append("<tr><td>Highest seen bytes downloaded in one minute:</td><td>" + tcpConnection.maxSeenIncomingBytesPerMinute()+"</td></tr>\n");
        sb.append("<tr><td>Current outgoing request rate</td><td>"+node.sentRequestCounter.getExtrapolatedEventsPerHour()+"</td></tr>\n");
        double actualPSuccess = Core.diagnostics.getBinomialValue("routingSuccessRatio", Diagnostics.HOUR, Diagnostics.SUCCESS_PROBABILITY);
        sb.append("<tr><td>Current probability of a request succeeding by routing</td><td>"+nfp.format(actualPSuccess)
                +"</td></tr>\n");
        double pTransfer = ((NGRoutingTable)Main.origRT).pTransferGivenInboundRequest();
        sb.append("<tr><td>Current probability of an inbound request causing a transfer outwards</td><td>"+nfp.format(pTransfer)+"</td></tr>");
        double maxPSuccess = node.rt.maxPSuccess();
        sb.append("<tr><td>Current target (best case single node) probability of a request succeeding</td><td>"+
                nfp.format(maxPSuccess)+"</td></tr>\n");
        
        sb.append("</table>\n");
        titleBoxTemplate.set("TITLE", "Load");
        titleBoxTemplate.set("CONTENT", sb.toString());
        titleBoxTemplate.toHtml(pw);
    }
}