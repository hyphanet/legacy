package freenet.client.http;

import freenet.client.http.ImageServlet.Dimension;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Date;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.client.SplitFileStatus;
import freenet.client.http.filter.FilterException;
import freenet.client.metadata.MimeTypeUtils;
import freenet.message.client.FEC.SegmentHeader;
import freenet.node.Node;
import freenet.node.http.infolets.HTMLTransferProgressIcon;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;
import freenet.support.servlet.HtmlTemplate;

/**
 * Servlet to handle to handle downloading of FEC SplitFiles from freenet.
 * 
 * @author <a href="mailto:giannijohansson@attbi.com">Gianni Johansson </a>
 */
public class SplitFileRequestServlet extends ServletWithContext {

    String DEFAULT_MIME_TYPE = null;

    // REDFLAG: Get all default state into one place?

    // Default values which are used if no values are specified in
    // the config file or the request URL.
    int defaultHtl = 15;

    int defaultBlockHtl = 10;

    int defaultRetries = 3;
    
    int maxRetries = 50;

    int defaultRetryHtlIncrement = 5;

    // These values seem a little bit low. /Bombe
    int defaultHealHtl = 5;

    int defaultHealPercentage = 5;

    int defaultThreads = 5;

    boolean defaultDoParanoidChecks = true;

    int defaultRefreshIntervalSecs = 30;

    boolean defaultForceSave = false;

    boolean defaultWriteToDisk = false;

    boolean disableWriteToDisk = false;

    boolean defaultSkipDS = false;

    boolean defaultUseUI = true;

    //int defaultUseUIMinSize = 1<<21; // 2MB
    boolean defaultRunFilter = true;

    boolean defaultRandomSegs = true;

    boolean defaultFilterParanoidStringCheck = false;

    String defaultDownloadDir = "";

    String filterPassThroughMimeTypes = "";

    int defaultLifetimeMs = 60 * 60000;

    boolean pollForDroppedConnections = true;

    private NumberFormat nf = NumberFormat.getInstance();

    ////////////////////////////////////////////////////////////
    // Servlet initialization
    ////////////////////////////////////////////////////////////

    public void init() {
        DEFAULT_MIME_TYPE = MimeTypeUtils.fullMimeType("application/octet-stream", null, null);

        ServletContext context = getServletContext();
        setupLogger(context);
        setupBucketFactory(context);
        setupClientFactory(context);

        // Some params will need to be duped in fproxy? REDFLAG: revisit

        defaultHtl = ParamParse.readInt(this, logger, "requestHtl", defaultHtl, 0, 100);
        // _DO_ perturb the overall HTL
        defaultHtl = Node.perturbHTL(defaultHtl);
        defaultBlockHtl = ParamParse.readInt(this, logger, "sfBlockRequestHtl", defaultBlockHtl, 0, 100);
        // _DO_ perturb the overall block HTL
        defaultBlockHtl = Node.perturbHTL(defaultBlockHtl);
        defaultRetries = ParamParse.readInt(this, logger, "sfRequestRetries", defaultRetries, 0, 50);
        maxRetries = ParamParse.readInt(this, logger, "maxRetries", maxRetries, 0, Integer.MAX_VALUE);
        defaultRetryHtlIncrement = ParamParse.readInt(this, logger, "sfRetryHtlIncrement", defaultRetryHtlIncrement, 0, 100);
        defaultHealHtl = ParamParse.readInt(this, logger, "sfHealHtl", defaultHealHtl, 0, 100);
        // _DO_ perturb the overall healing HTL
        // But DO NOT perturb the HTL for the individual blocks.
        defaultHealHtl = Node.perturbHTL(defaultHealHtl);
        defaultHealPercentage = ParamParse.readInt(this, logger, "sfHealPercentage", defaultHealPercentage, 0, 100);
        defaultThreads = ParamParse.readInt(this, logger, "sfRequestThreads", defaultThreads, 0, 100);
        defaultDoParanoidChecks = ParamParse.readBoolean(this, logger, "sfDoParanoidChecks", defaultDoParanoidChecks);
        defaultRefreshIntervalSecs = ParamParse.readInt(this, logger, "sfRefreshIntevalSecs", defaultRefreshIntervalSecs, -1, 3600);
        defaultForceSave = ParamParse.readBoolean(this, logger, "sfForceSave", defaultForceSave);
        defaultWriteToDisk = ParamParse.readBoolean(this, logger, "sfDefaultWriteToDisk", defaultWriteToDisk);

        disableWriteToDisk = ParamParse.readBoolean(this, logger, "sfDisableWriteToDisk", disableWriteToDisk);

        if (disableWriteToDisk) defaultWriteToDisk = false;

        defaultSkipDS = ParamParse.readBoolean(this, logger, "sfSkipDS", defaultSkipDS);
        defaultUseUI = ParamParse.readBoolean(this, logger, "sfUseUI", defaultUseUI);
        //defaultUseUIMinSize = ParamParse.readInt(this, logger,
        // "sfUseUIMinSize", defaultUseUIMinSize, 0, Integer.MAX_VALUE);
        defaultRunFilter = ParamParse.readBoolean(this, logger, "sfRunFilter", defaultUseUI);
        defaultRandomSegs = ParamParse.readBoolean(this, logger, "sfRandomizeSegments", defaultRandomSegs);
        defaultFilterParanoidStringCheck = ParamParse.readBoolean(this, logger, "sfFilterParanoidStringCheck", defaultFilterParanoidStringCheck);

        defaultDownloadDir = getInitParameter("sfDefaultSaveDir");

        String s = getInitParameter("sfFilterPassThroughMimeTypes");
        if (!(s == null || s.length() == 0))
            filterPassThroughMimeTypes = s;
        else
            filterPassThroughMimeTypes = Node.filterPassThroughMimeTypes;
        logger.log(this, "New SplitFileRequestServlet created", Logger.MINOR);
        if (logger.shouldLog(Logger.DEBUG, this)) {
            logger.log(this, "   requestHtl = " + defaultHtl, Logger.DEBUG);
            logger.log(this, "   sfBlockHtl = " + defaultBlockHtl, Logger.DEBUG);
            logger.log(this, "   sfRequestRetries = " + defaultRetries, Logger.DEBUG);
            logger.log(this, "   sfRetryHtlIncrement = " + defaultRetryHtlIncrement, Logger.DEBUG);
            logger.log(this, "   sfRequestThreads = " + defaultThreads, Logger.DEBUG);
            logger.log(this, "   sfRefreshIntervalSecs = " + defaultRefreshIntervalSecs, Logger.DEBUG);
            logger.log(this, "   sfForceSave = " + defaultForceSave, Logger.DEBUG);
            logger.log(this, "   sfSkipDS = " + defaultSkipDS, Logger.DEBUG);
            logger.log(this, "   sfUseUI = " + defaultUseUI, Logger.DEBUG);
            //logger.log(this, " sfMinSizeUseUI = " + defaultUseUIMinSize,
            //Logger.DEBUGGING);
            logger.log(this, "   sfRunFilter = " + defaultRunFilter, Logger.DEBUG);
        }
    }

    ////////////////////////////////////////////////////////////
    // Presentation
    ////////////////////////////////////////////////////////////

    // suburls
    // /starting reads metadata
    // /download/<uri> downloads url once
    // /cancel cancel's pending request
    // /override_filter sends data after filter trips
    // /status_progress renders download progress

    ////////////////////////////////////////////////////////////
    // This is kind of verbose, but I want to make it
    // as easy as possible for other people to modify
    // the UI in a maintainable way.
    //
    protected void handleIllegalDownloadState(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context)
            throws IOException {
        sendHtml(resp, HttpServletResponse.SC_BAD_REQUEST, "<html> " + "<head> " + "<title>You can only download once.</title> " + "</head> "
                + "<body> " + "<h1>You can only download once.</h1> " + "</body> " + "</html> ");
    }

    protected void handleNotASplitFile(HttpServletRequest req, HttpServletResponse resp, String uri, SplitFileRequestContext context)
            throws IOException {

        sendHtml(resp, HttpServletResponse.SC_NOT_IMPLEMENTED, "<html> " + "<head> " + "<title>Not a SplitFile</title> " + "</head> " + "<body> "
                + "<h1>Not a SplitFile</h1> " + " The requested URI wasn't a splitFile. Use fproxy instead!" + "</body> " + "</html> ");

    }

    protected void handleNotFECEncoded(HttpServletRequest req, HttpServletResponse resp, String uri, SplitFileRequestContext context)
            throws IOException {

        sendHtml(resp, HttpServletResponse.SC_NOT_IMPLEMENTED, "<html> " + "<head> " + "<title>Not a FEC Encoded</title> " + "</head> " + "<body> "
                + "<h1>Not a FEC Encoded</h1> " + " The requested URI is a SplitFile, but it isn't FEC encoded." + " <p> "
                + " This file can't be downloaded. Retrying won't help. " + " Ask the content author to re-insert it with FEC." + "</body> "
                + "</html> ");

    }

    protected void handleFilterException(HttpServletRequest req, HttpServletResponse resp, FilterException fe, SplitFileRequestContext context)
            throws IOException {

        sendHtml(resp, HttpServletResponse.SC_OK, "<html> " + "<head> " + "<title>Filter Exception</title> " + "</head> " + "<body> "
                + "<h1>Filter Exception</h1> " + "</body> " + "</html> ");

        // REDFLAG:
        // Unimplemented.

        // should print filter exception, with link to
        // context.overrideFilterURL
    }

    protected void handleRequestFailed(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context) throws IOException {

        sendHtml(resp, HttpServletResponse.SC_NOT_FOUND, "<html> " + "<head> " + "<title>Freenet Request Failed</title> " + "</head> " + "<body> "
                + "<h1>Freenet Request Failed</h1> " + "</body> " + "</html> ");
    }

    protected void handleMetadataRequestFailed(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context)
            throws IOException {

        sendHtml(resp, HttpServletResponse.SC_NOT_FOUND, "<html> " + "<head> " + "<title>Freenet SplitFile Metadata Request Failed</title> "
                + "</head> " + "<body> " + "<h1>Freenet SplitFile Metadata Request Failed</h1> "
                + "Couldn't start downloading the SplitFile because the SplitFile metadata " + "couldn't be retrieved from Freenet. " + "</body> "
                + "</html> ");
    }

    ////////////////////////////////////////////////////////////
    // Helper functions render appropriate replies for
    // each type of suburl request.
    //

    protected void onNewContext(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        // Parse out url
        String key = null;
        String path = null;
        boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);
        try {
            if (logDEBUG) logger.log(this, "Got request: " + key, Logger.DEBUG);
            // getRequestURI() does not include params, so can be decoded
            key = URLDecoder.decode(req.getRequestURI());
            if (logDEBUG) logger.log(this, "Decoded: " + key, Logger.DEBUG);
            path = URLDecoder.decode(req.getServletPath());

            int pos = key.indexOf(req.getServletPath());

            if (pos == -1 || pos + req.getServletPath().length() == key.length() - 1) {
                handleBadURI(req, resp);
                return;
            }

            key = key.substring(pos + req.getServletPath().length());

        } catch (URLEncodedFormatException uefe) {
            handleBadURI(req, resp);
            return;
        }

        // chop leading /
        if (key != null && key.startsWith("/")) {
            key = key.substring(1);
        }

        // Registers itself.
        SplitFileRequestContext context = new SplitFileRequestContext(defaultLifetimeMs, key, path, this, bf);

        String warning = context.updateParameters(req);
        if (warning == null) {

            if (context.getMetadata(req, resp)) {
                // Send redirect to kick off
                // request for metadata.
                //          String s = req.getHeader("accept");
                // 	    freenet.Node.logger.log(this, "Accept header: "+s,
                // 				    freenet.Node.logger.DEBUG);
                // Disabled: Mozilla at least changes the Accept headers on
                // redirection
                // 	    if((s.indexOf("image") != -1) && (s.indexOf("text") == -1))
                // 		context.useUI = false; // Streaming as an inline image
                // 	    if(context.sf.getSize() < context.useUIMinSize)
                // 		context.useUI = false;
                if (context.useUI) {
                    resp.sendRedirect(URLEncoder.encode(context.parameterURL()));
                    if (logDEBUG) logger.log(this, "Sending redirect: " + context.parameterURL(), Logger.DEBUG);
                } else {
                    resp.sendRedirect(URLEncoder.encode(context.downloadURL()));
                    if (logDEBUG) logger.log(this, "Sending redirect: " + context.downloadURL(), Logger.DEBUG);
                }
            }
            // else
            // getMetaData sends back an error msg.
        } else {
            onParameterForm(req, resp, context, warning);
        }
    }

    protected void onDownload(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context) throws IOException {
        boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);
        if (logDEBUG) logger.log(this, "onDownload(,," + context + ") on " + this, Logger.DEBUG);
        try {
            // This blocks until the request is finished or canceled.
            context.doRequest(req, resp);
        } catch (IllegalStateException es) {
            // Thrown if the request has been called more than once.
            if (logDEBUG) logger.log(this, "onDownload(,," + context + ") on " + this + " got " + es, es, Logger.DEBUG);
            handleIllegalDownloadState(req, resp, context);
        }
    }

    protected void onCancel(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context) throws IOException {
        boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);
        if (logDEBUG) logger.log(this, "Called onCancel()", Logger.DEBUG);
        context.cancel();
        if (logDEBUG) logger.log(this, "Out of context.cancel()", Logger.DEBUG);

        StringWriter psw = new StringWriter();
        PrintWriter ppw = new PrintWriter(psw);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        context.titleBoxTmp.set("TITLE", "Splitfile Download Cancelled");
        pw.println("<p>You have chosen to cancel the splitfile download. ");
        pw.println("Use your browser's history function to return to the page you ");
        pw.println("visited when you started this download.</p>");
        context.titleBoxTmp.set("CONTENT", sw.toString());
        context.titleBoxTmp.toHtml(ppw);

        if (logDEBUG) logger.log(this, "onCancel writing: " + psw.toString(), Logger.DEBUG);

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");

        context.pageTmp.set("TITLE", "Splitfile Download Cancelled");
        context.pageTmp.set("BODY", psw.toString());
        PrintWriter w = resp.getWriter();
        context.pageTmp.toHtml(w);
        w.close();

        if (logDEBUG) logger.log(this, "Finished writing in onCancel()", Logger.DEBUG);
        resp.flushBuffer();
        if (logDEBUG) logger.log(this, "Out of onCancel()", Logger.DEBUG);
    }

    // hmmm... not really required. but pedantic.
    protected void onOverrideFilter(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context) throws IOException {
        context.doOverrideFilter(req, resp);
    }

    private final void renderTopStatusFrame(PrintWriter pw, SplitFileRequestContext context) {

        SegmentHeader header = null;
        if (context.status != null) {
            header = context.status.segment();
        }
        HtmlTemplate titleBoxTmp = context.titleBoxTmp;

        StringWriter tsw = new StringWriter();
        PrintWriter tpw = new PrintWriter(tsw);

        // split uri into key and filename (if a filename is given)
        String key = context.displayKey();
        String filename = context.filename();

        titleBoxTmp.set("TITLE", "Splitfile Download");
        tpw.println("<table border=\"0\"><tr>");
        Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/download.png");
        tpw.println("<td valign=\"top\"><img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/download.png\" alt=\"\" width=\""
                + size.getWidth() + "\" height=\"" + size.getHeight() + "\"></img></td>");
        tpw.println("<td width=\"10\">&nbsp;</td>");
        tpw.println("<td valign=\"top\">");
        String shortKey = key;
        if (shortKey.startsWith("freenet:")) shortKey = key.substring("freenet:".length(), key.length());
        String saveKey = context.uri;
        if (saveKey.startsWith("freenet:")) saveKey = saveKey.substring("freenet:".length(), saveKey.length());
        tpw.println("<p><b>Key</b>: freenet:<a href=\"/" + HTMLEncoder.encode(URLEncoder.encode(saveKey)) + "\">" + shortKey + "</a>");
        if (filename != null) {
            tpw.println("<br /><b>Filename</b>: " + filename + ", " + "<b>Length:</b> " + format(context.sf.getSize()));
        }
        if (header != null) {
            long totalSize = context.status.dataSize();
            if (totalSize == 0) {
                totalSize = context.sf.getSize();
            }
            double progress = (double) context.status.retrievedBytes() / (double) totalSize;
            int width = 300;

            tpw.println("<br /><b>Status</b>: ");
            try {
                HtmlTemplate barTemplate = null;
                if (progress == 0.0) {
                    barTemplate = HtmlTemplate.createTemplate("bar.tpl");
                    barTemplate.set("COLOR", "");
                    barTemplate.set("WIDTH", Integer.toString(width));
                } else if (progress == 1.0) {
                    barTemplate = HtmlTemplate.createTemplate("bar.tpl");
                    barTemplate.set("COLOR", "g");
                    barTemplate.set("WIDTH", Integer.toString(width));
                } else {
                    barTemplate = HtmlTemplate.createTemplate("relbar.tpl");
                    barTemplate.set("LBAR", "g");
                    barTemplate.set("RBAR", "");
                    barTemplate.set("LBARWIDTH", Integer.toString((int) (progress * width)));
                    barTemplate.set("RBARWIDTH", Integer.toString((int) ((1 - progress) * width)));
                }
                barTemplate.set("ALT", Integer.toString((int) progress * 100) + "%");
                barTemplate.toHtml(tpw);

            } catch (IOException e) {
                logger.log(this, "Couldn't load template", e, Logger.NORMAL);
            }
            //long deltat = (System.currentTimeMillis() -
            // context.status.touched()) / 1000;
            //if (deltat < 60) {
            //    tpw.print(" <b>Idle</b>: " + deltat + " seconds");
            //} else {
            //    tpw.print(" <b>Idle</b>: " + (int) (deltat / 60) + " minutes");
            //}
            tpw.print("<br /><b>Request Started:</b> " + context.dateFormat.format(context.startTime.getTime()));
            tpw.println(", <b>Time Elapsed:</b> " + timeDistance(context.startTime, Calendar.getInstance()));
            if ((progress >= 0.1) || ((context.status.blocksProcessed() > 4) && (context.status.retrievedBytes() > 0))) {
                long start = context.startTime.getTime().getTime();
                long elapsed = Calendar.getInstance().getTime().getTime() - start;
                long end = start + (long) (elapsed / progress);
                Calendar eta = Calendar.getInstance();
                eta.setTime(new Date(end));
                tpw.println("<br /><b>Estimated Finish Time:</b> " + context.dateFormat.format(eta.getTime()));
                long throughput = context.status.retrievedBytes() * 1000 / elapsed;
                tpw.println("<br /><b>Speed:</b> " + format(throughput) + "/s"); //K.I.S.S.!!!
            }
        }
        tpw.println("</p></td></tr></table>");
        titleBoxTmp.set("CONTENT", tsw.toString());
        titleBoxTmp.toHtml(pw);
        pw.println("<br />");
    }

    // Break table into a separate function.
    private final void renderRunningDownloadStatus(PrintWriter pw, SplitFileRequestContext context) {

        SegmentHeader header = context.status.segment();

        StringWriter tsw = new StringWriter();
        PrintWriter tpw = new PrintWriter(tsw);

        HtmlTemplate titleBoxTmp = context.titleBoxTmp;

        if (header != null) {
            tsw = new StringWriter();
            tpw = new PrintWriter(tsw);

            if (header.getCheckBlockCount() != 0) {
                titleBoxTmp.set("TITLE", "Required Blocks: " + header.getBlocksRequired() + ", Received Blocks: " + context.status.blocksProcessed());
                for (int i = 0; i < header.getBlocksRequired(); i++) {
                    if (i < context.status.blocksProcessed()) {
                        tpw.println(new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_SUCCESS).render());
                    } else {
                        tpw.println(new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_WAITING).render());
                    }
                }
            } else {
                titleBoxTmp.set("TITLE", "Attempting Non-Redundant Splitfile Download");
                tpw.println("<p>Warning! You are attempting a non-redundant splitfile download! ");
                tpw.println("This means that the download will fail if just one block can not ");
                tpw.println("be retrieved from the network! If you can, please notify the ");
                tpw.println("person who inserted this file and ask her to re-insert it using ");
                tpw.println("the Fproxy File Insertion utility from the Freenet Gateway Page. ");
                tpw.println("That will automatically make sure that redundant check blocks ");
                tpw.println("are inserted, thus making a complete download much easier.</p>");
            }
            titleBoxTmp.set("CONTENT", tsw.toString());
            titleBoxTmp.toHtml(pw);
            pw.println("<br />");

            tsw = new StringWriter();
            tpw = new PrintWriter(tsw);

            int completed = 0;
            for (int p = 0, i = 0; i < header.getBlockCount() + header.getCheckBlockCount(); i++) {
                int img;
                String alt = "[Block " + i + "] ";
                switch (context.status.retrievedBlockStatus(i)) {
                case SplitFileStatus.QUEUED:
                    img = HTMLTransferProgressIcon.ICONTYPE_WAITING;
                    alt += "Queued";
                    break;
                case SplitFileStatus.UNDEFINED:
                    img = HTMLTransferProgressIcon.ICONTYPE_WAITING;
                    alt += "Waiting";
                    break;
                case SplitFileStatus.RUNNING:
                    if (context.status.retrievedBlockSize(i) > 0) {
                        img = HTMLTransferProgressIcon.ICONTYPE_TRANSFERING;
                        alt += "Transferring... (" + (context.status.retrievedBlockRetries(i) + 1) + ". Try)";
                    } else {
                        img = HTMLTransferProgressIcon.ICONTYPE_PROGRESS;
                        alt += "In Progress... (" + (context.status.retrievedBlockRetries(i) + 1) + ". Try)";
                    }
                    break;
                case SplitFileStatus.FAILED_RNF:
                    img = HTMLTransferProgressIcon.ICONTYPE_FAILURE;
                    alt += "Failed (Route Not Found)";
                    break;
                case SplitFileStatus.FAILED_DNF:
                    img = HTMLTransferProgressIcon.ICONTYPE_FAILURE;
                    alt += "Failed (Data Not Found)";
                    break;
                case SplitFileStatus.FAILED:
                    img = HTMLTransferProgressIcon.ICONTYPE_FAILURE;
                    alt += "Failed (Unknown Reason)";
                    break;
                case SplitFileStatus.REQUEUED_RNF:
                    img = HTMLTransferProgressIcon.ICONTYPE_RETRY;
                    alt += "Route Not Found, Will Retry";
                    break;
                case SplitFileStatus.REQUEUED_DNF:
                    img = HTMLTransferProgressIcon.ICONTYPE_RETRY;
                    alt += "Data Not Found, Will Retry";
                    break;
                case SplitFileStatus.REQUEUED:
                    img = HTMLTransferProgressIcon.ICONTYPE_RETRY;
                    alt += "Unknown Error, Will Retry";
                    break;
                case SplitFileStatus.SUCCESS:
                    img = HTMLTransferProgressIcon.ICONTYPE_SUCCESS;
                    alt += "Success (" + (p = context.status.retrievedBlockRetries(i)) + " Retr" + ((p == 1) ? "y" : "ies") + ")";
                    completed++;
                    break;
                default:
                    img = HTMLTransferProgressIcon.ICONTYPE_WAITING;
                    alt += "Running - status " + context.status.retrievedBlockStatus(i);
                }

                if (img == HTMLTransferProgressIcon.ICONTYPE_RETRY && (context.status.retrievedBlockRetries(i)) > 1) {
                    img = HTMLTransferProgressIcon.ICONTYPE_RETRY2;
                    alt = alt + " (" + context.status.retrievedBlockRetries(i) + ". Retry)";
                }
                tpw.println(new HTMLTransferProgressIcon(img, alt).render());
            }
            if (header.getCheckBlockCount() != 0) {
                titleBoxTmp.set("TITLE", "Segment " + (header.getSegmentNum() + 1) + ", " + (context.status.segmentNr() + 1) + " of "
                        + header.getSegments() + ", Download Queue: " + (header.getBlockCount() + header.getCheckBlockCount()) + " Blocks");
            } else {
                titleBoxTmp.set("TITLE", "Download Queue: " + (header.getBlockCount()) + " Blocks, Received Blocks: " + completed);
            }
            titleBoxTmp.set("CONTENT", tsw.toString());
            titleBoxTmp.toHtml(pw);
        } else {
            tpw.println("<p>Waiting for download to start&hellip;</p>");
            titleBoxTmp.set("TITLE", "Waiting for Download");
            titleBoxTmp.set("CONTENT", tsw.toString());
            titleBoxTmp.toHtml(pw);
        }
    }

    protected boolean setupFilter = false;

    protected void onSplitBottom(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context) throws IOException {

        boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);
        // if the download has already started, skip the bottom
        // frame and redirect to the status_progress page.
        if (context.state != SplitFileRequestContext.STATE_STARTING) {
            resp.sendRedirect(URLEncoder.encode(context.progressURL()));
            if (logDEBUG) logger.log(this, "Sending redirect: " + context.progressURL(), Logger.DEBUG);
            return;
        }

        String warning = context.updateParameters(req);

        if (warning == null) {

            if (!(context.writeDir == null || disableWriteToDisk || context.writeToDisk == false)) {

                if (logDEBUG) logger.log(this, "Starting background request for " + this, Logger.DEBUG);

                context.doBackgroundRequest();

                resp.sendRedirect(context.progressURL());
                return;
            }

            if (!setupFilter) {
                setupFilter = true;
                context.setupFilter(); // need to run it if only to null out
                // filter... see comments
            }

            Writer w = resp.getWriter();
            String s = "<html><head><title>";
            if (context.state == SplitFileRequestContext.STATE_FILTER_FAILED || context.state == SplitFileRequestContext.STATE_CANCELED
                    || context.state == SplitFileRequestContext.STATE_FAILED)
                s += "Failed download - ";
            else if (context.state == SplitFileRequestContext.STATE_DONE)
                s += "Finished download - ";
            else if (context.state == SplitFileRequestContext.STATE_SENDING_DATA)
                s += "Sending data - ";
            else if (context.state == SplitFileRequestContext.STATE_FILTERING_DATA)
                s += "Filtering data - ";
            else
                s += ((int) (context.progress() * 100.0)) + " % - ";
            w.write(s + HTMLEncoder.encode(context.filename()) + "</title></head>");
            String dlFrame = "  <frame name=\"download\" src=\"" + HTMLEncoder.encode(URLEncoder.encode(context.downloadURL())) + "\">";
            String detailFrame = "  <frame name=\"details\" src=\"" + HTMLEncoder.encode(URLEncoder.encode(context.progressURL())) + "\">";
            if (logDEBUG)
                    logger.log(this, "MIME type: " + context.mimeType + ", mimeTypeParsed: " + context.mimeTypeParsed + ", default: "
                            + DEFAULT_MIME_TYPE, Logger.DEBUG);

            if (context.mimeType.equals(DEFAULT_MIME_TYPE)) {
                if (logDEBUG) logger.log(this, "Writing *,0 frameset for " + context.uri, Logger.DEBUG);
                w.write("<frameset rows=\"*,0\">");
            } else {
                if (logDEBUG) logger.log(this, "Writing 1,1 frameset for " + context.uri, Logger.DEBUG);
                w.write("<frameset rows=\"1,1\">");
            }
            w.write(detailFrame);
            w.write(dlFrame);
            w.write("</frameset></html>");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/html");
            w.flush();
        } else {
            onParameterForm(req, resp, context, warning);
        }
    }

    protected void onParameterForm(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context) throws IOException {
        onParameterForm(req, resp, context, null);
    }

    protected void onParameterForm(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context, String warning)
            throws IOException {

        // if the download is already running, create the next frameset.

        HtmlTemplate titleBoxTmp = context.titleBoxTmp;
        HtmlTemplate pageTmp = context.pageTmp;

        if ((context.state != SplitFileRequestContext.STATE_INIT) && (context.state != SplitFileRequestContext.STATE_REQUESTING_METADATA)
                && (context.state != SplitFileRequestContext.STATE_STARTING)) {
            resp.sendRedirect(URLEncoder.encode(context.splitBottomURL()));
            return;
        }

        String htlAsString = Integer.toString(context.blockHtl);
        String retryHtlIncrementAsString = Integer.toString(context.retryHtlIncrement);
        String healHtlAsString = Integer.toString(context.healHtl);
        String healPercentageAsString = Integer.toString(context.healPercentage);
        String retriesAsString = Integer.toString(context.retries);
        String threadsAsString = Integer.toString(context.threads);
        String forceSaveAsString = "";
        String skipDSAsString = "";
        String randomSegsAsString = "";
        String runFilterAsString = "";
        String paranoidStringCheckAsString = "";
        String writeToDiskAsString = "";

        context.preSetupFilter();

        // For HTML, checkboxes are on if checked attribute is set
        // The value parameter is what is sent if they are checked
        if (context.forceSave) {
            forceSaveAsString = " checked";
        }

        if (context.skipDS) {
            skipDSAsString = " checked";
        }

        if (context.randomSegs) {
            randomSegsAsString = " checked";
        }

        if (context.runFilter) {
            runFilterAsString = " checked";
        }

        if (context.filterParanoidStringCheck) {
            paranoidStringCheckAsString = " checked";
        }

        if (context.writeToDisk) writeToDiskAsString = " checked";

        StringWriter psw = new StringWriter();
        PrintWriter ppw = new PrintWriter(psw);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        titleBoxTmp.set("TITLE", "Splitfile Download Request");
        pw.println("Downloading large SplitFiles can be a resource intensive operation. ");
        pw.println("Hit the Back button on your browser if you want to abort. ");
        pw.println("You can also abort the download once it starts by hitting ");
        pw.println("the Back button on your browser or canceling the file save ");
        pw.println("dialog if you're saving to a file.");
        if (warning != null) pw.println("<hr><span class=\"warning\">" + warning + "</span><hr>");
        titleBoxTmp.set("CONTENT", sw.toString());
        titleBoxTmp.toHtml(ppw);
        ppw.println("<br />");

        sw = new StringWriter();
        pw = new PrintWriter(sw);

        titleBoxTmp.set("TITLE", "Splitfile Download Parameters");
        context.writeHtml(pw, true);
        pw.println("<form method=\"GET\" action=\"" + HTMLEncoder.encode(URLEncoder.encode(context.splitBottomURL())) + "\">");
        // Hack to make checkbox work correctly.
        pw.println("<input type=\"hidden\" name=\"usedForm\" value=\"true\" >");
        pw.println("<table border=\"0\">");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"text\" name=\"blockHtl\" value=\"" + htlAsString + "\" size=\"5\"></td>");
        pw.println("    <td>Initial Hops to Live</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"text\" name=\"retries\" value=\"" + retriesAsString + "\" size=\"5\"></td>");
        pw.println("    <td>Number of Retries for a Block that Failed</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"text\" name=\"retryHtlIncrement\" value=\"" + retryHtlIncrementAsString
                + "\" size=\"5\"></td>");
        pw.println("    <td>Increment HTL on retry by this amount</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"checkBox\" name=\"forceSaveCB\" value=\"true\"" + forceSaveAsString + "></td>");
        pw.println("    <td>Force the Browser to Save the File</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"checkBox\" name=\"skipDSCB\" value=\"true\"" + skipDSAsString + "></td>");
        pw.println("    <td>Don't Look for Blocks in Local Data Store</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"checkBox\" name=\"randomSegs\" value=\"true\"" + randomSegsAsString + "></td>");
        pw.println("    <td>Download Segments in Random Order</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"text\" name=\"threads\" value=\"" + threadsAsString + "\" size=\"5\"></td>");
        pw.println("    <td>Number of Simultaneous Downloads</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"checkBox\" name=\"runFilterCB\" value=\"true\"" + runFilterAsString + "></td>");
        pw.println("    <td>Run Anonymity Filter on Download Completion (recommended)</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"checkBox\" name=\"filterParanoidStringCheck\" value=\"true\"" + paranoidStringCheckAsString
                + "></td>");
        pw.println("    <td>Make the Anonymity Filter Really Paranoid</td");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"text\" name=\"healPercentage\" value=\"" + healPercentageAsString + "\" size=\"5\"></td>");
        pw.println("    <td>% of Missing Data Blocks to Insert (Healing)</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("    <td align=\"right\"><input type=\"text\" name=\"healHtl\" value=\"" + healHtlAsString + "\" size=\"5\"></td>");
        pw.println("    <td>Hops-to-Live for the Healing Blocks</td>");
        pw.println("</tr>");

        if (!disableWriteToDisk) {

            pw.println("<tr>");
            pw.println("    <td align=\"right\"><input type=\"checkBox\" " + "name=\"writeToDisk\" value=\"true\"" + writeToDiskAsString
                    + " size=\"5\"></td>");
            pw.println("    <td>Write directly to disk rather than sending to browser</td>");
            pw.println("</tr>");

            pw.println("<tr>");

            pw.println("<tr>");
            pw.println("    <td align=\"right\"><input type=\"text\" name=\"saveToDir\" " + "value=\""
                    + (defaultDownloadDir == null ? "" : defaultDownloadDir) + "\" size=\"15\"></td>");
            pw.println("    <td>Folder to write file to</td>");
            pw.println("</tr>");

        }

        pw.println("</table>");

        pw.println("<p><input type=\"submit\" value=\"Start Download\">");
        pw.println("</form>");
        titleBoxTmp.set("CONTENT", sw.toString());
        titleBoxTmp.toHtml(ppw);

        pageTmp.set("BODY", psw.toString());
        pageTmp.set("TITLE", "Splitfile Download Request");
        pageTmp.toHtml(resp.getWriter());

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");
        resp.getWriter().flush();
    }

    protected void onStatusProgress(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context) throws IOException {

        boolean updating = false;

        boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);

        StringWriter psw = new StringWriter();
        PrintWriter ppw = new PrintWriter(psw);

        StringWriter sw;
        PrintWriter pw;

        HtmlTemplate titleBoxTmp = context.titleBoxTmp;
        HtmlTemplate pageTmp = null;

        synchronized (context) {

            updating = context.isUpdating();

            if ((context.refreshIntervalSecs > 0) && updating) {
                if (logDEBUG)
                        logger.log(this, "Scheduling status page update in " + context.refreshIntervalSecs + "s for " + context.displayKey(),
                                Logger.DEBUG);

                // Optional client pull updating.
                pageTmp = context.refreshPageTmp;
                pageTmp.set("REFRESH-TIME", Integer.toString(context.refreshIntervalSecs));
                pageTmp.set("REFRESH-URL", HTMLEncoder.encode(URLEncoder.encode(context.progressURL())));

            } else {
                if (logDEBUG) logger.log(this, "Not scheduling status page update for " + context.displayKey(), Logger.DEBUG);
                pageTmp = context.pageTmp;
            }

            switch (context.state) {
            case SplitFileRequestContext.STATE_INIT:
            case SplitFileRequestContext.STATE_REQUESTING_METADATA:

                titleBoxTmp.set("TITLE", "Current Download Status");
                titleBoxTmp.set("CONTENT", "<p>Downloading Splitfile Metadata...</p>");
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", ((int) (context.progress() * 100.0)) + "% - " + HTMLEncoder.encode(context.filename()));
                if (logDEBUG) logger.log(this, "Requesting splitfile metadata for " + context.displayKey(), Logger.DEBUG);
                break;
            case SplitFileRequestContext.STATE_STARTING:
                renderTopStatusFrame(ppw, context);
                titleBoxTmp.set("TITLE", "Starting Download");
                titleBoxTmp.set("CONTENT", "<p>The Splitfile Download is about to start...</p>");
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", ((int) (context.progress() * 100.0)) + "% - " + HTMLEncoder.encode(context.filename()));
                if (logDEBUG) logger.log(this, "Starting download for " + context.displayKey(), Logger.DEBUG);
                break;
            case SplitFileRequestContext.STATE_WORKING:
                synchronized (context.status) {
                    if (context.status.statusCode() == SplitFileStatus.DECODING) {
                        renderTopStatusFrame(ppw, context);
                        SegmentHeader header = context.status.segment();
                        titleBoxTmp.set("TITLE", "FEC Decoding Segment " + (header.getSegmentNum() + 1) + " of " + header.getSegments());
                        titleBoxTmp.set("CONTENT", "<p>FEC decoding " + "missing data blocks... this may take " + "a while; please be patient.</p>");
                        titleBoxTmp.toHtml(ppw);
                        pageTmp.set("TITLE", ((int) (context.progress() * 100.0)) + "% - " + HTMLEncoder.encode(context.filename()));
                        if (logDEBUG)
                                logger.log(this, "Decoding segment " + (header.getSegmentNum() + 1) + " of " + header.getSegments() + " for "
                                        + context.displayKey(), Logger.DEBUG);

                    } else if (context.status.statusCode() == SplitFileStatus.INSERTING_BLOCKS) {
                        //titleBoxTmp.set("TITLE", "Current Download Status");
                        //titleBoxTmp.set("CONTENT", "<p>Re-inserting " +
                        // context.status.reinsertions() +
                        //                " reconstructed blocks " +
                        //                " to heal the network. </p>");
                        //titleBoxTmp.toHtml(ppw);
                        // TODO: show the status display for the inserted
                        // blocks???
                        // well, we shouldn't get here anymore. background
                        // healing.
                        // print a warning if we do.
                        logger.log(this, "Warning! Reached State INSERTING_BLOCKS!", Logger.ERROR);
                        //renderTopStatusFrame(ppw, context);
                        //renderRunningUploadStatus(ppw, context);
                    } else if (context.status.statusCode() == SplitFileStatus.VERIFYING_CHECKSUM) {
                        renderTopStatusFrame(ppw, context);
                        titleBoxTmp.set("TITLE", "Current Download Status");
                        titleBoxTmp.set("CONTENT", "<p> Verifying checksum: " + context.status.checksum() + " </p>");
                        titleBoxTmp.toHtml(ppw);
                        pageTmp.set("TITLE", "Verifying Checksum - " + HTMLEncoder.encode(context.filename()));
                        if (logDEBUG) logger.log(this, "Starting download for " + context.displayKey(), Logger.DEBUG);

                    } else {
                        renderTopStatusFrame(ppw, context);
                        renderRunningDownloadStatus(ppw, context);
                        pageTmp.set("TITLE", ((int) (context.progress() * 100.0)) + "% - " + HTMLEncoder.encode(context.filename()));
                        if (logDEBUG)
                                logger.log(this, "Working on " + context.displayKey() + ", " + ((int) (context.progress() * 100.0)) + "%",
                                        Logger.DEBUG);
                    }
                }

                break;
            case SplitFileRequestContext.STATE_FILTERING_DATA:
                renderTopStatusFrame(ppw, context);
                titleBoxTmp.set("TITLE", "Current Download Status");
                titleBoxTmp.set("CONTENT", "<p>Running the anonymity filter. REDFLAG: UNIMPLEMENTED!!!!");
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Filtering data - " + HTMLEncoder.encode(context.filename()));
                break;
            case SplitFileRequestContext.STATE_FILTER_FAILED:
                // Hmmmm... what's the right thing to display for this case.
                titleBoxTmp.set("TITLE", "Current Download Status");
                titleBoxTmp.set("CONTENT", "Anonymity filter failed. REDFLAG: UNIMPLEMENTED!!!!");
                pageTmp.set("TITLE", "Anonymity Filter Failed! - " + HTMLEncoder.encode(context.filename()));
                titleBoxTmp.toHtml(ppw);
                break;
            case SplitFileRequestContext.STATE_SENDING_DATA:
                // Hmmmm... what's the right thing to display for this case.
                titleBoxTmp.set("TITLE", "Current Download Status");
                titleBoxTmp.set("CONTENT", "<p>Download finished successfully. " + "Sending data to the browser...</p>" + "<p><b>Time Elapsed:</b> "
                        + timeDistance(context.startTime, Calendar.getInstance()) + "</p>"); // we
                // don't
                // have
                // endTime
                // yet
                pageTmp.set("TITLE", "Sending data - " + HTMLEncoder.encode(context.filename()));
                titleBoxTmp.toHtml(ppw);
                logger.log(this, "Sending " + context.filename() + " to the browser", Logger.MINOR);
                break;
            case SplitFileRequestContext.STATE_DONE:
                titleBoxTmp.set("TITLE", "Current Download Status");
                titleBoxTmp.set("CONTENT", "<p>Download finished successfully.</p>" + "<p><b>Time for Completion:</b> "
                        + timeDistance(context.startTime, context.endTime) + "</p>");
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Completed - " + HTMLEncoder.encode(context.filename()));
                if (logDEBUG) logger.log(this, "Completed " + context.displayKey(), Logger.DEBUG);
                break;
            case SplitFileRequestContext.STATE_FAILED:
                sw = new StringWriter();
                pw = new PrintWriter(sw);

                titleBoxTmp.set("TITLE", "Current Download Status");
                if (context.errMsg != null) {
                    pw.println("<p>" + context.errMsg + "</p>");
                    logger.log(this, "Download failed because " + context.errMsg + " for " + context.displayKey(), Logger.MINOR);
                } else {
                    pw.println("<p>Download failed. Couldn't get status of last block.</p>");
                    logger.log(this, "Download failed for unknown reasons", Logger.NORMAL);
                }
                pw.println("<p>Click <a href=\"" + HTMLEncoder.encode(URLEncoder.encode(context.retryURL())) + "\" "
                        + "target=\"_top\">here</a> to retry.</p>");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Failed - " + HTMLEncoder.encode(context.filename()));

                break;
            case SplitFileRequestContext.STATE_CANCELED:
                renderTopStatusFrame(ppw, context);
                titleBoxTmp.set("TITLE", "Current Download Status");
                String content = "<p>Download aborted.  The client dropped" + " the connection, the Freenet request timed out, or it"
                        + " was manually cancelled.</p>";
                titleBoxTmp.set("CONTENT", content);
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Cancelled - " + HTMLEncoder.encode(context.filename()));
                logger.log(this, "Download of " + context.displayKey() + " cancelled", Logger.MINOR);
                break;
            default:
            }

            if (updating) {
                ppw.println("<p>");
                ppw.println("[&nbsp;<a href=\"" + HTMLEncoder.encode(URLEncoder.encode(context.progressURL())) + "\">Update Status Info</a>&nbsp;] ");
                ppw.println("[&nbsp;<a href=\"" + HTMLEncoder.encode(URLEncoder.encode(context.cancelURL())) + "\">Cancel Download</a>&nbsp;]</p>");

            }
        }

        pageTmp.set("BODY", psw.toString());
        pageTmp.toHtml(resp.getWriter());

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");
        resp.getWriter().flush();
    }

    // checked to here

    protected void handleUnknownCommand(HttpServletRequest req, HttpServletResponse resp, SplitFileRequestContext context) throws IOException {

        sendHtml(resp, HttpServletResponse.SC_BAD_REQUEST, "<html> " + "<head> " + "<title>Unknown Command</title> " + "</head> " + "<body> "
                + "<h1>Unknown Command</h1> " + "</body> " + "</html> ");

    }

    // LATER: Copy scipients code from from VirtualClient to
    //        automagically map requests to functions using
    //        Class.getMethod()? Would belong in base.
    // PRO: 1337.
    // CON: might mess up compiled java efforts
    //      obscures control flow.
    //      not nesc.? When has that ever kept a hack out of the codebase ;-)

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        String uri = null;

        try {
            uri = URLDecoder.decode(req.getRequestURI());
            //System.err.println("sfrs uri: " + uri);
            //System.err.println("servlet path: " + req.getServletPath());
        } catch (URLEncodedFormatException uefe) {
            handleBadURI(req, resp);
            return;
        }

        SplitFileRequestContext context = (SplitFileRequestContext) getContextFromURL(uri);

        if (context == null) {
            if (hasContextID(uri)) {
                // Context ID was there but it's
                // bogus or expired.

                // Implement in base.
                handleBadContext(req, resp);
                return;
            } else {
                // No context ID.
                // Start a new context.
                onNewContext(req, resp);
                return;
            }
        }

        String cmd = getFirstPathElement(req.getRequestURI());
        if (cmd.equals("download")) {
            onDownload(req, resp, context);
        } else if (cmd.equals("cancel")) {
            onCancel(req, resp, context);
        } else if (cmd.equals("override_filter")) {
            onOverrideFilter(req, resp, context);
        } else if (cmd.equals("parameter_form")) {
            onParameterForm(req, resp, context);
        } else if (cmd.equals("split_bottom")) {
            onSplitBottom(req, resp, context);
        } else if (cmd.equals("status_progress")) {
            onStatusProgress(req, resp, context);
        } else {
            handleUnknownCommand(req, resp, context);
        }
    }

    private String timeDistance(Calendar start, Calendar end) {
        if (end == null) throw new IllegalStateException("end NULL!");
        if (start == null) throw new IllegalStateException("start NULL!");
        String result = "";
        long tsecs = (end.getTime().getTime() - start.getTime().getTime()) / 1000;
        int sec = (int) (tsecs % 60);
        int min = (int) ((tsecs / 60) % 60);
        int hour = (int) ((tsecs / 3600) % 24);
        int days = (int) (tsecs / (3600 * 24));
        /* I hope we don't have to calculate weeks or months... :) */
        if (days > 0) {
            result += days + " day" + ((days != 1) ? "s" : "");
        }
        if (hour > 0) {
            result += ((days > 0) ? ", " : "") + hour + " hour" + ((hour != 1) ? "s" : "");
        }
        if (min > 0) {
            result += (((days + hour) > 0) ? ", " : "") + min + " minute" + ((min != 1) ? "s" : "");
        }
        if (sec > 0) {
            result += (((days + hour + min) > 0) ? ", " : "") + sec + " second" + ((sec != 1) ? "s" : "");
        }
        return result;
    }

    private String format(long bytes) {
        if (bytes == 0) return "None";
        if (bytes % 1152921504606846976L == 0) return nf.format(bytes / 1152921504606846976L) + "EiB";
        if (bytes % 1125899906842624L == 0) return nf.format(bytes / 1125899906842624L) + " PiB";
        if (bytes % 1099511627776L == 0) return nf.format(bytes / 1099511627776L) + " TiB";
        if (bytes % 1073741824 == 0) return nf.format(bytes / 1073741824) + " GiB";
        if (bytes % 1048576 == 0) return nf.format(bytes / 1048576) + " MiB";
        if (bytes % 1024 == 0) return nf.format(bytes / 1024) + " KiB";
        return nf.format(bytes) + " Bytes";
    }

    // Sets contexts parameters to the servlet's
    // defaults.
    protected void setDefaultContextValues(SplitFileRequestContext context) {
        context.logger = logger;
        context.cf = cf;
        SplitFileRequestContext.bf = bf;

        context.htl = defaultHtl;
        context.blockHtl = defaultBlockHtl;
        context.retries = defaultRetries;
        context.retryHtlIncrement = defaultRetryHtlIncrement;
        context.healHtl = defaultHealHtl;
        context.healPercentage = defaultHealPercentage;
        context.threads = defaultThreads;
        context.doParanoidChecks = defaultDoParanoidChecks;
        context.refreshIntervalSecs = defaultRefreshIntervalSecs;
        context.forceSave = defaultForceSave;
        context.writeToDisk = defaultWriteToDisk;
        context.disableWriteToDisk = disableWriteToDisk;
        context.skipDS = defaultSkipDS;
        context.useUI = defaultUseUI;
        //context.useUIMinSize = defaultUseUIMinSize;
        context.runFilter = defaultRunFilter;
        context.randomSegs = defaultRandomSegs;
        context.filterParanoidStringCheck = defaultFilterParanoidStringCheck;
    }

}
