package freenet.node.http.infolets;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.text.NumberFormat;

import javax.servlet.http.HttpServletRequest;

import freenet.Core;
import freenet.Version;
import freenet.client.AutoRequester;
import freenet.client.ClientFactory;
import freenet.client.FreenetURI;
import freenet.client.InternalClient;
import freenet.config.Params;
import freenet.diagnostics.Diagnostics;
import freenet.node.ConfigUpdateListener;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.NodeConfigUpdater;
import freenet.node.http.Infolet;
import freenet.node.http.SimpleAdvanced_ModeUtils;
import freenet.support.Logger;
import freenet.support.NullBucket;
import freenet.support.servlet.HtmlTemplate;

/**
 * This is the Infolet which is displayed by default
 * 
 * @author ian
 */
public class DefaultInfolet extends Infolet implements ConfigUpdateListener {

    private Node node;

    private static boolean logDEBUG;

    static boolean sentFirstTimeWarning = false;

    HtmlTemplate titleBoxTmp,insertTmp;

    private volatile Params bookmarks;

    ClientFactory factory;

    public DefaultInfolet() {
        super();
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
    }

    public DefaultInfolet(String path) {
        this();
        bookmarks = null;
        if (logDEBUG) {
            Core.logger.log(DefaultInfolet.class, "Registering the defaultInfolet as a listener on " + path, Logger.DEBUG);
        }
        NodeConfigUpdater.addUpdateListener(path, this);
        if (bookmarks == null) throw new IllegalStateException("Bookmarks NOT INITIALIZED!");
    }

    public String longName() {
        return "Web Interface";
    }

    public String shortName() {
        return "web";
    }

    public void init(Node n) {
        try {
            //relBarTmp = HtmlTemplate.createTemplate("relbar.tpl");
            //barTmp = HtmlTemplate.createTemplate("bar.tpl");
            titleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");
            insertTmp = HtmlTemplate.createTemplate("fproxyInsert.tpl");
        } catch (java.io.IOException e) {
            ///BAD BOY!!!
        }
        
        node = n;
        factory = new InternalClient(n);
        backgroundFetch();
    }

    // Not used
    public void toHtml(PrintWriter pw) {
    }

	protected static String format(long bytes) {
		NumberFormat nf = NumberFormat.getInstance();
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
	
    public void toHtml(PrintWriter pw, HttpServletRequest req) {
        // Copy local versions for thread safety
        HtmlTemplate titleBoxTmp = new HtmlTemplate(this.titleBoxTmp);
        HtmlTemplate insertTmp = new HtmlTemplate(this.insertTmp);
        // Figure out the prefix for FProxy
        String fproxyAddr = "/servlet/fproxy";
        StringWriter ssw;
        PrintWriter sw;
        // Warning
        if (Node.firstTime && !sentFirstTimeWarning) {
            ssw = new StringWriter(100);
            sw = new PrintWriter(ssw);
            sw.println("Click one of the links below to start browsing Freenet. " + "As this is your first time using Freenet, please be patient "
                    + "as pages can sometimes take several minutes to appear.  You " + "will find that Freenet gets much faster over time as your "
                    + "node learns how to find information more effectively. Using " + "this software may be illegal under some jurisdictions.");
            titleBoxTmp.set("TITLE", "Please be patient");
            titleBoxTmp.set("CONTENT", ssw.toString());
            titleBoxTmp.toHtml(pw);
            sentFirstTimeWarning = true;
        }

        // Warning of invalid IP address
        if (Node.badAddress) {
            ssw = new StringWriter(100);
            sw = new PrintWriter(ssw);
            sw.println("Freenet cannot find a valid internet address. " + "This means that requests to other nodes will get "
                    + "lost sometimes, and your node will be unable to " + "serve requests for other nodes. Your computer may "
                    + "not be online, or for some reason could not be " + "autodetected; the most common reason for this is "
                    + "that you are using a NAT router/firewall. If so, you " + "need to configure it to forward port " + Node.listenPort
                    + " to this machine, and set ipAddress in the config " + "file (" + Main.paramFile + ") to the external internet "
                    + "address of the NAT router. If you don't understand " + "what was just said, don't use a NAT router! Another "
                    + "possibility is that ipAddress is set in your config " + "file to an invalid address.");
            titleBoxTmp.set("TITLE", "Bad IP address setting");
            titleBoxTmp.set("CONTENT", ssw.toString());
            titleBoxTmp.toHtml(pw);
        }

        // Status Bar
        ssw = new StringWriter(100);
        sw = new PrintWriter(ssw);
        sw.println("<TABLE align = 'center'><TR align = 'left'><TD colspan=2>Build " + Version.buildNumber);
        if (node.getHighestSeenBuild() > Version.buildNumber) sw.println(" (<b>Latest: " + Version.highestSeenBuild + "</b>)");
        //sw.println("</TD></TR><TR><TD align='center'>");
        sw.println("&nbsp;&nbsp;Load:");
        //sw.println("</TD><TD align='center'>");
        renderLoadBar(sw);
        if (Node.logOutputBytes) {
			sw.println("</TD></TR><TR><TD align='left' colspan=2>&nbsp;</TD</TR>");
			sw.println("</TD></TR><TR><TD align='left' colspan=2>");
			sw.println("Outbound message overhead:");
			sw.println("</TD></TR><TR><TD align='center' colspan=2>");
			renderMessageOverheadBar(sw);
		}
        sw.println("</TD></TR></TABLE>");
        
        titleBoxTmp.set("TITLE", "Status");
        titleBoxTmp.set("CONTENT", ssw.toString());
        titleBoxTmp.toHtml(pw);

        // Bookmarks
        ssw = new StringWriter(100);
        sw = new PrintWriter(ssw);

        String bookmarkHTML = getBookmarkHTML();
        sw.print(bookmarkHTML);

        sw.println("<p><small><b>Note</b>: These sites and their thumbnail images are published anonymously. We take no responsibility for the contents therein; links are provided as a convenience. If the thumbnail image loads, the site probably will.</small></p>");
        titleBoxTmp.set("TITLE", "Bookmarks");
        titleBoxTmp.set("CONTENT", ssw.toString());
        titleBoxTmp.toHtml(pw);

        // Request Freesite
        ssw = new StringWriter(100);
        sw = new PrintWriter(ssw);
        sw.println("<form action=\"" + fproxyAddr + "\">");
        sw.println("<table><tr><td>Key</td><td align=\"left\">");
        if (Node.firstTime) {
            sw.println("<input size=\"50\" name=\"key\" value=\"Enter a Freesite URI here\">");
        } else {
            sw.println("<input size=\"50\" name=\"key\">");
        }
        sw.println("</td></tr></table>");
        sw.println("<div align=\"right\"><input type=\"submit\" value=\"Request\"></div>");
        sw.println("</form>");
        if (SimpleAdvanced_ModeUtils.isAdvancedMode(req)) {
            titleBoxTmp.set("TITLE", "Request Freesite by URI");
            titleBoxTmp.set("CONTENT", ssw.toString());
            titleBoxTmp.toHtml(pw);

            /*
             * Insert Freesite I have used an external template since the
             * insert HTML is quite complex
             */
            if (Node.httpInserts) {
                ssw = new StringWriter(100);
                sw = new PrintWriter(ssw);
                insertTmp.toHtml(sw);
                titleBoxTmp.set("TITLE", "Insert file by URI");
                titleBoxTmp.set("CONTENT", ssw.toString());
                titleBoxTmp.toHtml(pw);
            }
        }
    }

    private void renderMessageOverheadBar(PrintWriter sw) {
		double trailerChunkBytes = Core.diagnostics.getCountingValue("outputBytesTrailerChunks",Diagnostics.HOUR,Diagnostics.COUNT_CHANGE);
        double totalBytes = Core.diagnostics.getCountingValue("outputBytes",Diagnostics.HOUR,Diagnostics.COUNT_CHANGE);
        double bytesWasted = totalBytes-trailerChunkBytes;
        long overhead = Math.round((bytesWasted/totalBytes)*100);
        HTMLProgressBar overheadBar = new HTMLProgressBar(overhead,100);
        overheadBar.setLowColorThreshold(20,HTMLProgressBar.COLOR_YELLOW);
        overheadBar.setHighColorThreshold(40,HTMLProgressBar.COLOR_RED);
        sw.println(overheadBar.render());
        sw.println(" " + overhead + "% ("+format(Math.round(bytesWasted))+" wasted in the last hour)");
	}

	private void renderLoadBar(PrintWriter sw) {
		int load = (int) (node.estimatedLoad(false) * 100);
        int overloadLow = (int) (Node.overloadLow * 100);
        int overloadHigh = (int) (Node.overloadHigh * 100);
        HTMLProgressBar progress = new HTMLProgressBar(load,100);
        progress.setLowColorThreshold(overloadLow,HTMLProgressBar.COLOR_YELLOW);
        progress.setHighColorThreshold(overloadHigh,HTMLProgressBar.COLOR_RED);
        sw.println(progress.render());
        sw.println(" " + load + "%");
	}

	private String getBookmarkHTML() {
        StringBuffer link = new StringBuffer(512);
        link.append("<table border=\"0\">\n");
        if (bookmarks != null) {

            int maxCount = -1;
            try {
                maxCount = bookmarks.getInt("count");
            } catch (Exception e) {
                maxCount = -1;
            }

            int i = 0;
            while (!((maxCount != -1) && (maxCount <= i))) {

                Params bookmark = (Params) bookmarks.getSet(Integer.toString(i));
                if (logDEBUG) {
                    Core.logger.log(DefaultInfolet.class, "looking for bookmark [" + i + "]: getSet: " + bookmark, Logger.DEBUG);
                }
                if (bookmark == null) {
                    break;
                }

                String key = bookmark.getString("key");
                String title = bookmark.getString("title");
                String activelinkFile = bookmark.getString("activelinkFile");
                String description = bookmark.getString("description");

                String activelink = null;
                if ((activelinkFile != null) && (activelinkFile.trim().length() > 0)) {
                    if (key.endsWith("/"))
                        activelink = key + activelinkFile;
                    else if (key.indexOf('/') > 0) activelink = key.substring(0, key.lastIndexOf('/') + 1) + activelinkFile;
                }

                if (logDEBUG) {
                    Core.logger.log(DefaultInfolet.class, "Found full bookmark [" + i + "]: " + key + "/" + title + "/" + activelink + "/"
                            + description, Logger.DEBUG);
                }

                link.append("<tr><td><a href=\"/").append(key).append("\">\n");
                if (activelink != null)
                        link.append("<img src=\"/").append(activelink).append("\" alt=\"").append(title).append(
                                "\" width=\"95\" height=\"32\" />");
                link.append("</a></td>\n");
                link.append("<td><a href=\"/").append(key).append("\">").append(title).append("</a></td>\n");
                if (description != null) {
                    link.append("<td>").append(description).append("</td>\n");
                }
                link.append("</tr>\n");
                i++;
            }
        }
        link.append("</table>");
        return link.toString();
    }

    private void backgroundFetch() {
        Core.logger.log(this, "Starting background bookmark fetch", Logger.DEBUG);
        if (bookmarks == null) Core.logger.log(DefaultInfolet.class, "Bookmarks null", Logger.DEBUG);
        if (factory == null) Core.logger.log(DefaultInfolet.class, "Factory null", Logger.DEBUG);
        if (bookmarks != null && factory != null) {

            // Start background requests - we do not care whether they succeed
            int maxCount = -1;
            try {
                maxCount = bookmarks.getInt("count");
            } catch (Exception e) {
                maxCount = -1;
            }
            Core.logger.log(DefaultInfolet.class, "Bookmark max count: " + maxCount, Logger.DEBUG);

            int i = 0;
            while (!((maxCount != -1) && (maxCount <= i))) {
                Params bookmark = (Params) bookmarks.getSet(Integer.toString(i));
                Core.logger.log(DefaultInfolet.class, "looking for bookmark [" + i + "]: exists: " + (bookmark != null), Logger.DEBUG);
                if (bookmark == null) {
                    break;
                }

                String key = bookmark.getString("key");
                try {
                    FreenetURI uri = new FreenetURI(key);
                    AutoRequester a = new AutoRequester(factory);
                    a.doRedirect(true);
                    a.doDateRedirect(true);
                    a.setHandleSplitFiles(false);
                    a.doGet(uri, new NullBucket(), Node.maxHopsToLive, true);
                    // Now forget about it
                } catch (MalformedURLException e) {
                    Core.logger.log(this, "Malformed URI in bookmarks: " + key, Logger.NORMAL);
                }
                i++;
            }
        }
        Core.logger.log(this, "Started background bookmark fetch", Logger.DEBUG);
    }

    public void configPropertyUpdated(String path, String val) {
        // ignore... we don't handle any individual values
    }

    public void configPropertyUpdated(String path, Params fs) {
        if (logDEBUG) Core.logger.log(DefaultInfolet.class, "configPropertyUpdated " + "called w/ path [" + path + "]: fs = " + fs, Logger.DEBUG);
        Params newBookmarks = (Params) fs.getSet("bookmarks");
        if (newBookmarks != null) {
            bookmarks = newBookmarks;
            Core.logger.log(DefaultInfolet.class, "Bookmarks updated on request: new bookmarks: " + bookmarks.toString(), Logger.MINOR);
            backgroundFetch();
        } else {
            bookmarks = null;
            Core.logger.log(DefaultInfolet.class, "No bookmarks found!", new Exception("grrr"), Logger.ERROR);
        }
    }

}
