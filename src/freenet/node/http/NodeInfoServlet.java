package freenet.node.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.client.http.StupidBrowserCheck;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.http.infolets.DefaultInfolet;
import freenet.node.http.infolets.DistributionServletInfolet;
import freenet.node.http.infolets.DownloadsInfolet;
import freenet.node.http.infolets.EnvironmentInfolet;
import freenet.node.http.infolets.FailureTableInfolet;
import freenet.node.http.infolets.GeneralInfolet;
import freenet.node.http.infolets.LoadStatsInfolet;
import freenet.node.http.infolets.LoggerInfolet;
import freenet.node.http.infolets.ManualInfolet;
import freenet.node.http.infolets.NodeStatusInfolet;
import freenet.node.http.infolets.OpenConnections;
import freenet.node.http.infolets.ReadMeInfolet;
import freenet.node.http.infolets.TickerContents;
import freenet.support.Logger;
import freenet.support.URLEncodedFormatException;
import freenet.support.servlet.HtmlTemplate;

/**
 * A servlet which aggregates "Infolets", pieces of code which represent some
 * aspect of node status in HTML. It creates a nice menu from which a user may
 * conveniently find the infolet they are looking for.
 * 
 * @author ian
 */
public class NodeInfoServlet extends HttpServlet {

    private static Node node;

    private static Vector groups = new Vector();

    private static Hashtable lookupTable = new Hashtable();

    private static Infolet defaultInfolet = null;

    private static Object staticSync = new Object();

    private static boolean staticInitted = false;

    private HtmlTemplate pageTemplate, titleBoxTemplate;

    private static HtmlTemplate basePageTemplate, baseTitleBoxTemplate;

    private static String path;

    public final void init() {
        try {
            synchronized (staticSync) {
                if (!staticInitted) {
                    staticInitted = true;
                    node = (Node) getServletContext().getAttribute("freenet.node.Node");
                    Core.logger.log(this, "Initializing Infolets", Logger.DEBUG);

                    path = getServletConfig().getInitParameter("servletContextPath");

                    staticInit();
                }
            }
            // Initialize templates
            pageTemplate = new HtmlTemplate(basePageTemplate);
            titleBoxTemplate = new HtmlTemplate(baseTitleBoxTemplate);
        } catch (IOException e) {
            Core.logger.log(this, "Failed to Initialize HtmlTemplate", e, Logger.ERROR);
        }

    }

    protected static void staticInit() throws IOException {
        basePageTemplate = HtmlTemplate.createTemplate("InfoServletTmpl.html");
        baseTitleBoxTemplate = HtmlTemplate.createTemplate("titleBox.tpl");

        /*
         * Construct menu ============== Create the group, register it, and
         * then register the Infolets which are to be part of that group in the
         * order you want to see them in the menu. The long group name should
         * be the name which will appear in the menu, the short group name will
         * be the name that will appear in the URL (so it should basically be
         * nothing but lower-case alphanumeric characters). The same is true
         * for the long and short names of the Infolets themselves. For
         * example: Group testGroup = new Group("Long Group Name",
         * "briefgroupname"); registerGroup(testGroup);
         * registerInfolet(testGroup, new TestInfolet());
         * registerInfolet(testGroup, new TestInfolet2()); ... Group testGroup2 =
         * new Group("Second Long Group Name", "briefgroupname2"); ...
         */
        Group performance = new Group("Performance", "performance");
        registerGroup(performance);
        registerInfolet(performance, new GeneralInfolet());
        Group network = new Group("Networking", "networking");
        registerGroup(network);
        if (!Main.publicNode) {
            registerInfolet(network, new DownloadsInfolet());
            registerInfolet(network, new OpenConnections());
            registerInfolet(network, new DistributionServletInfolet());
        }
        registerInfolet(network, new LoadStatsInfolet());
        Group internal = new Group("Internals", "internal");
        registerGroup(internal);
        registerInfolet(internal, new NodeStatusInfolet()); // placeholder only
        registerInfolet(internal, new LoggerInfolet());
        registerInfolet(internal, new TickerContents());
        registerInfolet(internal, new FailureTableInfolet());
        registerInfolet(internal, new EnvironmentInfolet());

        Group docs = new Group("Documentation", "documentation");
        registerGroup(docs);
        // REDUNDANT registerInfolet(internal, new ThreadFactoryInfolet());
        registerInfolet(docs, new ReadMeInfolet());
        registerInfolet(docs, new ManualInfolet());
        defaultInfolet = new DefaultInfolet(path);
        defaultInfolet.init(node);
    }

    /**
     * Render a Html page
     * 
     * @param req
     * @param resp
     * @exception IOException
     */
    // We synchronize this method because use of the HtmlTemplate renders this
    // code
    // thread-unsafe
    public synchronized void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

      if (StupidBrowserCheck.didWarning(req, resp, Core.logger, this)) return;
        String path = req.getPathInfo();
      Core.logger.log(this, "getPathInfo returned " + path, Logger.DEBUG);

        int x = (path == null) ? -1 : path.indexOf('/');
        int y = (path == null) ? -1 : path.lastIndexOf('/');
        String subset = null;
        if (x != y) {
            subset = path.substring(y + 1);
            path = path.substring(0, y);
            Core.logger.log(this, "Broken: subset=" + subset + ", path=" + path, Logger.DEBUG);
        }

        // Obtain Infolet
        Infolet i = null;
        if (path != null) {
            try {
                i = (Infolet) lookupTable.get(path);
            } catch (ClassCastException e) {
            }
        }
        if (i == null) {
            i = defaultInfolet;
        }

        SimpleAdvanced_ModeUtils.handleRequestedParams(req, resp); //Parse a
                                                                   // possible
                                                                   // requested
                                                                   // modechange
                                                                   // & co

        Core.logger.log(this, "i is " + i, Logger.DEBUG);
        if (subset != null && i instanceof MultipleFileInfolet && ((MultipleFileInfolet) i).write(subset, req, resp)) {
        } else {
            PrintWriter pw = resp.getWriter();
            resp.setContentType("text/html");

            pageTemplate.set("TITLE", i.longName());
            pageTemplate.set("TOPRIGHTLINK", SimpleAdvanced_ModeUtils.renderModeSwitchLink(req.getRequestURI(), req));

            StringWriter swMenu = new StringWriter(100);
            PrintWriter pwMenu = new PrintWriter(swMenu);
            // Display menu
            String servletPath = req.getServletPath();
            if (!servletPath.endsWith("/")) {
                servletPath = servletPath + "/";
            }
            pwMenu.println("<p>");
            pwMenu.println("<a href=\"" + servletPath + "\">Web Interface</a>");
            if (!Main.publicNode) pwMenu.println("<br /><a href=\"/servlet/bookmarkmanager\">" + "Bookmark Manager</a>");
            pwMenu.println("</p>");
            for (Enumeration e = groups.elements(); e.hasMoreElements();) {
                Group g = (Group) e.nextElement();
                g.toHtml(pwMenu, req);
            }
            pwMenu.flush();
            titleBoxTemplate.set("TITLE", "Node Information");
            titleBoxTemplate.set("CONTENT", swMenu.toString());
            pageTemplate.set("MENU", titleBoxTemplate);
            pageTemplate.set("BODY", i, req);
            pageTemplate.toHtml(pw, req);
        }

        try {
            resp.flushBuffer();
        } catch (IOException e) {
            Core.logger.log(this, "I/O error (" + e + ") flushing buffer... probably harmless", e, Logger.MINOR);
        }
    }

    /**
     * Gets the servletInfo attribute of the NodeInfoServlet object
     * 
     * @return The servletInfo value
     */
    public String getServletInfo() {
        return "Information on the nodes current internals.";
    }

    /**
     * Gets the servletName attribute of the NodeInfoServlet object
     * 
     * @return The servletName value
     */
    public String getServletName() {
        return "Node Information";
    }

    private static void registerGroup(Group g) {
        groups.addElement(g);
        lookupTable.put(g.shortName, g);
    }

    private static void registerInfolet(Group g, Infolet i) {
        g.register(i);
        lookupTable.put(g.shortName + "/" + i.shortName(), i);
        i.init(node);
    }

    /**
     * @author ian
     */
    private static class Group {

        public String longName;

        public String shortName;

        private Vector infolets = new Vector();

        public Group(String longName, String shortName) {
            this.longName = longName;
            this.shortName = shortName;
        }

        public final void register(Infolet l) {
            infolets.addElement(l);
        }

        public final void toHtml(PrintWriter pw, HttpServletRequest req) {
            StringWriter swGroup = new StringWriter(100);
            PrintWriter pwGroup = new PrintWriter(swGroup);
            boolean anyPrinted = false;
            pwGroup.println("<h3>" + longName + "</h3>");
            pwGroup.println("<ul>");
            for (Enumeration e = infolets.elements(); e.hasMoreElements();) {
                Infolet l = (Infolet) e.nextElement();
                // this should really check to see if the Node Status Servlet
                // is running,
                // but its not that easy to get a reference to
                // MultipleHttpServletContainer
                // to check whether the Serlvet will be run (it doesn't get run
                // until the first
                // access)
                // anyone who turns nodestatus off in the config will know what
                // they're doing
                if (l.visibleFor(req)) {

                    pwGroup.println("<li><a href=\"" + l.target(req.getServletPath(), shortName) + "\">" + l.longName().replaceAll(" ", "&nbsp;")
                            + "</a></li>");
                    anyPrinted = true;
                }
            }
            pwGroup.println("</ul>");
            if (anyPrinted) {
                pwGroup.flush();
                pw.print(swGroup.getBuffer());
            }
        }
    }
}
