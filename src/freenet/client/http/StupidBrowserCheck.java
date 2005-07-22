package freenet.client.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketException;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.support.Logger;
import freenet.support.URLEncodedFormatException;
import freenet.support.servlet.HtmlTemplate;

public class StupidBrowserCheck {

    static boolean dontWarnOperaUsers = false;
    
    private static HtmlTemplate simplePageTmp, titleBoxTmp;
    
    public static void init(boolean b) {
        dontWarnOperaUsers = b;
        try {
            simplePageTmp = HtmlTemplate.createTemplate("SimplePage.html");
            titleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");
        } catch (IOException ioe1) {
            Core.logger.log(StupidBrowserCheck.class, "Template Initialization Failed", Logger.ERROR);
        }

    }
    
    /** Set of IP addresses which were sent a warning about using a bad browser */
    protected static Set badBrowserWarningsSentTo = new HashSet();

    /**
     * @param req
     * @return
     */
    public static boolean didWarning(HttpServletRequest req, HttpServletResponse resp, Logger logger, Object me) throws IOException {
        boolean logDEBUG = logger.shouldLog(Logger.DEBUG, StupidBrowserCheck.class);
        // Check for stupid browsers that don't respect MIME types
        // and thus jeopardize anonymity by by allowing attacker to bypass
        // anonymity filter by inserting HTML as text/plain
        String sUserAgent = req.getHeader("User-Agent");
        if (sUserAgent != null) {
            if (logDEBUG) logger.log(StupidBrowserCheck.class, "Request from User-Agent: " + sUserAgent, Logger.DEBUG);
            String sIPAddress = req.getRemoteAddr();
            if (logDEBUG) logger.log(StupidBrowserCheck.class, "Remote IP address: "+sIPAddress, Logger.DEBUG);
            if (sIPAddress == null) return true; // already closed!
            boolean evilBrowser = false;
            if(sUserAgent.indexOf("Opera 8") >= 0 || sUserAgent.indexOf("Opera 7.5") >= 0) {
                // Opera 8+ is safe even though it pretends to be IE
            } else if(sUserAgent.indexOf("MSIE ") >= 0) {
                if(dontWarnOperaUsers && sUserAgent.indexOf("Opera ")>=0)
                    return false;
                // Not possible to make IE work safely without a lot of work
                evilBrowser = true;
            }
            
            if (evilBrowser) {
                if(logger.shouldLog(Logger.MINOR, me)) logger.log(me, "Bad browser version... sending warning", Logger.MINOR);
                if (!badBrowserWarningsSentTo.contains(sIPAddress)) {
                    try {
                        resp.setContentType("text/html");
                        setNoCache(resp);

                        StringWriter sw = new StringWriter(200);
                        PrintWriter pw = new PrintWriter(sw);
                        pw
                                .println("Freenet has determined that you are using Internet Explorer or Opera. Please be aware that Internet Explorer treats contents in a manner that makes it impossible for us to protect your anonymity while browsing Freenet using this browser. Opera also does this by default but can be configured not to, and thus be safe to use with Freenet - please refer to the <a href=\"/servlet/nodeinfo/documentation/readme\">README</a>. Some browsers that do not do this are Mozilla, K-Meleon, Firefox (from Mozilla.org), Lynx, Links, Amaya, Arena, or a correctly configured Opera.");
                        pw.println("<p>If you are really really sure you want to proceed, don't ");
                        pw.println("say we didn't warn you, and click <a href=\"" + req.getRequestURI()
                                + ((req.getQueryString() != null) ? ("?" + req.getQueryString()) : "") + "\">here</a> to continue.</p>");

                        titleBoxTmp.set("TITLE", "Internet Explorer Allows Sites To Compromize Your Anonymity");
                        titleBoxTmp.set("CONTENT", sw.toString());
                        simplePageTmp.set("TITLE", "Anonymity Compromization Warning");
                        simplePageTmp.set("BODY", titleBoxTmp);

                        PrintWriter pagew = resp.getWriter();
                        simplePageTmp.toHtml(pagew);
                        pagew.flush();

                        badBrowserWarningsSentTo.add(sIPAddress);
                        return true;
                    } catch (SocketException e) {
                        Core.logger.log(me, "SocketException sending warning", Logger.DEBUG);
                    }
                }
            }
        }
        return false;
    }

    // FIXME: should probably be somewhere more obvious!
    static void setNoCache(HttpServletResponse resp) {
        long t = System.currentTimeMillis();
        resp.setDateHeader("Expires", t - 1000);
        resp.setDateHeader("Last-Modified", t);
        resp.setHeader("Cache-control", "max-age=0, must-revalidate, no-cache");
        resp.setHeader("Pragma", "no-cache");
    }


}
