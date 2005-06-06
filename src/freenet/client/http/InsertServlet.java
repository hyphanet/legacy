package freenet.client.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.client.AutoRequester;
import freenet.client.ClientEvent;
import freenet.client.SplitFileStatus;
import freenet.client.events.SplitFileEvent;
import freenet.client.http.ImageServlet.Dimension;
import freenet.keys.SVK;
import freenet.message.client.FEC.SegmentHeader;
import freenet.node.Node;
import freenet.node.http.infolets.HTMLTransferProgressIcon;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;
import freenet.support.mime.MIME;
import freenet.support.mime.MIMEFormatException;
import freenet.support.mime.MIME_binary;
import freenet.support.mime.MIME_multipart;
import freenet.support.servlet.HtmlTemplate;

public class InsertServlet extends ServletWithContext {

    // Default values which are used if no values are specified in
    // the config file or the request URL.
    int defaultHtl = 15;

    int defaultRetries = 3;

    int defaultThreads = 5;

    int defaultRefreshIntervalSecs = 30;

    int defaultLifetimeMs = 60 * 60000;

    // REDFLAG: TEMPORARY HACK. This has to come out.
    // Used to allow posting to "/" to get backward
    // compatibility for NIM.
    //
    String redirectPath = null;

    private NumberFormat nf = NumberFormat.getInstance();

    ////////////////////////////////////////////////////////////
    // Servlet initialization
    ////////////////////////////////////////////////////////////
    public void init() {
        ServletContext context = getServletContext();
        setupLogger(context);
        setupBucketFactory(context);
        setupClientFactory(context);

        defaultHtl = ParamParse.readInt(this, logger, "insertHtl", defaultHtl, 0, 100);
        defaultRetries = ParamParse.readInt(this, logger, "sfInsertRetries", defaultRetries, 0, 50);
        defaultThreads = ParamParse.readInt(this, logger, "sfInsertThreads", defaultThreads, 0, 100);
        defaultRefreshIntervalSecs = ParamParse.readInt(this, logger, "sfRefreshIntevalSecs", defaultRefreshIntervalSecs, -1, 3600);
        // REDFLAG: BUG. freenet.conf param reading borken.
        // e.g. With this line in my freenet conf,
        // the getInitParameter call below returns null.
        //
        // mainport.params.servlet.4.redirectPath=/servlet/Insert

        redirectPath = getInitParameter("redirectPath");

        // REDFLAG: remove hack after parameter bug is fixed.
        if (redirectPath == null) {
            redirectPath = "/servlet/Insert";
        }

        // This is fuXORd too. not just Strings.
        //int lala = ParamParse.readInt(this, logger, "lala", defaultHtl, 0,
        // 1000);
        //System.err.println("lala: " + lala);

        logger.log(this, "New InsertServlet created", Logger.MINOR);
        logger.log(this, "   insertHtl = " + defaultHtl, Logger.DEBUG);
        logger.log(this, "   sfInsertRetries = " + defaultRetries, Logger.DEBUG);
        logger.log(this, "   sfInsertThreads = " + defaultThreads, Logger.DEBUG);
        logger.log(this, "   sfRefreshIntervalSecs = " + defaultRefreshIntervalSecs, Logger.DEBUG);

        logger.log(this, "   redirectPath = " + redirectPath, Logger.DEBUG);
    }

    ////////////////////////////////////////////////////////////
    // Presentation
    ////////////////////////////////////////////////////////////

    // Presentation states
    public final static int STATE_STARTING = 1;

    public final static int STATE_INSERTING = 2;

    public final static int STATE_CANCELING = 3;

    public final static int STATE_DONE = 4;

    public final static int STATE_FAILED = 5;

    public final static int STATE_CANCELED = 6;

    ////////////////////////////////////////////////////////////
    // suburls
    // /start POST only, reads data
    // /legacy_start POST only, transitions immediately to inserting
    //               w/o confirming (for NIM).
    //               aliased to /
    //
    // /confirming displays/reads parameters, GET
    // /insert starts insert, GET
    //                  
    // /status displays status, GET
    // /cancel cancels insert, GET
    ////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////
    // Error handling functions.
    // Most others are inherited from ServletWithContext.
    //
    protected void onRepost(HttpServletRequest req, HttpServletResponse resp, InsertContext context) throws IOException {

        sendHtml(resp, HttpServletResponse.SC_BAD_REQUEST, "<html> " + "<head> " + "<title>Repost attempt</title> " + "</head> " + "<body> "
                + "<h1>Repost attemp</h1> " + "You can only post the data once.  Restart the " + "insert from scratch" + "</body> " + "</html> ");

        // REDFLAG: retry link?
    }

    protected void onPostFailed(HttpServletRequest req, HttpServletResponse resp, InsertContext context) throws IOException {

        String msg = "";
        if ((context != null) && (context.error != null)) {
            msg = " " + context.error + " ";
        }
        sendHtml(resp, HttpServletResponse.SC_BAD_REQUEST, "<html> " + "<head> " + "<title>Couldn't read the posted data</title> " + "</head> "
                + "<body> " + "<h1>Couldn't read the posted data</h1> " + msg + "</body> " + "</html> ");
        // REDFLAG: retry link?
    }

    // Example form.
    // Not fit for public consumption, but useful to
    // template writers.
    protected void onDefaultForm(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        StringWriter psw = new StringWriter();
        PrintWriter ppw = new PrintWriter(psw);

        // local HtmlTemplate here due to lack of InsertContext
        HtmlTemplate pageTmp = HtmlTemplate.createTemplate("EmptyPage.html");
        HtmlTemplate titleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");

        StringWriter sw;
        PrintWriter pw;

        sw = new StringWriter();
        pw = new PrintWriter(sw);

        pw.println("<form method=\"post\" enctype=\"multipart/form-data\" " + "action=\"start\">");
        pw.println("<table border=\"0\">");

        pw.println("<tr>");
        pw.println("<td align=\"right\">");
        pw.println("<input type=\"text\" name=\"key\" size=\"40\"></td>");
        pw.println("<td>The Key to insert this data under. Use ");
        pw.println("&ldquo;CHK@&rdquo; for CHK insertion.</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("<td align=\"right\">");
        pw.println("<input type=\"text\" name=\"htl\" size=\"5\"></td>");
        pw.println("<td>The Hops-to-Live value for this insert.</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("<td align=\"right\">");
        pw.println("<input type=\"file\" name=\"filename\" size=\"30\"></td>");
        pw.println("<td>The file to insert.</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("<td align=\"right\"><select name=\"content-type\">");
        pw.println("<option selected value=\"auto\">Use file extension</option>");
        pw.println("<option value=\"text/plain\">Plain text</option>");
        pw.println("<option value=\"text/html\">HTML text</option>");
        pw.println("<option value=\"image/gif\">GIF image</option>");
        pw.println("<option value=\"image/jpeg\">JPEG image</option>");
        pw.println("<option value=\"audio/wav\">WAV sound</option>");
        pw.println("<option value=\"audio/mpeg\">MP3 music</option>");
        pw.println("<option value=\"video/mpeg\">MPEG video</option>");
        pw.println("<option value=\"application/pdf\">PDF file</option>");
        pw.println("<option value=\"application/postscript\">Postscript document</option>");
        pw.println("<option value=\"application/octet-stream\">Other</option>");
        pw.println("</select></td>");
        pw.println("<td>The MIME type of the file. The default is most ");
        pw.println("probably the best.</td>");
        pw.println("</tr>");

        pw.println("<tr>");
        pw.println("<td colspan=\"2\"><input type=\"submit\" ");
        pw.println("value=\"  Insert  \"></td>");
        pw.println("</tr>");

        pw.println("</table>");

        titleBoxTmp.set("TITLE", "Freenet File Insertion Utility");
        titleBoxTmp.set("CONTENT", sw.toString());
        titleBoxTmp.toHtml(ppw);

        pageTmp.set("TITLE", "Freenet File Insertion Utility");
        pageTmp.set("BODY", psw.toString());
        pageTmp.toHtml(resp.getWriter());

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");
        resp.flushBuffer();
    }

    ////////////////////////////////////////////////////////////
    // Core presentation functions.
    ////////////////////////////////////////////////////////////

    protected void onConfirming(HttpServletRequest req, HttpServletResponse resp, InsertContext context) throws IOException {

        long fileLength = context.data.getBody().size();
        String splitString = context.isSplit ? "yes" : " no ";

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        HtmlTemplate pageTmp = new HtmlTemplate(context.pageTmp);

        // REDFLAG: later, Form to readjust params.

        String warning = "";
        if (context.warningMsg != null) {
            warning = " <h1 class=\"warning\"> WARNING: </h1> " + "<p class=\"warning\">" + context.warningMsg + "</p>";
        }

        pageTmp.set("TITLE", "Confirming insert: " + context.key);
        pageTmp.set("CONTENT", warning + "<h1>Confirming insert: " + context.key + "</h1> " + " length: " + fileLength + " <br /> " + " redundant: "
                + splitString + " <br /> " + " htl: " + context.htl + " <br /> " + " blockHtl: " + context.blockHtl + " <br /> " + " retries: "
                + context.retries + " <br /> " + " threads: " + context.threads + " <br /> " + "<p> " + "<a href=\""
                + HTMLEncoder.encode(URLEncoder.encode(context.insertURL())) + "\">[Start Insert]</a><br />" + "<a href=\""
                + HTMLEncoder.encode(URLEncoder.encode(context.cancelURL())) + "\">[Cancel Insert Request]</a>");

        pageTmp.toHtml(pw);

        sendHtml(resp, HttpServletResponse.SC_OK, sw.toString());
    }

    protected void splitInsertStatus(InsertContext context, PrintWriter pagew) {

        HtmlTemplate titleBoxTmp = new HtmlTemplate(context.titleBoxTmp);

        StringWriter splitsw = new StringWriter();
        PrintWriter splitpw = new PrintWriter(splitsw);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        SegmentHeader h = context.status.segment();

        if (h == null) {
            pw.println("<p>Waiting for Segment Insert to Start...</p>");
            titleBoxTmp.set("TITLE", "Waiting");
            titleBoxTmp.set("CONTENT", sw.toString());
            titleBoxTmp.toHtml(splitpw);
        } else {

            int totalBlocks = h.getBlockCount() + h.getCheckBlockCount();
            double progress = (double) context.status.insertedBytes() / (double) context.status.completeSize();
            int width = 300;

            pw.println("<table border=\"0\"><tr>");
            Dimension size = ImageServlet.getSize(HtmlTemplate.defaultTemplateSet + "/upload.png");
            pw.println("<td valign=\"top\"><img src=\"/servlet/images/" + HtmlTemplate.defaultTemplateSet + "/upload.png\" width=\""
                    + size.getWidth() + "\" height=\"" + size.getHeight() + "\" alt=\"\"></img></td>");
            pw.println("<td width=\"10\">&nbsp;</td>");
            pw.println("<td valign=\"top\">");
            pw.println("<p><b>Key:</b> " + context.key);
            pw.println("<br /><b>Status:</b> ");
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
                barTemplate.toHtml(pw);

            } catch (IOException e) {
                Core.logger.log(this, "Couldn't load template", e, Logger.NORMAL);
            }

            pw.print("<br /><b>Request Started:</b> " + context.dateFormat.format(context.startTime.getTime()));
            pw.println(", <b>Time Elapsed:</b> " + timeDistance(context.startTime, Calendar.getInstance()));
            if ((progress >= 0.1) || ((context.status.blocksProcessed() > 4) && (context.status.insertedBytes() > 0))) {
                long start = context.startTime.getTime().getTime();
                long elapsed = Calendar.getInstance().getTime().getTime() - start;
                long end = start + (long) (elapsed / progress);
                Calendar eta = Calendar.getInstance();
                eta.setTime(new Date(end));
                pw.println("<br /><b>Estimated Finish Time:</b> " + context.dateFormat.format(eta.getTime()));
                long throughput = context.status.insertedBytes() * 1000 / elapsed;
                pw.println("<br /><b>Speed:</b> " + format(throughput) + "/s"); // like
                                                                                // if
                                                                                // they
                                                                                // can
                                                                                // understand
                                                                                // "estimated
                                                                                // throughput"
            }
            pw.println("</p></td></tr></table>");

            titleBoxTmp.set("TITLE", "Insert Status");
            titleBoxTmp.set("CONTENT", sw.toString());
            titleBoxTmp.toHtml(splitpw);
            splitpw.println("<br />");

            sw = new StringWriter();
            pw = new PrintWriter(sw);

            if (context.status.statusCode() == SplitFileStatus.ENCODING) {
                titleBoxTmp.set("TITLE", "FEC Encoding Segment " + (h.getSegmentNum() + 1) + " of " + h.getSegments());
                pw.println("<p>Creating FEC Check Blocks... this may take ");
                pw.println("a while; please be patient.</p>");
            } else {
                int c = 0;
                for (int p = 0, i = 0; i < totalBlocks; i++) {
                    int img;
                    String alt = "[Block " + i + "] ";
                    switch (context.status.insertedBlockStatus(i)) {
                    case SplitFileStatus.RUNNING:
                        img = HTMLTransferProgressIcon.ICONTYPE_PROGRESS;
                        alt += "In Progress... (" + (context.status.insertedBlockRetries(i) + 1) + ". Try)";
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
                        alt += "Success (" + (p = context.status.insertedBlockRetries(i)) + " Retr" + ((p == 1) ? "y" : "ies") + ")";
                        c++;
                        break;
                    case SplitFileStatus.QUEUED:
                    default:
                        img = HTMLTransferProgressIcon.ICONTYPE_WAITING;
                        alt += "Queued";
                    }
                    pw.println(new HTMLTransferProgressIcon(img, alt, alt).render());
                }
                titleBoxTmp.set("TITLE", "Segment " + (h.getSegmentNum() + 1) + " of " + h.getSegments() + ", Upload Queue: " + +totalBlocks
                        + " Blocks, Remaining: " + +(totalBlocks - c) + " Blocks");
            }
            titleBoxTmp.set("CONTENT", sw.toString());
            titleBoxTmp.toHtml(splitpw);
        }

        splitpw.println("<p>[ <a href=\"");
        splitpw.println(HTMLEncoder.encode(URLEncoder.encode(context.statusURL())));
        splitpw.println("\">Update Status</a> ] [ <a href=\"");
        splitpw.println(HTMLEncoder.encode(URLEncoder.encode(context.cancelURL())));
        splitpw.println("\">Cancel Insert Request</a> ]</p>");

        titleBoxTmp.set("TITLE", "Insert Status");
        titleBoxTmp.set("CONTENT", splitsw.toString());
        titleBoxTmp.toHtml(pagew);
    }

    protected void splitStatus(InsertContext context, PrintWriter pagew) {

        boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);

        HtmlTemplate pageTmp = null;
        HtmlTemplate titleBoxTmp = new HtmlTemplate(context.titleBoxTmp);

        StringWriter psw = new StringWriter();
        PrintWriter ppw = new PrintWriter(psw);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        synchronized (context) {
            if (context.isUpdating() && context.refreshInterval > 0) {
                if (logDEBUG) logger.log(this, "Scheduling status page update in " + context.refreshInterval + "s", Logger.DEBUG);

                pageTmp = new HtmlTemplate(context.refreshPageTmp);
                pageTmp.set("REFRESH-TIME", Integer.toString(context.refreshInterval));
                pageTmp.set("REFRESH-URL", HTMLEncoder.encode(URLEncoder.encode(context.statusURL())));
            } else {
                if (logDEBUG) logger.log(this, "Not scheduling status page update", Logger.DEBUG);
                pageTmp = new HtmlTemplate(context.pageTmp);
            }

            switch (context.state) {
            case STATE_STARTING:
                pw.println("<p>Waiting for request to start...</p>");
                pw.println("<p>[ <a href=\"");
                pw.println(HTMLEncoder.encode(URLEncoder.encode(context.cancelURL())));
                pw.println("\">Cancel Insert Request</a> ]</p>");
                titleBoxTmp.set("TITLE", "Insert Starting...");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Insert Status");
                break;
            case STATE_INSERTING:
                if (context.isSplit) {
                    splitInsertStatus(context, ppw);
                } else {
                    // Is there a way to get at least some sort of status?
                    pw.println("<p>Inserting...</p>");
                    pw.println("<p>[ <a href=\"");
                    pw.println(HTMLEncoder.encode(URLEncoder.encode(context.statusURL())));
                    pw.println("\">Update</a> ] [ <a href=\"");
                    pw.println(HTMLEncoder.encode(URLEncoder.encode(context.cancelURL())));
                    pw.println("\">Cancel</a> ]</p>");
                    titleBoxTmp.set("TITLE", "Inserting...");
                    titleBoxTmp.set("CONTENT", sw.toString());
                    titleBoxTmp.toHtml(ppw);
                    pageTmp.set("TITLE", "Insert Request Status");
                }
                break;
            case STATE_CANCELING:
            case STATE_CANCELED:
                pw.println("<p>The Insert Request was cancelled.</p>");
                pw.println("<p>[ <a href=\"" + getGatewayURL());
                pw.println("\">Return to Gateway</a> ]</p>");
                titleBoxTmp.set("TITLE", "Insert Request Cancelled");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Insert Request Cancelled");
                break;
            case STATE_DONE:
                pw.println("<p>The Insert Request finished sucessfully.</p>");
                pw.println("<p><b>Final URI:</b> <code>" + context.finalKey + "</code>");
                pw.print("<br /><b>Request Finished:</b> " + context.dateFormat.format(context.endTime.getTime()));
                pw.println("<br /><b>Time for Completion:</b> " + timeDistance(context.startTime, context.endTime));
                pw.println("</p><p>[ <a href=\"" + getGatewayURL());
                pw.println("\">Return to Gateway</a> ]</p>");
                titleBoxTmp.set("TITLE", "Insert Request Finished");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Insert Request Finished");
                break;
            case STATE_FAILED:
                // REDFLAG: remove!
                if (context.throwable != null) {
                    context.throwable.printStackTrace();
                }
                pw.println("<p>The Insert Request failed.</p>");
                pw.println("<p>Reason: ");
                pw.println(((context.error != null) ? context.error : "") + "</p>");
                pw.println("<p>[ <a href=\"" + getGatewayURL());
                pw.println("\">Return to Gateway</a> ]</p>");
                titleBoxTmp.set("TITLE", "Insert Request Failed");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Insert Request Failed");
                break;
            default:
                pw.println("<p>If you can see this there is a bug in");
                pw.println("InsertServlet.insertStatus().</p>");
                pw.println("<p>[ <a href=\"" + getGatewayURL());
                pw.println("\">Return to Gateway</a> ]</p>");
                titleBoxTmp.set("TITLE", "Bug!");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
                pageTmp.set("TITLE", "Bug!");
            }
        }

        pageTmp.set("BODY", psw.toString());
        pageTmp.toHtml(pagew);
    }

    protected void onStatus(HttpServletRequest req, HttpServletResponse resp, InsertContext context) throws IOException {

        synchronized (context) {
            splitStatus(context, resp.getWriter());
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");
        resp.flushBuffer();
    }

    protected void onInsert(HttpServletRequest req, HttpServletResponse resp, InsertContext context) throws IOException {
        context.startInsert();
        resp.sendRedirect(URLEncoder.encode(context.statusURL()));
    }

    protected String getGatewayURL() {
        return "/";
    }

    protected void onCancel(HttpServletRequest req, HttpServletResponse resp, InsertContext context) throws IOException {

        context.cancel();

        HtmlTemplate pageTmp = new HtmlTemplate(context.pageTmp);
        HtmlTemplate titleBoxTmp = new HtmlTemplate(context.titleBoxTmp);

        StringWriter psw = new StringWriter();
        PrintWriter ppw = new PrintWriter(psw);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.println("<p>The Insert Request for <code>" + context.key);
        pw.println("</code> has been cancelled.</p>");
        pw.println("<p>[ <a href=\"" + getGatewayURL() + "\">Return to Gateway</a> ]</p>");
        titleBoxTmp.set("TITLE", "Insert Request Cancelled");
        titleBoxTmp.set("CONTENT", sw.toString());
        titleBoxTmp.toHtml(ppw);

        pageTmp.set("TITLE", "Insert Request Cancelled");
        pageTmp.set("BODY", psw.toString());
        pageTmp.toHtml(resp.getWriter());

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");
        resp.flushBuffer();
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        String uri = null;

        try {
            uri = URLDecoder.decode(req.getRequestURI());
            logger.log(this, "is uri: " + uri, Logger.DEBUG);
            logger.log(this, "servlet path: " + req.getServletPath(), Logger.DEBUG);
        } catch (URLEncodedFormatException uefe) {
            handleBadURI(req, resp);
            return;
        }

        InsertContext context = (InsertContext) getContextFromURL(uri);

        if (context == null) {
            uri = uri.substring(req.getServletPath().length());
            if (uri.equals("/form")) {
                // Return an example form.
                onDefaultForm(req, resp);
                return;
            }

            // REDFLAG: make error message clearer
            // Only make new contexts in POST
            handleBadContext(req, resp);
            return;
        }

        String cmd = getFirstPathElement(req.getRequestURI());
        if (cmd.equals("confirming")) {
            onConfirming(req, resp, context);
        } else if (cmd.equals("status")) {
            onStatus(req, resp, context);
        } else if (cmd.equals("insert")) {
            onInsert(req, resp, context);
        } else if (cmd.equals("cancel")) {
            onCancel(req, resp, context);
        } else {
            handleUnknownCommand(req, resp, context);
        }
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        if (!Node.httpInserts) {
            logger.log(this, "Somebody tried to insert something even though httpInserts is false. Ignored!", Logger.NORMAL);
            return;
        }

        String uri = null;
        String path = null;

        try {
            uri = URLDecoder.decode(req.getRequestURI());
            logger.log(this, "POST uri: " + uri, Logger.DEBUG);

            path = req.getServletPath();
            if (path == null) {
                // Note: The tricks to get the path right for
                //       redirects are in onNewContext().
                path = "";
            }

            logger.log(this, "servlet path: " + path, Logger.DEBUG);

        } catch (URLEncodedFormatException uefe) {
            handleBadURI(req, resp);
            return;
        }

        InsertContext context = (InsertContext) getContextFromURL(uri);

        if (context == null) {
            if (!hasContextID(uri)) {
                // No context ID.
                // Start a new context.
                String cmd = req.getRequestURI();
                if ((cmd != null) && cmd.startsWith(path)) {
                    // Can't use base getFirstPathElement()
                    // because their is no context id yet.
                    cmd = cmd.substring(path.length());
                    if (cmd.startsWith("/") && cmd.length() > 1) {
                        cmd = cmd.substring(1);
                    }
                }

                if (cmd == null || cmd.length() == 0 || cmd.equals("fproxy_insert") || cmd.startsWith("SSK@") || cmd.startsWith("CHK@")
                        || cmd.startsWith("KSK@")) {
                    cmd = "legacy_start";
                }
                if (cmd.startsWith("start") || cmd.startsWith("legacy_start")) {
                    onNewContext(req, resp, cmd.startsWith("start"));
                } else {
                    // hmmmm... need way to differentiate b/w
                    // GET and POST.
                    handleUnknownCommand(req, resp, null);
                }
                return;
            } else {
                // You can't repost to an existing context.
                onRepost(req, resp, context);
                return;
            }
        } else {
            // You can't repost to an existing context.
            onRepost(req, resp, context);
            return;
        }
    }

    protected void onNewContext(HttpServletRequest req, HttpServletResponse resp, boolean confirm) throws IOException {

        InsertContext newContext = null;
        try {
            String path = URLDecoder.decode(req.getServletPath());
            String warning = null;
            path = req.getServletPath();
            if ((path == null) || path.length() == 0 || path.equals("/")) {
                path = redirectPath;
                logger.log(this, "Used redirectPath hack! I feel dirty.", Logger.ERROR);

                warning = "Obsolete insert URL. Ask the page author to change their form to POST " + " to <p><code>" + redirectPath
                        + "</code><p> instead. <p> Support for the old POST " + " url will eventually be removed and this form will stop working "
                        + " if it isn't fixed. ";
                confirm = true;
            }

            newContext = new InsertContext(defaultLifetimeMs, path);
            if (warning != null) {
                newContext.warningMsg = warning;
            }

            if (!extractPostedData(newContext, req)) {
                onPostFailed(req, resp, newContext);
            } else {
                // REDFLAG: I send the redirect to switch from POSTing
                //          to GETting. Is this really required?
                if (confirm) {
                    // Allow user to adjust parameters before starting.
                    resp.sendRedirect(URLEncoder.encode(newContext.confirmingURL()));
                } else {
                    // Start immediately with default parameters.
                    newContext.startInsert();
                    resp.sendRedirect(URLEncoder.encode(newContext.statusURL()));
                }
                // Prevent cleanup.
                newContext = null;
            }
        } catch (URLEncodedFormatException uefe) {
            handleBadURI(req, resp);
        } finally {
            if (newContext != null) {
                newContext.reap();
            }
        }
    }

    ////////////////////////////////////////////////////////////
    // Helper functions to extract data from
    // multi-part mime POSTs.
    ////////////////////////////////////////////////////////////

    private final MIME_binary[] extractParts(MIME_multipart formData) {
        MIME_binary[] parts = new MIME_binary[6];

        for (int i = 0; i < formData.getPartCount(); i++) {
            // REDFLAG: Remove.
            logger.log(this, i + " content-type: " + formData.getPart(i).getHeader().getContent_Type() + " name: "
                    + formData.getPart(i).getHeader().getContent_DispositionParameter("name"), Logger.DEBUG);

            String name = formData.getPart(i).getHeader().getContent_DispositionParameter("name");

            if (name == null) {
                logger.log(this, " Skipped mime part with no name!", Logger.DEBUG);
                continue;
            }

            if (name.equals("key")) {
                parts[0] = (MIME_binary) formData.getPart(i);
            } else if (name.equals("filename")) {
                parts[1] = (MIME_binary) formData.getPart(i);
            } else if (name.equals("htl")) {
                parts[2] = (MIME_binary) formData.getPart(i);
            } else if (name.equals("content-type")) {
                parts[3] = (MIME_binary) formData.getPart(i);
            } else if (name.equals("threads")) {
                parts[4] = (MIME_binary) formData.getPart(i);
            } else if (name.equals("retries")) {
                parts[5] = (MIME_binary) formData.getPart(i);
            } else {
                freePart(formData.getPart(i));
            }
        }
        return parts;
    }

    private final void freePart(MIME part) {
        if (part == null) { return; }
        try {
            ((MIME_binary) part).freeBody();
        } catch (IOException ioe) {
            logger.log(this, "Ignored exception freeing part: " + ioe, Logger.DEBUG);
        }
    }

    private final void freeParts(MIME_binary[] parts) {
        if (parts == null) { return; }

        for (int i = 0; i < parts.length; i++) {
            freePart(parts[i]);
            parts[i] = null;
        }
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

    // REDFLAG: this is dumb, fix to parse all parameters
    //          out of POST
    //
    // IMPORTANT: Only read basic default parameters out of
    //            multi-part mime post.
    //            Advanced parameters (threading, retries, etc.)
    //            can be tweaked on the confirmation page.
    protected boolean extractPostedData(InsertContext context, HttpServletRequest req) throws IOException {

        // read and validate POST data
        InputStream in = req.getInputStream();
        MIME_binary parts[] = null;

        MIME_multipart formData = null;
        try {
            formData = new MIME_multipart(in, req, bf);
        } catch (MIMEFormatException mfe) {
            throw new IOException(mfe.toString());
        }

        // Frees unknown MIME_binary parts.
        parts = extractParts(formData);

        //         if (formData.getPartCount() < 5) {
        //             context.error = "The form data must have at least 4 parts!";
        //             freeParts(parts);
        //             return false;
        //         }

        // key
        if (parts[0] == null) {
            context.error = "The form must POST a 'key' field!";
            freeParts(parts);
            return false;
        }

        context.key = parts[0].getBodyAsString();

        // file data
        if (parts[1] == null) {
            context.error = "The form must POST a 'filename' field!";
            freeParts(parts);
            return false;
        } else {
            if (parts[1].getBody().size() == 0) {
                context.error = "No data was sent!";
                freeParts(parts);
                return false;
            }
        }
        context.data = parts[1];

        // htl
        if (parts[2] != null) {
            try {
                context.htl = Integer.parseInt(parts[2].getBodyAsString());
                context.blockHtl = context.htl;
            } catch (NumberFormatException nfe) {
                context.error = "Couldn't read an integer out of the 'htl' field!";
                freeParts(parts);
                return false;
            }
        }

        // MIME type
        if (parts[3] != null) {
            context.mimeType = parts[3].getBodyAsString();
        }

        // number of threads
        if (parts[4] != null) {
            try {
                context.threads = Integer.parseInt(parts[4].getBodyAsString());
            } catch (NumberFormatException nfe) {
                context.error = "Couldn't read an integer out of the 'threads' field!";
                freeParts(parts);
                return false;
            }
        }

        // retries
        if (parts[5] != null) {
            try {
                context.retries = Integer.parseInt(parts[5].getBodyAsString());
            } catch (NumberFormatException nfe1) {
                context.error = "Couldn't read an integer out of 'retries' field!";
                freeParts(parts);
                return false;
            }
        }

        // Detect content-type if not specified.
        if (context.mimeType == null || context.mimeType.equalsIgnoreCase("auto")) {
            context.mimeType = parts[1].getHeader().getContent_Type();
            if (context.mimeType != null && context.mimeType.equalsIgnoreCase("application/unspecified")) {
                context.mimeType = null;
            }
        }

        // keep freeParts() from releasing filePart
        parts[1] = null;

        freeParts(parts);
        context.isSplit = context.data.getBody().size() > AutoRequester.MAXNONSPLITSIZE;
        return true;
    }

    private String timeDistance(Calendar start, Calendar end) {
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

    ////////////////////////////////////////////////////////////
    // BaseContext subclass containing code to request
    // the SplitFile from Freenet and information about
    // the request's progress.
    ////////////////////////////////////////////////////////////

    // DESIGN DECISION:
    // Keep presentation in InsertServelet members.
    // Only Servlet stuff in the InsertContext should be
    // related to getting parameters and inserting
    // the data.

    class InsertContext extends BaseContext implements Runnable {

        MIME_binary data;

        volatile boolean isSplit;

        String key;

        String mimeType;

        String path;

        String warningMsg;

        int htl;

        int blockHtl;

        int retries;

        int threads;

        int state = STATE_STARTING;

        int refreshInterval = defaultRefreshIntervalSecs;

        AutoRequester requester;

        SplitFileStatus status;

        Thread insertThread;

        // REDFLAG: do I really need these?
        String finalKey;

        String error;

        Throwable throwable;

        protected HtmlTemplate refreshPageTmp;

        protected HtmlTemplate pageTmp;

        protected HtmlTemplate titleBoxTmp;

        Calendar startTime;

        Calendar endTime;

        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z");

        InsertContext(long lifetime, String p) {
            super(lifetime);
            path = p;

            htl = defaultHtl;
            blockHtl = defaultHtl;

            retries = defaultRetries;
            threads = defaultThreads;

            // Create the requester before the request starts
            // so I can lean on AutoRequester.abort() in
            // the cancel() method.
            // REDFLAG: go back and simplify SFRS too ???
            requester = new AutoRequester(cf);
            status = new SplitFileStatus() {

                // Anonymous Adapter causes the context
                // to get touch()ed every time a SplitFileEvent
                // is received. This keeps the Reaper from
                // releasing it while the request is in progress.
                public void receive(ClientEvent ce) {
                    if (!(ce instanceof SplitFileEvent)) { return; }
                    super.receive(ce);
                    touch();
                }
            };
            requester.addEventListener(status);

            try {
                refreshPageTmp = HtmlTemplate.createTemplate("RefreshPage.html");
                pageTmp = HtmlTemplate.createTemplate("SimplePage.html");
                titleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");
            } catch (IOException ioe1) {
                logger.log(this, "Template Initialization Failed!" + ioe1.getMessage(), Logger.ERROR);
            }

            startTime = Calendar.getInstance();
        }

        void setDefaultValues() {
            // REDFLAG: implement me.
        }

        synchronized void setState(int value) {
            state = value;
            InsertContext.this.notifyAll();
        }

        // Completely releases the context.
        // i.e. so you can't releoad its pages anymore.
        public synchronized boolean reap() {
            cleanup();
            return super.reap();
        }

        // Releases the data bucket
        void cleanup() {
            // REDFLAG: implement
            if (data != null) {
                try {
                    data.freeBody();
                } catch (Exception e) {
                    // NOP
                }
            }
        }

        void cancel() {
            setState(STATE_CANCELING);
            requester.abort();
        }

        void startInsert() {
            synchronized (InsertContext.this) {
                if (state != STATE_STARTING) { throw new IllegalStateException("You can only start an insert request once."); }
            }

            requester.setBlockHtl(blockHtl);
            requester.setSplitFileThreads(threads);
            requester.setSplitFileRetryHtlIncrement(0);
            requester.setSplitFileRetries(retries);
            // Why doesn't the default work?
            requester.setSplitFileAlgoName("OnionFEC_a_1_2");

            // Conservative guesstimate of space taken up by
            // default metadata.
            final int SQWIDGE_FACTOR = 1024;
            // Hmmm... It would be better to do
            // do autoredirection at the PutRequestProcess
            // layer where we already have the real metadata
            // length. But that's really brittle code and I don't
            // have time to retest now. --gj
            if (!key.equals("CHK") && data.getBody().size() + SQWIDGE_FACTOR <= SVK.SVK_MAXSIZE) {
                // Turn off redirects for small files.
                // This makes NIM subissions easier to get,
                // because there is only one key instead of 2 that
                // can fail.
                requester.doRedirect(false);
            }

            // REDFLAG: Remove debugging printlns
            //System.err.println("------------------------------------------------------------");
            //System.err.println("fileLen: " + data.getBody().size());
            //System.err.println("key: " + key);
            //System.err.println("htl: " + htl);
            //System.err.println("mimeType: " + mimeType);
            //System.err.println("blockHtl: " + blockHtl);
            //System.err.println("threads: " + threads);
            //System.err.println("retries: " + retries);
            //System.err.println("------------------------------------------------------------");

            setState(STATE_INSERTING);
            boolean startFailed = true;
            try {
                Thread thread = new Thread(InsertContext.this, "InsertServlet: " + key);
                thread.start();
                startFailed = false;
            } finally {
                if (startFailed) {
                    // This servlet might live in fred's
                    // JVM and fred is not always a good roommate.
                    error = "Couldn't start insert thread!";
                    cleanup();
                    setState(STATE_FAILED);
                }
            }
        }

        public void run() {
            boolean succeeded = false;
            try {
                if (requester.doPut(key, data.getBody(), htl, mimeType)) {
                    finalKey = requester.getKey().toString();
                    succeeded = true;
                }
            } finally {
                synchronized (InsertContext.this) {
                    if (succeeded && (state != STATE_CANCELING)) {
                        endTime = Calendar.getInstance();
                        setState(STATE_DONE);
                    } else {
                        if (state == STATE_CANCELING) {
                            setState(STATE_CANCELED);
                        } else {
                            error = requester.getError();
                            throwable = requester.getThrowable();
                            setState(STATE_FAILED);
                        }
                    }
                    cleanup();
                }
            }
        }

        synchronized String confirmingURL() {
            return path + "/" + makeContextURL(BaseContext.DUMMY_TAG + "/confirming");
        }

        synchronized String insertURL() {
            return path + "/" + makeContextURL(BaseContext.DUMMY_TAG + "/insert");
        }

        synchronized String statusURL() {
            return path + "/" + makeContextURL(BaseContext.DUMMY_TAG + "/status");
        }

        synchronized String cancelURL() {
            return path + "/" + makeContextURL(BaseContext.DUMMY_TAG + "/cancel");
        }

        synchronized boolean isUpdating() {
            return (state == STATE_INSERTING);
        }
    }
}
