package freenet.node.http;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.TimeZone;
import java.util.Vector;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Address;
import freenet.BadAddressException;
import freenet.Core;
import freenet.Key;
import freenet.Transport;
import freenet.Version;
import freenet.client.AutoBackoffNodeRequester;
import freenet.client.Base64;
import freenet.client.ClientFactory;
import freenet.client.FreenetURI;
import freenet.client.events.RedirectFollowedEvent;
import freenet.client.http.ImageServlet;
import freenet.config.Params;
import freenet.interfaces.AllowedHosts;
import freenet.node.Node;
import freenet.node.rt.RoutingMemory;
import freenet.node.rt.RoutingStore;
import freenet.node.states.maintenance.Checkpoint;
import freenet.support.FileBucket;
import freenet.support.Logger;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.io.WriteOutputStream;
import freenet.support.servlet.HtmlTemplate;
import freenet.support.sort.QuickSorter;
import freenet.support.sort.VectorSorter;
import freenet.transport.tcpAddress;

public class DistributionServlet extends HttpServlet {

    private static final long LIFETIME = 24 * 60 * 60 * 1000;

    private static final long MAXHITS = 100;

    private static final int SEEDREFS = 20;

    private static final Object tablelock = new Object();

    private static Node node;

    private static File distribDir;

    private static File unpackedDir;

    private static String[] freenetResources;

    private static String[] freenetExtResources;

    private static ServletContext context;

    private static ClientFactory factory = null;

    private static ImageServlet imageServlet;

    private boolean advanced = false;

    private static HtmlTemplate basePageTmp, baseTitleBoxTmp;

    private static Object staticSync = new Object();

    private static boolean logDEBUG = true;

    private HtmlTemplate pageTmp, titleBoxTmp;

    private static AllowedHosts generatorAllowedHosts;
    
    public void init() throws ServletException {
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        Core.logger.log(this, "init()", Logger.DEBUG);
        try {
            synchronized (staticSync) {
                if (context == null) {
                    context = getServletContext();
                    if (context != null) {
                        staticInit();
                    }
                }
            }
            instanceInit();
        } catch (IOException e) {
            Core.logger.log(this, "Template initialization failed: " + e, e, Logger.ERROR);
            ServletException ex = new ServletException("Template initialization failed: " + e, e);
            throw ex;
        }
        Core.logger.log(this, "init() finished", Logger.DEBUG);
    }

    private void instanceInit() {
        Core.logger.log(this, "instanceInit()", Logger.DEBUG);
        pageTmp = new HtmlTemplate(basePageTmp);
        titleBoxTmp = new HtmlTemplate(baseTitleBoxTmp);
        Core.logger.log(this, "instanceInit() finished", Logger.DEBUG);
    }

    /**
     * Initialize the various static elements Requires that context is set to a
     * valid ServletContext
     */
    private static void staticInit() throws IOException {
        basePageTmp = HtmlTemplate.createTemplate("SimplePage.html");
        baseTitleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");
        if (logDEBUG) Core.logger.log(DistributionServlet.class, "Initializing Distribution Servlet with " + context, Logger.DEBUG);

        node = (Node) context.getAttribute("freenet.node.Node");
        generatorAllowedHosts = new AllowedHosts(context.getInitParameter("generatorAllowedHosts"));
        getPages();

        imageServlet = new ImageServlet();

        freenetResources = fileList("freenet.files");
        freenetExtResources = fileList("freenet-ext.files");
        String unpacked = context.getInitParameter("unpacked");
        Core.logger.log(DistributionServlet.class, "unpacked: " + unpacked, Logger.DEBUG);
        unpackedDir = new File(unpacked);
        if (!unpackedDir.isDirectory()) unpackedDir = null;
        String ddir = context.getInitParameter("distribDir");
        Core.logger.log(DistributionServlet.class, "distribDir: " + ddir, Logger.DEBUG);
        distribDir = new File(ddir);
        if (!distribDir.exists()) {
            if (!distribDir.mkdir()) {
                distribDir = defaultDistribDir();
            } else { /* ok */
            }
        } else {
            if (!distribDir.isDirectory()) {
                // This could be handled more elegantly - lots of duped
                // code in this whole block
                String s = "WARNING: specified distribDir is not a folder!" + " Using the default";
                Core.logger.log(DistributionServlet.class, s, Logger.NORMAL);
                distribDir = defaultDistribDir();
            } else { /* yay it worked */
            }
        }
        if (Core.logger.shouldLog(Logger.MINOR, DistributionServlet.class))
                Core.logger.log(DistributionServlet.class, "distribDir = " + distribDir, Logger.MINOR);
        initDistFiles();
    }

    protected static File defaultDistribDir() {
        File distribDir = new File(Node.routingDir, "distrib");
        if (!distribDir.exists()) {
            if (!distribDir.mkdir()) {
                String s = "WARNING: no distribDir, and could not create one";
                Core.logger.log(DistributionServlet.class, s, Logger.ERROR);
                distribDir = null;
            } else { /* all systems go */
            }
        } else {
            if (!distribDir.isDirectory()) {
                String s = "WARNING: default distribDir is not a folder!";
                Core.logger.log(DistributionServlet.class, s, Logger.ERROR);
                distribDir = null;
            } else { /* ok */
            }
        }
        return distribDir;
    }

    private static Hashtable getPages() {
        Hashtable rt = getPagesOrNull(context);
        if (rt == null) {
            synchronized (tablelock) {
                // do it again synchronized
                rt = getPagesOrNull(context);
                if (rt == null) {
                    rt = new Hashtable();
                    context.setAttribute("freenet.distribution.pages", rt);
                }
            }
        }
        return rt;
    }

    private static Hashtable getPagesOrNull(ServletContext context) {
        Object o = context.getAttribute("freenet.distribution.pages");
        return (o != null && (o instanceof Hashtable) ? (Hashtable) o : null);
    }

    private static String[] fileList(String res) {
        if (logDEBUG) Core.logger.log(DistributionServlet.class, "Trying to list " + res, Logger.DEBUG);
        try {
            InputStream in = DistributionServlet.class.getResourceAsStream(res);
            if (in == null) {
                if (logDEBUG)
                        Core.logger.log(DistributionServlet.class, "No stream for class for " + res + " - maybe not running from JAR?", Logger.DEBUG);
                return new String[0];
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF8"));

            Vector v = new Vector(1000);
            String s;
            while ((s = br.readLine()) != null) {
                v.addElement(s);
            }
            String[] rs = new String[v.size()];
            v.copyInto(rs);
            if (logDEBUG) Core.logger.log(DistributionServlet.class, "Copied " + v.size() + " files in fileList(" + res + ")", Logger.DEBUG);
            return rs;
        } catch (IOException e) {
            Core.logger.log(DistributionServlet.class, "Could not read filelist: " + res, e, Logger.ERROR);
            return null;
        }
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        try {
            Hashtable pages = getPages();

            long threshTime = System.currentTimeMillis() - LIFETIME;
            Vector remove = new Vector();
            for (Enumeration e = pages.elements(); e.hasMoreElements();) {
                DistributionPage dp = (DistributionPage) e.nextElement();
                if (dp.creationTime < threshTime) {
                    //                    pages.removeElementAt(i);
                    remove.addElement(dp.name);
                }
            }

            for (Enumeration e = remove.elements(); e.hasMoreElements();) {
                pages.remove(e.nextElement());
            }

            String pi = req.getPathInfo();
            String uri = pi == null ? "" : URLDecoder.decode(pi);

            //System.err.println(uri);
            if (logDEBUG) Core.logger.log(this, "URI: " + uri + " IP=" + req.getRemoteAddr(), Logger.DEBUG);
            if (uri.length()==0 || uri.equals("index.html")) {
                if (generatorAllowedHosts.match(req.getRemoteAddr())) {
                    sendIndex(resp, req, pages);
                } else {
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    resp.getWriter().print("Error");
                }
            } else if (uri.equals("disturl.txt")) {
		if (generatorAllowedHosts.match(req.getRemoteAddr())) {
                    sendDistURL(resp, req, pages);
		} else {
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    resp.getWriter().print("Error");
                }
            } else if (generatorAllowedHosts.match(req.getRemoteAddr()) && uri.equals("node.ref")) {
                sendNodeRef(req, resp);
            } else if (generatorAllowedHosts.match(req.getRemoteAddr()) && uri.endsWith("addDistPage.html")) {
                addDistPage(req, resp, pages);
            } else if (uri.endsWith("/") && pages.containsKey(uri.substring(0, uri.length() - 1))) {
                if (logDEBUG) Core.logger.log(this, "Sending Distribution Page to " + req.getRemoteAddr(), Logger.DEBUG);
                sendDistPage((DistributionPage) pages.get(uri.substring(0, uri.length() - 1)), req, resp);
            } else if (uri.endsWith("/freenet.zip") && pages.containsKey(uri.substring(0, uri.length() - "/freenet.zip".length()))) {
                sendDistro((DistributionPage) pages.get(uri.substring(0, uri.length() - 12)), req, resp);
            } else if (uri.indexOf("servlet/images/") == 0 && generatorAllowedHosts.match(req.getRemoteAddr())) {
                sendImage(uri.substring("servlet/images/".length()), req, resp);
            } else if (uri.indexOf("/servlet/images/") != -1 && pages.containsKey(uri.substring(0, uri.indexOf("/servlet/images/")))) {
                sendImage(uri.substring(uri.indexOf("/servlet/images/") + "/servlet/images/".length()), req, resp);
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().print("Error");
                if (logDEBUG) Core.logger.log(this, "Error serving " + uri + " for " + req.getRemoteAddr(), Logger.DEBUG);
            }
        } catch (URLEncodedFormatException e) {
            throw new ServletException(e.toString());
        } catch (RuntimeException e) {
            e.printStackTrace();
        }

    }

    protected boolean isLocalAddress(String s) {
        if (s.equals("127.0.0.1")) return true;
        InetAddress i = freenet.node.Main.getDetectedAddress(0);
        if (i != null && s.equals(i.getHostAddress()))
            return true;
        else
            return false;
    }

    public void sendNodeRef(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        PrintWriter pw = resp.getWriter();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        node.getNodeReference().getFieldSet().writeFields(new WriteOutputStream(buf));
        String ref = new String(buf.toByteArray());
        pw.println(ref);
        pw.flush();

    }

    public void sendIndex(HttpServletResponse resp, HttpServletRequest req, Hashtable pages) throws IOException {

        advanced = SimpleAdvanced_ModeUtils.isAdvancedMode(req);

        PrintWriter rpw = resp.getWriter();
        StringWriter psw = new StringWriter(200);
        PrintWriter ppw = new PrintWriter(psw);
        StringWriter sw = new StringWriter(200);
        PrintWriter pw = new PrintWriter(sw);

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");
        if (advanced)
            pageTmp.set("TITLE", "Node Distribution System");
        else
            pageTmp.set("TITLE", "Help spread Freenet!");
        pageTmp.set("DISTKEY", "");

        if (advanced) {
            titleBoxTmp.set("TITLE", "Node Distribution System");
            titleBoxTmp.set("DISTKEY", "");
            pw.println("<p>This is the Node Distribution System. It allows you ");
            pw.println("to create web pages from which others can download ");
            pw.println("customized freenet installer ZIPs. This is better than ");
            pw.println("downloading freenet from the ");
            pw.println("<a href=\"http://freenetproject.org/\">project server</a>, ");
            pw.println("because it uses locally generated seed nodes, leading ");
            pw.println("to a healthier network. This page cannot be accessed ");
            pw.println("from other computers on the Internet, but the ");
            pw.println("generated pages <b>can</b> (you may need to adjust certain ");
            pw.println("settings if you are behind NAT or firewall), however they contain no ");
            pw.println("offensive or illegal content, unless you consider ");
            pw.println("the Freenet software itself to be offensive or ");
            pw.println("illegal. They are also limited to 24 hours or 100 hits, ");
            pw.println("whichever comes first (a hit is a unique IP downloading ");
            pw.println("the page, or any download of the ZIP file).</p>");
        } else {
            titleBoxTmp.set("TITLE", "Help spread Freenet");
            titleBoxTmp.set("DISTKEY", "");
            pw.println("<p>You can help spread Freenet by letting others download ");
            pw.println("Freenet itself from your computer.  Click on the button below ");
            pw.println("to create a download page, and give the address to your friends.");
            pw.println("It will be up for 24 hours or 100 downloads; after that");
            pw.println("you can create a new one. </p>");
        }
        pw.println("<p><form action=\"./addDistPage.html\" method=\"Get\">" + 
                "<input type=\"Submit\" value=\"Make a distribution page.\""
                + "</form></p>");
        titleBoxTmp.set("CONTENT", sw.toString());
        titleBoxTmp.toHtml(ppw);

        /*
         * sw = new StringWriter(200); pw = new PrintWriter(sw);
         * titleBoxTmp.set("TITLE", "This Node's Reference");
         * ByteArrayOutputStream buf = new ByteArrayOutputStream();
         * node.myRef.getFieldSet().writeFields(new WriteOutputStream(buf));
         * String ref = new String(buf.toByteArray()); pw.println(" <pre> " +
         * ref + " </pre> "); titleBoxTmp.set("CONTENT", sw.toString());
         * titleBoxTmp.toHtml(ppw);
         */

        sw = new StringWriter(200);
        pw = new PrintWriter(sw);
        try {
            String prefix = getPrefixURL(req, new StringBuffer(req.getRequestURI()));
            Enumeration e = pages.elements();
            if (advanced)
                titleBoxTmp.set("TITLE", "Existing Distribution Pages");
            else
                titleBoxTmp.set("TITLE", "Existing Download Pages");
            titleBoxTmp.set("DISTKEY", "");
            if (e.hasMoreElements()) {
                pw.println("<ul>");
                while (e.hasMoreElements()) {
                    DistributionPage dp = (DistributionPage) e.nextElement();
                    pw.println("<li><a href=\"" + prefix + dp.name + "/\">" + prefix + dp.name + "/</a>, created " + new Date(dp.creationTime)
                            + "</li>");
                }
                pw.println("</ul>");
            } else {
                if (advanced)
                    pw.println("<p>No Distribution Pages have yet been created.</p>");
                else
                    pw.println("<p>No Download Pages have yet been created.</p>");
            }
            if (advanced)
                    pw
                            .println("<p><a href=\"/disturl.txt\">Here</a> is a text page that returns the URL of a single Distribution Page for scripting purposes (for example, to automatically append it to your email signature)</p>");
            titleBoxTmp.set("CONTENT", sw.toString());
            titleBoxTmp.set("DISTKEY", "");
            titleBoxTmp.toHtml(ppw);
        } catch (BadAddressException e) {
            titleBoxTmp.set("TITLE", "Error");
            titleBoxTmp.set("DISTKEY", "");
            pw.println("<p>Your Node Address is invalid.</p>");
            titleBoxTmp.set("CONTENT", sw.toString());
            titleBoxTmp.toHtml(ppw);
        }

        pageTmp.set("BODY", psw.toString());
        pageTmp.toHtml(rpw);
        rpw.flush();
    }

    public void sendDistURL(HttpServletResponse resp, HttpServletRequest req, Hashtable pages) throws IOException {

        PrintWriter pw = resp.getWriter();

        String prefix;
        try {
            prefix = getPrefixURL(req, new StringBuffer(req.getRequestURI()));
            if (prefix.endsWith("disturl.txt/")) prefix = prefix.substring(0, prefix.indexOf("disturl.txt/"));
        } catch (BadAddressException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType("text/plain");
            Core.logger.log(this, "BadAddressException trying to getPrefixURL in DistributionServlet - report to devl@freenetproject.org", e,
                    Logger.ERROR);
            pw.println("INTERNAL ERROR, DISTRIBUTION URL NOT AVAILABLE");
            pw.flush();
            return;
        }
        Enumeration e = pages.elements();
        DistributionPage dp = null;
        if (e.hasMoreElements()) {
            dp = (DistributionPage) e.nextElement();
        } else {
            if (initDistFiles()) {
                byte[] bs = new byte[8];
                Core.getRandSource().nextBytes(bs);
                String name = Base64.encode(bs);
                dp = new DistributionPage(name, System.currentTimeMillis());
                pages.put(name, dp);
            }
        }
        if (dp != null) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/plain");
            pw.println(prefix + dp.name + "/\n");
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.setContentType("text/plain");
            pw.println("DISTRIBUTION URL NOT FOUND");
        }
        pw.flush();
    }

    protected String getPrefixURL(HttpServletRequest req, StringBuffer urlPath) throws BadAddressException {
        StringBuffer url = new StringBuffer();
        String scheme = req.getScheme();
        int port = req.getServerPort();
        url.append(scheme); // http, https
        url.append("://");
        Transport tcp = node.transports.get("tcp");
        Address addr;
        addr = node.getNodeReference().getAddress(tcp);
        InetAddress host;
        try {
            host = ((tcpAddress) addr).getHost();
        } catch (java.net.UnknownHostException e) {
            // Impossible
            Core.logger.log(this, "UnknownHostException resolving address from incoming connection!", Logger.ERROR);
            throw new IllegalStateException("UnknownHostException resolving address from incoming connection!");
        }
        String name = host.getHostName();
        url.append(name);
        if ((scheme.equals("http") && port != 80) || (scheme.equals("https") && port != 443)) {
            url.append(':');
            url.append(req.getServerPort());
        }
        url.append(new String(urlPath)); // JDK 1.3 doesn't have
        // StringBuffer.append(StringBuffer)
        // :(
        if (url.charAt(url.length() - 1) != '/') url.append("/");
        return new String(url);
    }

    /* Don't you have C Structs in Java? YUK: */

    public static final int STARTSCRIPT = 0, STOPSCRIPT = 1, README = 2, PRECONFIG = 3, UPDATE = 4, WININSTALL = 5, NODECONFIG = 6, RABBIT = 7,
            FREENETEXTJAR = 8, FREENETJAR = 9;

    public static final String[] names = { "start-freenet.sh", "stop-freenet.sh", "README", "preconfig.sh", "update.sh", "freenet-webinstall.exe",
            "NodeConfig.exe", "freenet.exe", "freenet-ext.jar", "freenet.jar"};

    static final String[] altloc = { "", "", "", "scripts", "scripts", "", "", "", "lib", "lib"};

    public static final String[] keys = { "CHK@qkbggOi7F69x7xOOJkiBItkzxuIMAwI,RWUUCAMlXtMbDHpusbl1zA", // start-freenet.sh
            "CHK@h2CNT10djQ9d6u4BeMPZqsIOAo4KAwI,AqCdUrCb8Laml9l8aDlg9A", // stop-freenet.sh
            "CHK@ix0Z0wFt5jfWFGmbIZaLud~VOIsPAwI,2MBdgGPT57XMzl0AxpSKyA", // README
            "CHK@W35kJctCO55GvexkgIhSysCxgCkKAwI,IQGXJmmv20zPTqU~UiHv5Q", // preconfig.sh
            "CHK@4uh8GbwLebPGeQH3OPyUesaHJBMKAwI,IRYliW4Lkk4gHQvNaVRfAQ", // update.sh
            "CHK@c~fw58SjxQ~5TppuciSXd1OZRcARAwI,~MBTCdzHObqXmf8mUplXFg", // freenet-webinstall.exe
            "CHK@VjDL1olHjKPMT7-sQFA0GpJGz-kRAwI,N5XM9rOqS8c1RAcnZ~EY~g", // NodeConfig.exe
            "CHK@7fWN2TWdHoIODoxCeD1OebVdUB8QAwI,uLq58BoeKATpIJFa8PCiJA", // freenet.exe
            "CHK@A4rw3IQDnVXscfoFdXCtxnkISUgLAwI,xQ-ggU~7eoU7Rwb7rqkVjQ", // freenet-ext.jar
            "" // freenet.jar - sortof
    };

    static final String[] explanation = { "shell script to start freenet node (*nix)", "shell script to stop freenet node (*nix)",
            "documentation - README file", "shell script involved in setup (*nix)", "shell script to update freenet to latest version (*nix)",
            "freenet Windows installer/updater utility", "freenet Windows node configuration utility", "freenet Windows launcher/tray icon utility",
            "freenet support classes. You already have this - somewhere", "freenet core classes. You already have this - somewhere"};

    public static final File[] files = new File[names.length];

    static final DistributionRequest[] reqs = new DistributionRequest[names.length];

    public void addDistPage(HttpServletRequest req, HttpServletResponse resp, Hashtable pages) throws IOException {

        // also IP restricted.
        advanced = SimpleAdvanced_ModeUtils.isAdvancedMode(req);
        if (initDistFiles()) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/html");
            PrintWriter rpw = resp.getWriter();
            StringWriter psw = new StringWriter(200);
            PrintWriter ppw = new PrintWriter(psw);
            StringWriter sw = new StringWriter(200);
            PrintWriter pw = new PrintWriter(sw);
            byte[] bs = new byte[8];
            Core.getRandSource().nextBytes(bs);
            String name = Base64.encode(bs);

            pages.put(name, new DistributionPage(name, System.currentTimeMillis()));

            StringBuffer ruri = new StringBuffer(req.getRequestURI());
            ruri.setLength(ruri.length() - ("addDistPage.html").length() - 1);
            try {
                if (advanced)
                    titleBoxTmp.set("TITLE", "New Distribution Page Added");
                else
                    titleBoxTmp.set("TITLE", "New Download Page Added");
                titleBoxTmp.set("DISTKEY", "");
                if (advanced)
                    pw.println("<p>This URL will contain a freenet distribution for the next 24 hours or 100 hits:</p>");
                else
                    pw.println("<p>Tell your friends they can get Freenet from this URL:</p>");
                String prefix = getPrefixURL(req, ruri);
                ruri.append(name);
                pw.println("<p><b><a href=\"" + prefix + ruri + "/\">" + prefix + ruri + "/</a></p></b>");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            } catch (BadAddressException e) {
                titleBoxTmp.set("TITLE", "Error Creating Distribution Page");
                titleBoxTmp.set("DISTKEY", "");
                pw.println("<p>Your node address is invalid.</p>");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            }

            pageTmp.set("TITLE", "Node Distribution System");
            pageTmp.set("DISTKEY", "");
            pageTmp.set("BODY", psw.toString());
            pageTmp.toHtml(rpw);
            rpw.flush();
        } else {
            sendNoZip(req, resp);
        }
    }

    static protected Object filesLock = new Object();

    protected static boolean initDistFiles() {
        synchronized (filesLock) {
            mainloop: for (int x = 0; x < names.length; x++) {
                if (files[x] != null && files[x].canRead()) continue;
                files[x] = null;
                String name = names[x];
                /*
                 * Search in the following locations: . distribDir unpacked
                 * 
                 * Some files have prefixes that may or may not apply.
                 */
                File s[] = { new File("."), distribDir, unpackedDir};
                for (int y = 0; y < s.length; y++) {
                    File f = new File(s[y], name);
                    if (logDEBUG) Core.logger.log(DistributionServlet.class, "Checking " + f, Logger.DEBUG);
                    if (!f.canRead() && altloc[y].length()!=0) {
                        f = new File(s[y], altloc[y] + File.separator + name);
                        if (logDEBUG) Core.logger.log(DistributionServlet.class, "Checking " + f, Logger.DEBUG);
                    }
                    if (f.canRead()) {
                        files[x] = f;
                        Core.logger.log(DistributionServlet.class, "Already got: " + f, Logger.MINOR);
                        continue mainloop;
                    }
                }
                // Still here, so didn't find the file
                if (keys[x] != null && keys[x].length()!=0) {
                    if (reqs[x] == null) {
                        // Start an AutoRequester for the file
                        if (context == null) {
                            Core.logger.log(DistributionServlet.class, "No servlet context!", Logger.ERROR);
                            continue;
                        }
                        if (factory == null) {
                            factory = (ClientFactory) context.getAttribute("freenet.client.ClientFactory");
                            if (factory == null) {
                                Core.logger.log(DistributionServlet.class, "No ClientFactory!", Logger.ERROR);
                                continue;
                            }
                        }
                        try {
                            try {
                                reqs[x] = new DistributionRequest(x, factory);
                                new Checkpoint(reqs[x]).schedule(node);
                            } catch (MalformedURLException e) {
                                Core.logger.log(DistributionServlet.class, 
                                        "Uh oh... Malformed URI hardcoded into DistributionServlet!",
                                        Logger.ERROR);
                                reqs[x] = null;
                            }
                        } catch (Exception e) {
                            Core.logger.log(DistributionServlet.class, "Exception trying to start request", e, Logger.ERROR);
                            reqs[x] = null;
                            continue;
                        }
                    }
                }
            }
        }

        if (files[FREENETJAR] == null) {
            if (freenetResources != null && freenetResources.length != 0) {
                try {
                    File f = new File(distribDir, names[FREENETJAR]);
                    OutputStream os = new FileOutputStream(f);
                    writeLocalFreenetJar(os);
                    os.flush();
                    if (f.canRead())
                        files[FREENETJAR] = f;
                    else
                        Core.logger.log(DistributionServlet.class, "Can't read freenet.jar we just wrote", Logger.ERROR);
                } catch (IOException e) {
                    Core.logger.log(DistributionServlet.class, "IOException writing freenet.jar", e, Logger.ERROR);
                }
            }
        }
        if (files[FREENETEXTJAR] == null) {
            if (freenetExtResources != null && freenetExtResources.length != 0) {
                try {
                    File f = new File(distribDir, names[FREENETEXTJAR]);
                    OutputStream os = new FileOutputStream(f);
                    writeLocalFreenetExtJar(os);
                    os.flush();
                    if (f.canRead())
                        files[FREENETEXTJAR] = f;
                    else
                        Core.logger.log(DistributionServlet.class, "Can't read freenet-ext.jar we just wrote", Logger.ERROR);
                } catch (IOException e) {
                    Core.logger.log(DistributionServlet.class, "IOException writing freenet-ext.jar", e, Logger.ERROR);
                }
            }
        }
        boolean ok = true;
        for (int x = 0; x < files.length; x++) {
            if (files[x] == null || !files[x].canRead()) {
                ok = false;
                break;
            }
        }
        return ok;
    }

    protected void sendNoZip(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("text/html");
        PrintWriter rpw = resp.getWriter();
        String nodeAddress = "http://" + req.getServerName() + ":" + Node.fproxyPort;
        if (Node.mainportURIOverride != null && Node.mainportURIOverride.length()!=0)
            nodeAddress = Node.mainportURIOverride;
        else
            nodeAddress = "http://" + req.getServerName() + ":" + Node.fproxyPort;
        boolean needScripts = false;
        for (int x = 0; x < WININSTALL; x++) {
            if (files[x] == null) {
                needScripts = true;
                break;
            }
        }
        if (files[FREENETJAR] == null || files[FREENETEXTJAR] == null) needScripts = true;
        boolean needDozeInstaller = (files[WININSTALL] == null);

        StringWriter psw = new StringWriter(200);
        PrintWriter ppw = new PrintWriter(psw);
        StringWriter sw;
        PrintWriter pw;

        sw = new StringWriter(200);
        pw = new PrintWriter(sw);
        pageTmp.set("TITLE", "Freenet Distribution Status");
        pageTmp.set("DISTKEY", "");

        titleBoxTmp.set("TITLE", "You Need More Files");
        titleBoxTmp.set("DISTKEY", "");

        pw.println("The node cannot find files required to produce a Freenet distribution ZIP.");
        pw.println("You can either:<ul>");
        String s = "<li>Download the required files from freenet below";
        if (distribDir != null) {
            s += " into " + distribDir.toString() + "</li>";
        } else if (unpackedDir != null) {
            s += " into " + unpackedDir.toString() + "</li>";
        } else {
            // no distribDir, no unpackedDir
            s += ", create a <b>distribution.params.distribDir</b> (this should " + "be created in your freenet dir by default with your routing "
                    + "files - there is something wrong, maybe permissions?) or " + "<b>distribution.params.unpackedDir</b>, and save it there.</li>";
        }
        pw.println(s);
        if (needScripts) {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd"); // FIXME:
            // hawk's
            // timezone?
            formatter.setTimeZone(TimeZone.getTimeZone("PST"));
            String datestring = formatter.format(new Date());
            String w = "<li>Download the latest freenet release tar.gz from " + "<a href=\"" + nodeAddress
                    + "/__CHECKED_HTTP__sourceforge.net/project/showfiles.php?group_id=978\">sourceforge</a> " + "or the <a href=\"" + nodeAddress
                    + "/__CHECKED_HTTP__freenetproject.org/snapshots/freenet-" + datestring + ".tgz\">latest snapshot .tgz</a> " + "(or <a href=\""
                    + nodeAddress + "/__CHECKED_HTTP__freenetproject.org/snapshots/freenet-"
                    + formatter.format(new Date(System.currentTimeMillis() - 24L * 3600L * 1000L))
                    + ".tgz\">yesterday's snapshot</a>) from <a href=\"" + nodeAddress
                    + "/__CHECKED_HTTP__freenetproject.org/\">FreenetProject.org</a>, untar it ";
            if (distribDir != null)
                w += "and copy the files into " + distribDir.toString();
            else if (unpackedDir != null)
                w += "and copy the files into " + unpackedDir.toString();
            else
                w += "and set <b>distribution.params.distribDir</b> to the directory where you untarred them";
            w += ".</li>";
            pw.println(w);
        }
        if (needDozeInstaller) {
            String w = "<li>Download the Windows webinstaller from <a href=\"" + nodeAddress
                    + "/__CHECKED_HTTP__freenetproject.org/snapshots/freenet-webinstall.exe\">here</a> " + "or <a href=\"" + nodeAddress
                    + "/__CHECKED_HTTP__prdownloads.sourceforge.net/freenet/freenet-webinstall.exe?download\">here</a>";
            if (distribDir != null)
                w += " to " + distribDir.toString();
            else if (unpackedDir != null)
                w += " to " + unpackedDir.toString();
            else
                w += ", create a <b>distribution.params.distribDir</b> (this should be created "
                        + "in your freenet dir by default with your routing files - there is "
                        + "something wrong, maybe permissions?) or <b>distribution.params.unpackedDir</b>, " + "and save it there.</li>";
            pw.println(w);
        }
        if (needDozeInstaller || needScripts || files[FREENETEXTJAR] == null) {
            pw.println("<li>Come back later - the node has started to try to download the ");
            pw.println("missing files from freenet, it will put them in " + distribDir);
            pw.println(" when they have been downloaded.</li>");
        }
        pw.println("</ul>");
        titleBoxTmp.set("CONTENT", sw.toString());
        titleBoxTmp.toHtml(ppw);

        sw = new StringWriter(200);
        pw = new PrintWriter(sw);
        titleBoxTmp.set("TITLE", "Missing Files");
        titleBoxTmp.set("DISTKEY", "");
        for (int x = 0; x < files.length; x++) {
            if (files[x] == null || (!files[x].canRead())) {
                pw.println("<li><b>" + (keys[x].equals("") ? "" : "<a href=\"" + nodeAddress + "/" + keys[x] + "/" + names[x] + "\">") + names[x]
                        + (keys[x].length()==0 ? "" : "</a>") + "</b> - " + explanation[x] + "</li>");
            }
        }
        titleBoxTmp.set("CONTENT", sw.toString());
        titleBoxTmp.toHtml(ppw);

        sw = new StringWriter(200);
        pw = new PrintWriter(sw);
        titleBoxTmp.set("TITLE", "Distribution Directories");
        titleBoxTmp.set("DISTKEY", "");
        pw.println("<p><b>distribution.unpacked</b> currently: " + unpackedDir + "</p>");
        pw.println("<p><b>distribution.distribDir</b> currently: " + distribDir + "</p>");
        titleBoxTmp.set("CONTENT", sw.toString());
        titleBoxTmp.toHtml(ppw);

        pageTmp.set("BODY", psw.toString());
        pageTmp.toHtml(rpw);
    }

    public void sendDistPage(DistributionPage dp, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        //advanced = SimpleAdvanced_ModeUtils.isAdvancedMode(req);
        advanced = false;
        //something is messed up, it gives 503s
        synchronized (dp.h) {
            if (!dp.h.contains(req.getRemoteAddr())) {
                dp.hits++; // FIXME: this is atomic, right?
                dp.h.add(req.getRemoteAddr());
            }
        }
        if (dp.hits >= MAXHITS) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("text/plain");
            PrintWriter pw = resp.getWriter();
            pw.println("Hits on this distribution above limit, ask operator to open another.");
        } else {
            StringWriter psw = new StringWriter(200);
            PrintWriter ppw = new PrintWriter(psw);
            StringWriter sw = new StringWriter(200);
            PrintWriter pw = new PrintWriter(sw);

            PrintWriter rpw = resp.getWriter();
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/html");

            pageTmp.set("TITLE", "Node Distribution System");
            pageTmp.set("DISTKEY", "/" + dp.getName());

            titleBoxTmp.set("TITLE", "Node Distribution System");
            titleBoxTmp.set("DISTKEY", "/" + dp.getName());

            pw.println("<p>This is a distribution of the Freenet node, Version " + Version.getVersionString() + ". It was automatically "
                    + "generated by this Freenet node.</p>");
            if (advanced)
                pw.println("<p>If you marginally trust the owner of this node not " + "to distribute trojans, then this is much preferable "
                        + "to downloading from " + "<a href=\"http://freenetproject.org/\">the central "
                        + "server</a> (from which you can get the original " + "source code), because it leads to a healthier network"
                        + " (not to mention that you are not visible on logs " + "made of freenetproject.org). Do not trust this site "
                        + "unless you have good reason to think that the owner " + "is not distributing trojans. You can refer to this "
                        + "site by its URL, for example in your email " + "signature, but it will expire in " + dp.readableTimeRemaining() + " or "
                        + (MAXHITS - dp.hits) + " hits.</p>");
            else
                pw.println("<p>Download Freenet from here only if you <b>completely</b> trust "
                        + "the person that gave you this address.  Otherwise get it from "
                        + "the official website <a href=\"http://freenetproject.org\">freenetproject.org</a>");

            titleBoxTmp.set("CONTENT", sw.toString());
            titleBoxTmp.toHtml(ppw);

            sw = new StringWriter(200);
            pw = new PrintWriter(sw);
            titleBoxTmp.set("TITLE", "Installation Instructions");
            titleBoxTmp.set("DISTKEY", "/" + dp.getName());
            pw.println("<p>Download the <a href=\"freenet.zip\">" + "Installation Zipfile</a> and unzip. On Windows, run "
                    + "freenet-webinstall.exe, on Unix, read the README or just run " + "run.sh.</p>\n");
            titleBoxTmp.set("CONTENT", sw.toString());
            titleBoxTmp.toHtml(ppw);

            /*
             * sw = new StringWriter(200); pw = new PrintWriter(sw);
             * titleBoxTmp.set("TITLE", "This Node's Reference"); pw.println("
             * <pre> "); ByteArrayOutputStream buf = new
             * ByteArrayOutputStream();
             * node.myRef.getFieldSet().writeFields(new
             * WriteOutputStream(buf)); String ref = buf.toString("UTF8");
             * pw.println(ref); pw.println(" </pre> ");
             * titleBoxTmp.set("CONTENT", sw.toString());
             * titleBoxTmp.toHtml(ppw);
             */

            pageTmp.set("BODY", psw.toString());
            pageTmp.toHtml(rpw);
            rpw.flush();
        }
    }

    static protected Object syncBuffer = new Object();

    public void sendDistro(DistributionPage dp, HttpServletRequest req, HttpServletResponse resp) throws IOException {

        dp.hits++;
        if (dp.hits > MAXHITS) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("text/plain");
            PrintWriter pw = resp.getWriter();
            pw.println("Hits on this distribution above limit, ask operator to open another.");
            return;
        }
        if (logDEBUG) Core.logger.log(this, "Sending distribution ZIP", Logger.DEBUG);
        if (initDistFiles()) {

            if (logDEBUG) Core.logger.log(this, "Initialized dist files", Logger.DEBUG);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/zip");

            try {
                synchronized (syncBuffer) {
                    if (logDEBUG) Core.logger.log(this, "Synchronized up", Logger.DEBUG);
                    // limit memory usage by only allowing one d/l at a time
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                    ZipOutputStream out = new ZipOutputStream(buffer);
                    out.putNextEntry(new ZipEntry("freenet-distribution/" + "seednodes.ref"));
                    writeSeedNodes(out);
                    if (logDEBUG) Core.logger.log(this, "Written seednodes.ref", Logger.DEBUG);
                    for (int x = 0; x < files.length; x++) {
                        out.putNextEntry(new ZipEntry("freenet-distribution/" + names[x]));
                        copyFile(files[x], out);
                        if (logDEBUG) Core.logger.log(this, "Written " + names[x], Logger.DEBUG);
                    }
                    if (logDEBUG) Core.logger.log(this, "Finishing", Logger.DEBUG);
                    out.finish();
                    if (logDEBUG) Core.logger.log(this, "Flushing", Logger.DEBUG);
                    out.flush();
                    if (logDEBUG) Core.logger.log(this, "Flushed, size=" + buffer.size(), Logger.DEBUG);
                    resp.setIntHeader("Content-Length", buffer.size());
                    buffer.writeTo(resp.getOutputStream());
                    if (logDEBUG) Core.logger.log(this, "Written", Logger.DEBUG);
                }
            } finally {
                if (logDEBUG) Core.logger.log(this, "Garbage collecting", Logger.DEBUG);
                System.gc();
                System.runFinalization();
                if (logDEBUG) Core.logger.log(this, "Done garbage collecting", Logger.DEBUG);
            }
        } else {
            if (logDEBUG) Core.logger.log(this, "Sending no zip", Logger.DEBUG);
            sendNoZip(req, resp);
            if (logDEBUG) Core.logger.log(this, "Sent no zip", Logger.DEBUG);
        }
    }

    protected void sendImage(String uri, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (logDEBUG) Core.logger.log(this, "Sending image from " + uri, Logger.DEBUG);
        imageServlet.returnImage(resp, uri);
        resp.flushBuffer();
    }

    private void writeSeedNodes(OutputStream out) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(32768);
        WriteOutputStream wout = new WriteOutputStream(out);

        // start with me
        node.getNodeReference().getFieldSet().writeFields(wout, "End");

        // now grab some from DS
        // DO NOT DO BLOCKING I/O TO THE NETWORK WHILE HOLDING THE ROUTING
        // TABLE LOCK!!!
        // So far, this is only used in making the ZIP; but even
        // ZIPOutputStream is a bit slow, so we buffer it to minimize the
        // amount of time the RT is locked for.
        synchronized (node.rt.semaphore()) {
            RoutingStore rs = node.rt.getRoutingStore();
            int size = node.rt.countNodes();

            Vector selected = new Vector(SEEDREFS);
            Integer s;
            for (int i = 0; i < Math.min(SEEDREFS, size); i++) {
                do {
                    s = new Integer(Core.getRandSource().nextInt(size));
                } while (selected.contains(s));
                selected.add(s);
            }
            QuickSorter.quickSort(new VectorSorter(selected));
            Enumeration memories = rs.elements();
            int j = 0, next;

            for (Enumeration e = selected.elements(); e.hasMoreElements();) {
                next = ((Integer) e.nextElement()).intValue();
                RoutingMemory rm = null;
                RoutingMemory rmo = null;
                while (j <= next) {
                    rmo = rm;
                    rm = (RoutingMemory) memories.nextElement();
                    if (rm == null) {
                        if (rmo == null) {
                            break;
                        } else {
                            freenet.node.NodeReference nr = rmo.getNodeReference();
                            if (nr.noPhysical()) continue;
                            freenet.Transport tcp = node.transports.get("tcp");
                            Address a;
                            try {
                                a = nr.getAddress(tcp); // FIXME
                            } catch (freenet.BadAddressException ex) {
                                continue;
                            }
                            if (!tcp.checkAddress(a.toString())) continue;
                            nr.getFieldSet().writeFields(wout, "End");
                        }
                    }
                    j++;
                }
                if (rm != null) rm.getNodeReference().getFieldSet().writeFields(wout, "End");
            }
        }
        wout.flush();
        out.write(os.toByteArray());
    }

    private static void writeLocalFreenetJar(OutputStream out) throws IOException {

        Manifest mf = new Manifest();
        Attributes att = mf.getMainAttributes();
        att.putValue("Main-class", "freenet.node.Main");

        writeJarStream(freenetResources, mf, out);
    }

    private static void writeLocalFreenetExtJar(OutputStream out) throws IOException {
        writeJarStream(freenetExtResources, new Manifest(), out);
    }

    static void writeJarStream(String[] fileList, Manifest man, OutputStream out) throws IOException {
        JarOutputStream jout = new JarOutputStream(out, man);

        byte[] buf = new byte[Core.blockSize];

        for (int i = 0; i < fileList.length; i++) {

            ZipEntry ze = new ZipEntry(fileList[i].startsWith("/") ? fileList[i].substring(1) : fileList[i]);
            jout.putNextEntry(ze);
            InputStream in = Core.class.getResourceAsStream(fileList[i]);
            if (in == null) {
                Core.logger.log(DistributionServlet.class, "Could not find file " + fileList[i] + " when making distribution.", Logger.ERROR);
                throw new FileNotFoundException();
            }
            int read;
            // copy, note no input buffering since the blocks are large.
            while ((read = in.read(buf)) != -1) {
                jout.write(buf, 0, read);
            }
        }
        jout.finish();
        jout.flush();
    }

    private static void copyFile(File from, OutputStream to) throws IOException {
        byte[] buf = new byte[Core.blockSize];
        InputStream in = new FileInputStream(from);
        int read;
        while ((read = in.read(buf)) != -1) {
            to.write(buf, 0, read);
        }
    }

    private class DistributionPage {

        private String name;

        private long creationTime;

        private int hits = 0;

        HashSet h = new HashSet();

        public DistributionPage(String name, long creationTime) {
            this.name = name;
            this.creationTime = creationTime;
        }

        public String getName() {
            return name;
        }

        public long millisRemaining() {
            return (creationTime + LIFETIME) - System.currentTimeMillis();
        }

        public String readableTimeRemaining() {
            long millis = millisRemaining();
            if (millis < 0) millis = 0;
            if (millis > 3600 * 1000) {
                return Long.toString(millis / (3600 * 1000)) + " hours";
            } else if (millis > 60 * 1000) {
                return Long.toString(millis / (60 * 1000)) + " minutes";
            } else {
                return Long.toString(millis / 1000) + " seconds";
            }
        }

    }

    protected static class DistributionRequest extends AutoBackoffNodeRequester {

        int x;

        File dest;

        File temp;

        public DistributionRequest(int x, ClientFactory f) throws MalformedURLException, IOException {
            super(f, new FreenetURI(keys[x]), false, node.bf.makeBucket(new Key(new FreenetURI(keys[x]).getRoutingKey()).size()), Node.maxHopsToLive);
            this.x = x;
            temp = ((FileBucket) bucket).getFile();
            if (this.logDEBUG) Core.logger.log(this, "Downloading to temp: " + temp, Logger.DEBUG);
            dest = new File(distribDir, names[x]);
            if (dest.equals(temp)) throw new IllegalStateException("tempdir == distribDir!");
        }

        public String getCheckpointName() {
            return super.getCheckpointName() + " as " + names[x] + " for DistributionServlet";
        }

        protected boolean success() {
            if (this.logDEBUG) Core.logger.log(this, "Success for " + getCheckpointName(), Logger.DEBUG);
            File f = null;
            if (dest.exists() || (f = files[x]) != null && f.exists()) {
                if (this.logDEBUG) Core.logger.log(this, dest.toString() + " or " + (f == null ? "(null)" : f.toString()) + " exists", Logger.DEBUG);
                synchronized (filesLock) {
                    if (files[x] == null && dest.exists()) {
                        files[x] = dest;
                    }
                }
                finished = true;
                return true;
            }
            // Somebody else has filled it in

            if (temp.renameTo(dest) || (dest.delete() && temp.renameTo(dest))) {
                if (this.logDEBUG) Core.logger.log(this, "Renamed " + temp + " to " + dest, Logger.DEBUG);
                // Success?
                if (dest.exists() || (files[x] != null && files[x].exists())) {
                    if (Core.logger.shouldLog(Logger.NORMAL, this)) Core.logger.log(this, "Fetched file " + names[x], Logger.NORMAL);
                    synchronized (filesLock) {
                        files[x] = dest;
                    }
                    finished = true;
                    return true;
                } else {
                    String s = "Downloaded " + names[x] + " successfully but couldn't save!";
                    Core.logger.log(this, s, Logger.ERROR);
                    return true; // not our fault, retrying won't help
                }
            } else {
                Core.logger.log(this, "Rename of " + temp + " to " + dest + " failed!", Logger.MINOR);
                if (dest.exists() || (files[x] != null && files[x].exists())) {
                    Core.logger.log(this, "But exists, so OK (" + files[x] + ")", Logger.MINOR);
                    synchronized (filesLock) {
                        if (files[x] == null && dest.exists()) {
                            files[x] = dest;
                        }
                    }
                    finished = true;
                    return true;
                } else {
                    //temp and dest may be on different volumes
                    FileChannel in = null;
                    FileChannel out = null;
                    try {
                        in = new FileInputStream(temp).getChannel();
                        out = new FileOutputStream(dest).getChannel();
                        if (out.transferFrom(in, 0, in.size()) == in.size()) {
                            if (this.logDEBUG) Core.logger.log(this, "Copied " + temp + " to " + dest, Logger.DEBUG);
                            if (Core.logger.shouldLog(Logger.NORMAL, this)) Core.logger.log(this, "Fetched file " + names[x], Logger.NORMAL);
                            synchronized (filesLock) {
                                if (files[x] == null && dest.exists()) {
                                    files[x] = dest;
                                }
                            }
                            finished = true;
                            return true;
                        }
                    } catch (Throwable t) {
                    } finally {
                        try {
                            if (out != null) out.close();
                        } catch (Throwable t) {}
                        try {
                            if (in != null) in.close();
                        } catch (Throwable t) {}
                        if (!temp.delete())
                            Core.logger.log(this, "Delete failed on " + temp, Logger.ERROR);
                    }
                    // Last resort gone
                    String s = "Downloaded file successfully but couldn't " + "save!: " + names[x];
                    Core.logger.log(this, s, Logger.ERROR);
                    finished = true;
                    return true; // not our fault, retrying won't help
                }
            }
        }

        protected boolean failure() {
            Core.logger.log(this, "Failure for " + getCheckpointName(), Logger.MINOR);
            // May as well check :)
            if (dest.exists() || (files[x] != null && files[x].exists()))
                return true;
            else {
                return false;
            }
        }

        protected boolean doRedirect() {
            return true; // for splitfile freenet-ext.jar
        }
            
        protected void redirectFollowed(RedirectFollowedEvent e) {
        }

        protected boolean doSplitFiles() {
            return true; // freenet-ext.jar can be >1MB.
        }

    }

}
