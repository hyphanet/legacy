package freenet.client.http;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.Key;
import freenet.KeyException;
import freenet.client.AbstractClientKey;
import freenet.client.AutoRequester;
import freenet.client.ClientEvent;
import freenet.client.ClientEventListener;
import freenet.client.ClientFactory;
import freenet.client.FreenetURI;
import freenet.client.KeyNotInManifestException;
import freenet.client.RequestSizeException;
import freenet.client.inFlightRequestTrackingAutoRequester;
import freenet.client.events.DataNotFoundEvent;
import freenet.client.events.RedirectFollowedEvent;
import freenet.client.events.RouteNotFoundEvent;
import freenet.client.events.TransferFailedEvent;
import freenet.client.http.filter.ContentFilter;
import freenet.client.http.filter.ContentFilterFactory;
import freenet.client.http.filter.FilterException;
import freenet.client.http.filter.SaferFilter;
import freenet.client.metadata.DateRedirect;
import freenet.client.metadata.MetadataPart;
import freenet.client.metadata.MimeTypeUtils;
import freenet.client.metadata.SplitFile;
import freenet.node.Node;
import freenet.node.http.SimpleAdvanced_ModeUtils;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Fields;
import freenet.support.FileBucket;
import freenet.support.FileLoggerHook;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.LoggerHookChain;
import freenet.support.TempBucketFactory;
import freenet.support.URLEncoder;
import freenet.support.io.DataNotValidIOException;
import freenet.support.servlet.HtmlTemplate;
import freenet.support.servlet.http.HttpServletResponseImpl;

/*
 * This code is part of fproxy, an HTTP proxy server for Freenet. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * Servlet to handle incoming HttpServletRequest's for Freenet keys. This class
 * supersedes HttpHandlerServlet.
 * <p>
 * This servlet recognizes the following parameters:
 * <ul>
 * <li>key <br>
 * overrides URI in GET request
 * <li>htl <br>
 * overrides default htl
 * <li>linkhtl <br>
 * overrides default htl of linked and inlined content
 * <li>mime <br>
 * overrides mime type of requested document.
 * <li>date <br>
 * overrides current time in DBRs
 * <li>rdate <br>
 * make a redirect to that specific edition of the DBR
 * <li>force <br>
 * sets magic cookie used by anonymity filter
 * </ul>
 * </p>
 * 
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong </a> <br>
 * @author <a href="mailto:giannijohansson@mediaone.net">Gianni Johansson </a>
 */
public class FproxyServlet extends HttpServlet {

    /** Description of the Field */
    //protected boolean isLocalConnection = false;
    /** Notify the HTTP client it should not cache the response. */
    protected static boolean noCache = false;

    protected static long initTime = System.currentTimeMillis();

    /** Description of the Field */
    protected Logger logger;

    /** Description of the Field */
    protected static ClientFactory factory;

    /** Description of the Field */
    protected String tmpDir = null;

    /** Run filter flag */
    protected boolean runFilter = true;

    protected boolean filterParanoidStringCheck = false;

    /** MIME types to pass-through unfiltered */
    protected String passThroughMimeTypes = "";

    protected boolean doSendRobots = true;

    /** Default request HTL */
    protected int requestHtl = 15;

    /** ContentFilter */
    private static Random random = new Random();

    private static Hashtable randkeys = new Hashtable();

    private static Object lastForceKey = null;

    private static Object firstForceKey = null;

    private static final int defaultMaxForceKeys = 100;

    /**
     * Number of key overrides Fproxy should track... these are the
     * confirmation pages you get when you go to some file that fproxy doesn't
     * know how to handle
     */
    private static int maxForceKeys = -1;

    // set by init(), but static. Strange things will happen if it is
    // differently set on different instances... FIXME by some sort of static
    // init?

    protected static BucketFactory bucketFactory = null;

    private HtmlTemplate simplePageTmp, refreshPageTmp, titleBoxTmp;

    /**
     * Make a single TempBucketFactory to be shared by all instances. Can't
     * just construct it statically because non-static inner classes are used.
     */
    private void makeOnlyBucketFactory(BucketFactory bf) {
        synchronized (FproxyServlet.class) {
            if (bucketFactory == null) {
                if (bucketFactory == null) {
                    bucketFactory = bf;
                    if (bucketFactory != null) {
                        if (logger != null && logger.shouldLog(Logger.DEBUG, this)) logger.log(this, "Got BucketFactory from context", Logger.DEBUG);
                    } else {
                        bucketFactory = new TempBucketFactory(tmpDir);
                        logger.log(this, "Created TempBucketFactory for " + tmpDir + " - no " + "BucketFactory in context", Logger.MINOR);
                    }
                }
            }
        }
    }

    /** Initialise FproxyServlet */
    public void init() {
        try {
            simplePageTmp = HtmlTemplate.createTemplate("SimplePage.html");
            refreshPageTmp = HtmlTemplate.createTemplate("RefreshPage.html");
            titleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");
        } catch (IOException ioe1) {
            logger.log(this, "Template Initialization Failed", Logger.ERROR); //TODO: This will not work, we doesn't yet have a logger here...
        }

        // System attributes come out of context.
        ServletContext context = getServletContext();
        factory = (ClientFactory) context.getAttribute("freenet.client.ClientFactory");

        Logger parentLog = Node.logger;

        freenet.crypt.RandomSource randSource = (freenet.crypt.RandomSource) context.getAttribute("freenet.crypt.RandomSource");
        random.setSeed(randSource.nextLong());

        String logFile = getInitParameter("logFile");
        //int logLevel = Logger.DEBUG;

        if ((logFile == null) && (parentLog != null)) {
            // Use the logger owned by whatever object
            // created the HttpContainerImpl instance.
            logger = parentLog;
        } else {
            // REDFLAG: support full log parameters?
            // Create our own logger
        	String logLevel = getInitParameter("logLevel");
            if (logLevel == null) {
                logLevel = "DEBUG";
            }

            LoggerHookChain newLogger = new LoggerHookChain(logLevel);
            logger = newLogger;

            FileLoggerHook lh = null;
            if (logFile != null) {
                try {
                    System.err.println("LOGFILE: " + logFile);
                    if (logFile.toLowerCase().equals("no")) {
                        lh = new FileLoggerHook(System.err, null, null, logLevel);

                    } else {
                        lh = new FileLoggerHook(new PrintStream(new FileOutputStream(logFile)), null, null, logLevel);
                    }
                } catch (Exception e) {
                }
            }
            if (lh == null) {
                lh = new FileLoggerHook(System.out, null, null, logLevel);
            }
            lh.start();
            newLogger.addHook(lh);
        }

        runFilter = ParamParse.readBoolean(this, logger, "filter", runFilter);

        filterParanoidStringCheck = ParamParse.readBoolean(this, logger, "filterParanoidStringCheck", filterParanoidStringCheck);

        boolean dontWarnOperaUsers = ParamParse.readBoolean(this, logger, "dontWarnOperaUsers", false);
        StupidBrowserCheck.init(dontWarnOperaUsers);

        if (maxForceKeys == -1) // irreversible
                maxForceKeys = ParamParse.readInt(this, logger, "maxForceKeys", defaultMaxForceKeys, 0, Integer.MAX_VALUE);

        if (getInitParameter("passThroughMimeTypes") != null) {
            passThroughMimeTypes = getInitParameter("passThroughMimeTypes");
        }

        if (passThroughMimeTypes == null || passThroughMimeTypes.length() == 0) {
            passThroughMimeTypes = Node.filterPassThroughMimeTypes;
        }

        if (passThroughMimeTypes == null) throw new NullPointerException();

        requestHtl = ParamParse.readInt(this, logger, "requestHtl", requestHtl, 0, 100);
        // Perturb the default
        requestHtl = Node.perturbHTL(requestHtl);

        noCache = ParamParse.readBoolean(this, logger, "noCache", noCache);

        String s = getInitParameter("doSendRobots");
        if (s != null && !s.equalsIgnoreCase("false") && !s.equalsIgnoreCase("no"))
            doSendRobots = false;
        else
            doSendRobots = true;

        doSendRobots = ParamParse.readBoolean(this, logger, "doSendRobots", doSendRobots);

        BucketFactory tbf = (BucketFactory) context.getAttribute("freenet.support.BucketFactory");
        if (tbf == null) {

            s = getInitParameter("tempDir");
            if (s == null || s.trim().length() == 0) {
                s = null;
                if (Node.tempDir != null) {
                    s = Node.tempDir.toString();
                }
            }
            if (s != null) {
                s = s.trim();
            }
            if (s != null && s.length() != 0) {
                try {
                    FileBucket.setTempDir(s);
                    tmpDir = FileBucket.getTempDir();
                } catch (IllegalArgumentException ia) {
                    logger.log(this, "WARNING: Couldn't set fproxy tempDir: " + getInitParameter("tempDir"), Logger.ERROR);
                }
            } else {
                logger.log(this, "WARNING: fproxy tempDir not set. ", Logger.ERROR);
                logger.log(this, "         Set mainport.params.servlet.1.params.tempDir in freenet.conf/ini.", Logger.ERROR);

                System.err.println("WARNING: fproxy tempDir not set. ");
                System.err.println("         Set mainport.params.servlet.1.params.tempDir in freenet.conf/ini.");
            }
        } // don't need to set tempDir if we already have a BucketFactory

        // this needs to be after tmpDir has been set
        makeOnlyBucketFactory(tbf);

        logger.log(this, "New FproxyServlet created", Logger.MINOR);
        if (logger.shouldLog(Logger.DEBUG, this)) {
            logger.log(this, "   requestHtl = " + requestHtl, Logger.DEBUG);
            logger.log(this, "   filter = " + runFilter, Logger.DEBUG);
            logger.log(this, "   filterParanoidStringCheck = " + filterParanoidStringCheck, Logger.DEBUG);
            logger.log(this, "   passThroughMimeTypes = " + passThroughMimeTypes, Logger.DEBUG);
            logger.log(this, "   logFile = " + logFile, Logger.DEBUG);
            logger.log(this, "   logLevel = " + logger.getThreshold(), Logger.DEBUG);
            logger.log(this, "   tmpDir = " + tmpDir, Logger.DEBUG);
        }
    }

    static HashSet runningRequests = new HashSet();

    // As text, for now
    public static String dumpRunningRequests() {
        StringBuffer sb = new StringBuffer(200);
        int count = 0;
        long maxAge = 0;
        long minAge = Integer.MAX_VALUE;
        synchronized(runningRequests) {
            Iterator i = runningRequests.iterator();
            while(i.hasNext()) {
                count++;
                FproxyRequestTag f = (FproxyRequestTag) i.next();
                sb.append(f.toString());
                sb.append('\t');
                long t = System.currentTimeMillis() - f.startTime;
                if(t > 60 * 60 * 1000) {
                    // Took more than an hour!
                    Core.logger.log(f, "Still running: "+f+" fetching "+f.requestString+
                            " after "+t+"ms", Logger.ERROR);
                }
                sb.append(t);
                maxAge = Math.max(t, maxAge);
                minAge = Math.min(t, minAge);
                sb.append("ms");
                sb.append('\t');
                sb.append(f.requestString);
                sb.append('\n');
            }
        }
        return Integer.toString(count)+" FProxy requests, max age="+maxAge+", min age="+minAge+
        	":\n"+sb.toString();
    }
    
    class FproxyRequestTag {
        long startTime = -1;
        String requestString;
        FproxyRequestTag(String requestString) {
            this.startTime = System.currentTimeMillis();
            this.requestString = requestString;
        }
    }
    
    /**
     * Initiate a Freenet request (GET method)
     * 
     * @param req
     * @param resp
     * @exception IOException
     */
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        // Stuff that needs to get finalized
        Bucket data = null;

        boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);

        FproxyRequestTag frt = null;
        
        String s = null;
        try {
            if (logDEBUG) logger.log(this, "Got GET request", Logger.DEBUG);

            // Support checked jumps out of Freenet.
            // REDFLAG: query parameters not supported? no req.getRequestURL???
            s = req.getRequestURI();
            String q = req.getQueryString();
            if (q != null && q.length() != 0) s += "?" + q;
            frt = new FproxyRequestTag(s);
            synchronized(runningRequests) {
                runningRequests.add(frt);
            }
            if(logDEBUG)
                Core.logger.log(this, "Added to runningRequests: "+frt+": "+s, Logger.DEBUG);
            if (handleCheckedJump(s, resp)) { return; }

            if (StupidBrowserCheck.didWarning(req, resp, logDEBUG, logger, this)) return;

            // Query parameters
            String key = null;
            String queryKey = null;
            String queryForce = null;
            String queryHtl = null;
            String queryLinkHtl = null;
            String queryMime = null;
            String queryDate = null;
            String queryMaxLogSize = null;
            boolean queryRDate = false;
            boolean queryVerbose = false;
            String queryTry = null;
            long queryDateMillis = -1;
            int htlUsed = requestHtl;
            int linkHtl = -1;
            int tryNum = 0;
            int maxLogSize = 0;

            try {

                String rdateAsString = req.getParameter("rdate");
                if (rdateAsString != null && (rdateAsString.equalsIgnoreCase("true") || rdateAsString.equalsIgnoreCase("yes"))) queryRDate = true;

                String verboseAsString = req.getParameter("verbose");
                if (verboseAsString != null && (verboseAsString.equalsIgnoreCase("true") || verboseAsString.equalsIgnoreCase("yes")))
                        queryVerbose = true;

                queryKey = req.getParameter("key");
                if (queryKey != null) {
                    queryKey = freenet.support.URLDecoder.decode(queryKey);
                    // chop leading /
                    if (queryKey != null && queryKey.startsWith("/")) {
                        queryKey = queryKey.substring(1);
                    }
                    if (logDEBUG) logger.log(this, "Read key from query: " + queryKey, Logger.DEBUG);
                }

                if (queryKey == null) {
                    key = freenet.support.URLDecoder.decode(req.getRequestURI());

                    // chop leading /
                    if (key != null && key.startsWith("/")) {
                        key = key.substring(1);
                    }
                    if (logDEBUG) logger.log(this, "Read key from URL: " + key, Logger.DEBUG);
                }

                queryForce = req.getParameter("force");
                if (queryForce != null) {
                    queryForce = freenet.support.URLDecoder.decode(queryForce);
                    if (logDEBUG) logger.log(this, "Read force from query: " + queryForce, Logger.DEBUG);
                }

                queryTry = req.getParameter("try");
                if (queryTry != null) {
                    try {
                        tryNum = Integer.parseInt(freenet.support.URLDecoder.decode(queryTry));
                    } catch (NumberFormatException e) {
                        if (logDEBUG) logger.log(this, "Couldn't parse try number from " + "query, using: " + tryNum, Logger.DEBUG);
                    }
                }

                queryHtl = req.getParameter("htl");
                if (queryHtl != null) {
                    try {
                        htlUsed = Integer.parseInt(freenet.support.URLDecoder.decode(queryHtl));
                        if (logDEBUG) logger.log(this, "Read htl from query: " + htlUsed, Logger.DEBUG);
                    } catch (NumberFormatException e) {
                        if (logDEBUG) logger.log(this, "Couldn't parse htl from query, using: " + htlUsed, Logger.DEBUG);
                    }
                }

                queryLinkHtl = req.getParameter("linkhtl");
                if (queryLinkHtl != null) {
                    try {
                        linkHtl = Integer.parseInt(freenet.support.URLDecoder.decode(queryLinkHtl));
                        if (logDEBUG) logger.log(this, "Read link htl from query: " + linkHtl, Logger.DEBUG);
                    } catch (NumberFormatException e) {
                        if (logDEBUG) logger.log(this, "Couldn't parse linkhtl: " + queryLinkHtl, e, Logger.DEBUG);
                        linkHtl = -1;
                    }
                }

                queryMaxLogSize = req.getParameter("maxlogsize");
                if (queryMaxLogSize != null) {
                    try {
                        maxLogSize = Integer.parseInt(freenet.support.URLDecoder.decode(queryMaxLogSize));
                        if (logDEBUG) logger.log(this, "Read maxLogSize from query: " + maxLogSize, Logger.DEBUG);
                    } catch (NumberFormatException e) {
                        if (logDEBUG) logger.log(this, "Couldn't parse maxLogSize from query, using: " + maxLogSize, Logger.DEBUG);
                    }
                }

                queryMime = req.getParameter("mime");
                if (queryMime != null) {
                    queryMime = freenet.support.URLDecoder.decode(queryMime);
                    if (logDEBUG) logger.log(this, "Read mime from query: " + queryMime, Logger.DEBUG);
                }

                queryDate = req.getParameter("date");
                if (queryDate != null) {
                    queryDate = freenet.support.URLDecoder.decode(queryDate);
                    if (logDEBUG) logger.log(this, "Read date from query: " + queryDate, Logger.DEBUG);
                    queryDateMillis = FproxyServlet.parseDate(queryDate);
                    if (queryDateMillis < 0) {
                        logger.log(this, "Couldn't parse date from query, using -1", Logger.MINOR);
                        queryDateMillis = -1;
                    }
                }
            } catch (Exception e) {
                logger.log(this, "Error while parsing URI", e, Logger.ERROR);
                try {
                    sendError(resp, HttpServletResponse.SC_BAD_REQUEST, "Couldn't parse URI: " + req.getRequestURI());
                } catch (SocketException ex) {
                    logger.log(this, "Socket broke sending error to client: " + ex, Logger.DEBUG);
                }
                return;
            }

            if (queryKey != null) {
                if (logDEBUG) logger.log(this, "Redirecting to: " + queryKey, Logger.DEBUG);

                // Reconstruct the request w/o the key field.
                String encKey = URLEncoder.encode(queryKey);
                queryKey = reconstructRequest(HTMLEncoder.encode(encKey), queryForce, queryHtl, queryMime, queryDate, "");
                // ignore the try #
                try {
                    resp.sendRedirect("/" + queryKey);
                } catch (SocketException e) {
                    logger.log(this, "Socket broke sending redirect: " + e, Logger.DEBUG);
                }
                return;
            }

            // check for special keys
            if (logDEBUG) logger.log(this, "Key is: " + key, Logger.DEBUG);
            if (key.equals("robots.txt") && doSendRobots) {
                sendRobots(resp);
                return;
            }

            if (key.startsWith("freenet:")) {
                key = key.substring("freenet:".length());
            }

            // check for non-freenet keys (e.g. if newbie tries to use the key
            // as a search 'key')
            String upperKey = key.toUpperCase();
            if (!(upperKey.startsWith("KSK@") || upperKey.startsWith("SSK@") || upperKey.startsWith("CHK@") || upperKey.startsWith("SVK@"))
                    && queryForce == null) {
                writeErrorMessage(new BadKeyException(), req, resp, null, key, null, htlUsed, queryDate, queryMime, queryForce, 0);
                return;
            }

            // NOTE: Fproxy isn't responsible for sending the gateway page
            //       anymore. MultipleHttpServletContainer will route ""
            //       and "/" to the appropriate default servlet.
            //
            //       Fail sensibly in the case of misconfiguration. Specifically,
            //       this keeps the "Red Skull" image from being fetched.
            //
            if (key != null && (key.trim().length() == 0)) {
                sendError(resp, HttpServletResponse.SC_BAD_REQUEST, "fproxy doesn't handle \"\" or \"/\". There is probably an error "
                        + "in your freenet.conf/ini. Make sure nothing (including fproxy!) is using " + "port " + req.getServerPort()
                        + ". mainport will run on " + req.getServerPort() + " by default and redirect to fproxy "
                        + "the nodeinfo status page as required.");
            }

            // Do request, following redirects as necessary.
            AutoRequester r = new inFlightRequestTrackingAutoRequester(factory);
            r.setTempDir(tmpDir);
            if (queryDateMillis > 0) {
                r.setDateRedirectTime(queryDateMillis);
            } else {
                r.unsetDateRedirectTime();
            }
            r.setMaxLog2Size(maxLogSize);

            FreenetURI uri;
            try {
                uri = new FreenetURI(key);
            } catch (MalformedURLException e) {
                writeErrorMessage(e, req, resp, null, key, null, htlUsed, null, null, null, tryNum + 1);
                return;
            }

            // REDFLAG: where does data get freed?
            byte[] rk = AbstractClientKey.createFromRequestURI(uri).getKey().getVal();
            if(rk != null)
                data = bucketFactory.makeBucket(new Key(rk).size());
            else {
                Core.logger.log(this, "Could not get routing key bytes from "+uri+" in "+this, Logger.NORMAL);
                data = bucketFactory.makeBucket(0);
            }

            if (logDEBUG) logger.log(this, "Starting request process " + uri + " with htl " + htlUsed, Logger.DEBUG);

            FailureListener listener = new FailureListener(logger);
            r.addEventListener(listener);

            if (!r.doGet(uri, data, Node.perturbHTL(htlUsed))) {
                if (!(queryRDate && listener.dr != null)) {
                    if (logDEBUG) logger.log(this, "Request process returned error for " + uri + " with htl " + htlUsed, Logger.DEBUG);
                    writeErrorMessage(listener.getException(r, uri, req), req, resp, null, key, uri, htlUsed, queryDate, queryMime, queryForce,
                            tryNum + 1);
                    return;
                }
            } else {
                if (logDEBUG) logger.log(this, "Request process returned success for " + uri + " with htl " + htlUsed, Logger.DEBUG);
            }

            if (queryRDate && listener.dr != null) {
                long time = listener.dr.getRequestTime();
                FreenetURI newURI = listener.dr.getTargetForTime(uri, time);
                String redirect = reconstructRequest(newURI.toString(false), queryForce, queryHtl, queryMime, null, null);
                resp.sendRedirect("/" + redirect);
                return;
            }

            // Find content type, in descending order of preference:
            // x 1. specified in query parameters
            // x 2. specified in metadata
            // ? 3. guessed from data a la file(1)
            // x 4. guessed from key
            String mimeType = null;

            /*
             * MIME type may include a charset= parameter. If there is no
             * charset= parameter, the default is ISO-8859-1. If the file
             * begins with FFFE or FEFF, and the charset parameter is not set
             * to UTF16, then it may be interpreted as UTF16 by the browser, or
             * it may not, so we throw in the filter
             */

            // User specified mime type
            if (queryMime != null) {
                mimeType = queryMime;
            }

            // Try to read the mime type out of the metadata
            if ((mimeType == null) && (r.getMetadata() != null)) {
                mimeType = r.getMetadata().getMimeType(null);
            }

            // If that doesn't work guess it from the extension
            // on the key name.
            if (mimeType == null) {
                mimeType = MimeTypeUtils.getExtType(key);
            }

            // If all else fails, fall back to octet-stream
            // so the user can download the file.
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            // Now find the charset
            String charset = "";
            if (mimeType.startsWith("text/")) charset = "ISO-8859-1"; // the
            // default
            int x = mimeType.indexOf(";charset=");
            if (x != -1 && (x + ";charset=".length() < mimeType.length())) {
                charset = mimeType.substring(x + ";charset=".length(), mimeType.length());
                mimeType = mimeType.substring(0, x);
                while (mimeType.length() > 0 && Character.isWhitespace(mimeType.charAt(mimeType.length() - 1))) {
                    mimeType = mimeType.substring(0, mimeType.length() - 1);
                }
            }

            // Determine real mime type again
            String fullMimeType = mimeType;
            if (logDEBUG) logger.log(this, "MIME type: " + mimeType + ", charset: " + charset, Logger.DEBUG);
            if (mimeType.startsWith("text/")) {
                fullMimeType += ";charset=" + charset;
                if (logDEBUG) logger.log(this, "fullMimeType now " + fullMimeType, Logger.DEBUG);
            }

            SplitFile splitFile = r.getMetadata().getSplitFile();
            if (splitFile != null) {
                if (logDEBUG) {
                    logger.log(this, splitFile.toString(), Logger.DEBUG);
                    logger.log(this, "Key is a SplitFile, mimeType: " + fullMimeType, Logger.DEBUG);
                }
            }

            // Filter if nesc. and send data
            // Note: This doesn't check split files.
            boolean forced = false;
            if ((queryForce != null) && checkForceKey(queryForce)) {
                if (logDEBUG) logger.log(this, "Forced", Logger.DEBUG);
                forced = true;
            }

            InputStream in = null;
            OutputStream out = null;
            Bucket filterOutputBucket = null;
            try {
                ContentFilter filter = null;
                // Run this _BEFORE_ sending it to the splitfile servlet
                if (runFilter && !forced) {
                    filter = ContentFilterFactory.newInstance(passThroughMimeTypes, bucketFactory);
                    // newInstance shouldn't be a very heavy operation
                    if (filter instanceof SaferFilter) {
                        SaferFilter f = (SaferFilter) filter;
                        f.setParanoidStringCheck(filterParanoidStringCheck);
                        f.setLinkHtl(linkHtl);
                    }
                    if (!filter.wantFilter(mimeType, charset)) filter = null;
                }

                // Hack to splice new SplitFileRequestServlet
                // code into fproxy
                if (splitFile != null) {
                    // REDFLAG: Need to reconstruct query values in redirect.
                    String encKey = URLEncoder.encode(key);
                    String redirect = "/servlet/SFRequest/" + reconstructRequest(encKey, "", queryHtl, queryMime, queryDate, "");
                    if (forced) {
                        char c = (redirect.indexOf('?') != -1) ? '&' : '?';
                        redirect += c + "runFilter=false";
                    }
                    resp.sendRedirect(redirect);
                    return;
                }

                long size = data.size();

                // Get InputStream.
                if (splitFile != null) throw new IllegalStateException("splitFile not null!");

                // Simple data request.
                if (filter != null) {
                    if (filter instanceof SaferFilter) size = -1;
                    filterOutputBucket = filter.run(data, mimeType, charset);
                    in = filterOutputBucket.getInputStream();
                    if (filterOutputBucket == data) filterOutputBucket = null;
                    // if it's just sent it back to us, don't want to free it
                    // twice
                } else {
                    in = data.getInputStream();
                }

                // Set response headers, 200
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.setContentType(fullMimeType);
                if (logDEBUG)
                        logger.log(this, "Content-Type was " + fullMimeType + " now charEncoding: " + resp.getCharacterEncoding(), Logger.DEBUG);
                if (size != -1) resp.setContentLength((int) data.size());
                // set up for binary output
                // can't do this earlier in case we need resp.getWriter()
                out = resp.getOutputStream();

                if (!noCache) {
                    if (listener.dr == null) {
                        resp.setDateHeader("Last-Modified", initTime);
                        resp.setDateHeader("Expires", initTime + 10L * 365L * 24L * 3600L * 1000L);
                    } else {
                        long curTime = listener.dr.getRequestTime() * 1000;
                        long offset = listener.dr.getOffset() * 1000;
                        long increment = listener.dr.getIncrement() * 1000;
                        curTime -= offset;
                        curTime -= curTime % increment;
                        curTime += offset;
                        resp.setDateHeader("Last-Modified", curTime);
                        resp.setDateHeader("Expires", curTime + increment);
                    }
                } else {
                    StupidBrowserCheck.setNoCache(resp);
                }

                if (logDEBUG) logger.log(this, "Copying stream for " + uri, Logger.DEBUG);
                copy(in, out);
                if (logDEBUG) logger.log(this, "Copied stream for " + uri, Logger.DEBUG);
            } catch (Exception e) {
                //e.printStackTrace();
                int level = Logger.ERROR;
                if (e instanceof SocketException || e instanceof FilterException) level = Logger.DEBUG;
                if (level != Logger.DEBUG || logDEBUG) logger.log(this, "Error sending data to browser for " + uri + ": " + e, e, level);
                if (!resp.isCommitted()) writeErrorMessage(e, req, resp, out, key, uri, htlUsed, queryDate, queryMime, queryForce, tryNum + 1);
            } finally {
                if (in != null) {
                    // Don't leak file handles.
                    try {
                        in.close();
                    } catch (IOException e) {
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                    }
                }
                if (filterOutputBucket != null) bucketFactory.freeBucket(filterOutputBucket);

                // Attemp to commit the response
                try {
                    resp.flushBuffer();
                } catch (IOException e) {
                    // Ignored.
                }
            }
        } catch (Exception e) {
            logger.log(this, "Unexpected Exception in FproxyServlet.doGet -- " + e, e, Logger.ERROR);
            //sendError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            //        "Unexpected Exception in FproxyServlet.doGet -- " + e);
        } finally {
            if(frt != null) {
                synchronized(runningRequests) {
                    runningRequests.remove(frt);
                }
                long t = System.currentTimeMillis() - frt.startTime;
                Core.diagnostics.occurrenceContinuous("fproxyRequestTime", t);
            }
            if(logDEBUG)
                Core.logger.log(this, "Removed from runningRequests: "+this+": "+s, Logger.DEBUG);
            if (data != null) bucketFactory.freeBucket(data);
        }
    }

    /**
     * @param s
     * @return returns date as seconds since the epoch
     */
    public static long parseDate(String s) {
        try {
            return Fields.dateTime(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Gets the prefix attribute of the FproxyServlet object
     * 
     * @param n
     *            number of parameters in the URL
     * @return The prefix value
     */
    private final static String getUrlParamSeparator(int n) {
        return n == 0 ? "?" : "&";
    }

    /**
     * Reconstruct request
     * 
     * @param queryKey
     * @param queryForce
     * @param queryHtl
     * @param queryMime
     * @return
     */
    private String reconstructRequest(String queryKey, String queryForce, String queryHtl, String queryMime, String queryDate, String queryTry) {
        int count = 0;
        StringBuffer query = new StringBuffer(queryKey);
        if (queryForce != null) {
            query.append(FproxyServlet.getUrlParamSeparator(count++)).append("force=").append(queryForce);
        }
        if (queryHtl != null) {
            query.append(FproxyServlet.getUrlParamSeparator(count++)).append("htl=").append(queryHtl);
        }
        if (queryMime != null) {
            query.append(FproxyServlet.getUrlParamSeparator(count++)).append("mime=").append(queryMime);
        }
        if (queryDate != null) {
            query.append(FproxyServlet.getUrlParamSeparator(count++)).append("date=").append(queryDate);
        }
        if (queryTry != null) {
            query.append(FproxyServlet.getUrlParamSeparator(count++)).append("try=").append(queryTry);
        }
        return query.toString();
    }

    /**
     * @param in
     * @param out
     * @exception IOException
     */
    final static void copy(InputStream in, OutputStream out) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(Core.blockSize);
        ReadableByteChannel readChannel = Channels.newChannel(in);
        WritableByteChannel writeChannel = Channels.newChannel(out);
        while (readChannel.read(buffer) != -1) {
            buffer.flip();
            writeChannel.write(buffer);
            buffer.clear();

            // Give the task scheduler some breathing room to improve
            // the reponsiveness of the machine during transfers.\
            Thread.yield();
        }
        writeChannel.close();
        readChannel.close();
    }

    // REDFLAG: suspect error reporting. Get someone who knows more about
    // servlets to look at this.
    // What if an error occurs while you have after you have already starting
    // writing a non html document?
    //
    // This is no worse than the original fproxy implementation, but no better
    // :-(

    /**
     * @param e
     * @param resp
     * @param out
     * @param key
     * @param htl
     */
    protected final void writeErrorMessage(Exception e, HttpServletRequest req, HttpServletResponse resp, OutputStream out, String key,
            FreenetURI rkey, int htl, String date, String mime, String force, int tryNum) {
        try {
            key = URLEncoder.encode(key);
            String encKey = HTMLEncoder.encode(key);
            boolean logDEBUG = logger.shouldLog(Logger.DEBUG, this);
            if (logDEBUG) logger.log(this, "Key before encoding: " + key + " , key after encoding: " + encKey, Logger.DEBUG);

            resp.reset();

            if (e instanceof FilterException) {
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
            resp.setContentType("text/html");

            PrintWriter pagew = null;
            if (out == null) {
                pagew = resp.getWriter();
                // OK, we didn't write any binary data.
            } else {
                // Will this work?
                pagew = new PrintWriter(new OutputStreamWriter(out));
                // REDFLAG: encoding?
            }
            String encMime = (mime != null && mime.length() != 0) ? HTMLEncoder.encode(mime) : null;
            String encDate = (date != null && date.length() != 0) ? HTMLEncoder.encode(date) : null;
            String encForce = (force != null && force.length() != 0) ? HTMLEncoder.encode(force) : null;

            HtmlTemplate pageTmp = null;
            StringWriter psw = new StringWriter(200); // for the complete page
            PrintWriter ppw = new PrintWriter(psw);
            StringWriter sw = new StringWriter(200); // for a single titleBox
            PrintWriter pw = new PrintWriter(sw);

            if (logDEBUG) logger.log(this, "Reporting exception to browser: " + e, e, Logger.DEBUG);
            if (e instanceof FilterException) {
                FilterException fe = (FilterException) e;
                pageTmp = simplePageTmp;
                pageTmp.set("TITLE", "Filter Warning");
                pageTmp.set("SHORTTITLE", "Filter Warning");
                titleBoxTmp.set("TITLE", "Warning: " + e.getMessage());

                pw.print("<p>");
                pw.print(((FilterException) e).explanation);
                pw.println("</p>");

                // Why do we have to encode the key here? It's because this
                // HTML is generated by the gateway, and we cannot trust the
                // URL. It might have characters which will break the security
                // of the page (e.g. close quotes).
                //
                // So we encode it, and pass it as part of the query string.
                // If the user clicks on the "Retrieve anyway", we will get the
                // URL as a form argument, decode it and redirect to it. The
                // redirect is hopefully secure against funny characters...
                //
                String forceKey = makeForceKey();
                String extraDate = "";
                String extraMime = "";
                if (encDate != null && encDate.length() != 0) {
                    extraDate = "&date=" + encDate;
                }
                if (encMime != null && encMime.length() != 0) {
                    extraMime = "&mime=" + encMime;
                }

                // REDFLAG: document /fproxy_forced_confirm
                StringBuffer msg = new StringBuffer("<p><a href=\"/fproxy_forced_confirm?key=");
                msg.append(encKey).append("&force=").append(forceKey).append(extraDate).append(extraMime).append(
                        "\">Retrieve anyway</A>, see the <a href=\"/fproxy_forced_confirm?key=").append(encKey).append(extraDate).append(
                        "&mime=text/plain").append(extraDate).append("\">source</A>").append(", <a href=\"/").append(
                        constructURI(encKey, "application/octet-stream", encDate, forceKey, htl, 0)).append("\">force save to disk</a>").append(
                        ", or <A HREF=\"/\">return</A> to gateway page.</p>");
                pw.println(msg.toString());

                if (fe.analysis != null) {
                    Enumeration errors = fe.analysis.getDisallowedElements();
                    if (errors != null) {
                        titleBoxTmp.set("CONTENT", sw.toString());
                        titleBoxTmp.toHtml(ppw);
                        titleBoxTmp.set("TITLE", "Disallowed Elements");
                        sw = new StringWriter(200);
                        pw = new PrintWriter(sw);
                        while (errors.hasMoreElements()) {
                            pw.print("<li>");
                            pw.println(errors.nextElement());
                        }
                    }
                    Enumeration warnings = fe.analysis.getWarningElements();
                    if (warnings != null) {
                        titleBoxTmp.set("CONTENT", sw.toString());
                        titleBoxTmp.toHtml(ppw);
                        titleBoxTmp.set("TITLE", "External Links");
                        sw = new StringWriter(200);
                        pw = new PrintWriter(sw);
                        while (warnings.hasMoreElements()) {
                            pw.print("<li>");
                            pw.println(warnings.nextElement());
                        }
                    }
                }
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            } else if (e instanceof MalformedURLException) {
                pageTmp = simplePageTmp;
                pageTmp.set("TITLE", "Malformed URI");
                pageTmp.set("SHORTTITLE", "Malformed URI");
                titleBoxTmp.set("TITLE", "Malformed URI");
                pw.print("<p>Unable to retrieve key: <b>");
                pw.print(encKey);
                pw.println("</b>");
                pw.println("<p>The URI was invalid.</p>");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            } else if (e instanceof KeyException || e instanceof DataNotValidIOException) {
                // The latter means the data is not valid at the document level
                pageTmp = simplePageTmp;
                pageTmp.set("TITLE", "Bad Key");
                pageTmp.set("SHORTTITLE", "Bad Key");
                titleBoxTmp.set("TITLE", "Bad Key");
                pw.print("<p>Unable to retrieve key: <b>");
                pw.print(encKey);
                pw.println("</b>");
                if (e instanceof KeyException)
                    pw.println("<p>The key was invalid.</p>");
                else
                    pw.println("<p>The data was broken as inserted.</p>");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            } else if (e instanceof BadKeyException) {
                pageTmp = simplePageTmp;
                pageTmp.set("TITLE", "Unexpected Key");
                pageTmp.set("SHORTTITLE", "Unexpected Key");
                titleBoxTmp.set("TITLE", "Unexpected Key");
                pw.println("<p>The requested Key <b>" + encKey + "</b> doesn't look like a freenet key.</p>");
                pw.println("<p>Freenet Keys begin with <b>KSK@</b>, <b>SSK@</b>, "
                        + "<b>CHK@</b> or <b>SVK@</b>. The most common reason for this message "
                        + "is that you are trying to use the Freenet Gateway " + "key form as a search form. Please "
                        + "visit one of the bookmark links on the gateway " + "and follow links to other freesites from there.</p>");
                pw.println("<p>If you really want to try and retrieve this key, you can ");
                pw.println("<a href=\"/" + encKey + "?force=true\">Retrieve anyway</a>, ");
                pw.println("or <A HREF=\"/\">return</A> to gateway page.</p>");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            } else if (e instanceof KeyNotInManifestException) {
                pageTmp = simplePageTmp;
                pageTmp.set("TITLE", "Key Not Found in Manifest");
                pageTmp.set("SHORTTITLE", "Key Not Found in Manifest");
                pw.println("<p>Couldn't retrieve key: <b>" + encKey + "</b></p>");
                pw.println("<p>The key you are trying to fetch does not exist in the ");
                pw.println("freesite it is supposed to be contained in, which was ");
                pw.println("found. This is a permanent error. You can click " + "<a href=\"/"
                        + HTMLEncoder.encode(rkey.popMetaString().toString(false)) + "\">here</a> to go to the parent site ");
                pw.println("or <a href=\"/\">return</A> to gateway page.</p>");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            } else if (e instanceof RequestSizeException) {
                pageTmp = simplePageTmp;
                pageTmp.set("TITLE", "Key Too Big");
                pageTmp.set("SHORTTITLE", "Key Too Big");
                pw.println("<p>Couldn't retrieve key: <b>" + encKey + "</b></p>");
                pw.println("<p>The key you are trying to fetch is too big.");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            } else if (e instanceof RequestFailedException && (((RequestFailedException) e).getMessage().length() != 0)) {
                int htlNew = htl;
                if (((RequestFailedException) e).isDNF) {
                    htlNew = htlNew + 4;
                    if (htlNew > Node.maxHopsToLive) htlNew = Node.maxHopsToLive;
                }
                pageTmp = refreshPageTmp;

                // Ramp up the automatic refresh but don't refresh too quickly
                // because little would have changed. Don't retry less than
                // once an hour because the user wants the data sometime.
                long refreshTime = Math.min(30 << tryNum, 60 * 60);
                pageTmp.set("REFRESH-TIME", Long.toString(refreshTime));

                pageTmp.set("REFRESH-URL", "/" + constructURI(encKey, encMime, encDate, encForce, htlNew, tryNum));
                if (logDEBUG) logger.log(this, "REFRESH-URL:/" + constructURI(encKey, encMime, encDate, encForce, htlNew, tryNum), Logger.DEBUG);
                titleBoxTmp.set("TITLE", "Network Error");
                if (SimpleAdvanced_ModeUtils.isAdvancedMode(req)) {
                    pw.println("<p>Couldn't retrieve key: <b>" + encKey + "</b>");
                    pw.println("<br />Hops To Live: <b>" + htl + "</b></p>");
                    pageTmp.set("TITLE", ((RequestFailedException) e).summary + ": " + encKey);
                    pageTmp.set("SHORTTITLE", ((RequestFailedException) e).summary);
                } else {
                    pageTmp.set("TITLE", "Couldn't Retrieve Key: " + encKey);
                    pageTmp.set("SHORTTITLE", "Couldn't Retrieve Key");
                }

                // Display extra feedback info.
                pw.println("<p>" + e.getMessage() + "</p>");

                //String encKey = HTMLEncoder.encode(key);
                if (SimpleAdvanced_ModeUtils.isAdvancedMode(req)) {
                    pw.println("<form name=\"changeHTL\" action=\"/" + encKey + "\">");
                    pw.println("<p>Change Hops To Live to <input type=\"text\" " + "size=\"3\" name=\"htl\" value=\"" + htlNew
                            + "\"/> and <input type=\"submit\" value=\"Retry\"/>");
                } else {
                    pw.println("Retrying...");
                }
                if (encDate != null && encDate.length() != 0) {
                    pw.println("<input type=\"hidden\" name=\"date\" value=\"" + encDate + "\">");
                } // FIXME: anonymity consequences?
                if (encMime != null && encMime.length() != 0) {
                    pw.println("<input type=\"hidden\" name=\"mime\" value=\"" + encMime + "\">");
                }
                if (encForce != null && encForce.length() != 0) {
                    pw.println("<input type=\"hidden\" name=\"force\" value=\"" + encForce + "\">");
                }
                pw.println("</form>");
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            } else {
                // Don't know what the error is so don't autorefresh
                pageTmp = simplePageTmp;
                if (SimpleAdvanced_ModeUtils.isAdvancedMode(req)) {
                    pageTmp.set("TITLE", "Unknown Network Error: " + e);
                } else {
                    pageTmp.set("TITLE", "Unknown Network Error");
                }
                pageTmp.set("SHORTTITLE", "Unknown Network Error");
                titleBoxTmp.set("TITLE", "Unknown Network Error");
                pw.println("<p>Couldn't retrieve key: <b>" + encKey + "</b>");
                pw.println("<br />Hops To Live: <b>" + htl + "</b></p>");
                //String encKey = HTMLEncoder.encode(key);
                pw.println("<p>Please report the following to devl@freenetproject.org:</b>");
                pw.println("<p><pre>" + e.toString() + "\n");
                e.printStackTrace(pw);
                pw.println("</pre></p>");
                pw.println("<form name=\"changeHTL\" action=\"/" + encKey + "\"> <p>Change Hops To Live to <input type=\"text\" "
                        + "size=\"3\" name=\"htl\" value=\"" + htl + "\"/> and <input type=\"submit\" value=\"Retry\"/>");
                if (encDate != null && encDate.length() != 0) {
                    pw.println("<input type=\"hidden\" name=\"date\" value=\"" + encDate + "\">");
                }
                // FIXME: anonymity consequences?
                if (encMime != null && encMime.length() != 0) {
                    pw.println("<input type=\"hidden\" name=\"mime\" value=\"" + encMime + "\">");
                }
                if (encForce != null && encForce.length() != 0) {
                    pw.println("<input type=\"hidden\" name=\"force\" value=\"" + encForce + "\">");
                }
                titleBoxTmp.set("CONTENT", sw.toString());
                titleBoxTmp.toHtml(ppw);
            }
            pageTmp.set("BODY", psw.toString());
            pageTmp.toHtml(pagew);
            pagew.flush();
        } catch (Exception e1) {
            logger.log(this, "Couldn't report error to browser: " + e1, e1, Logger.ERROR);

        }
    }

    /**
     * Construct key URI for automated retries. The constructed URI does not
     * contain a slash at the beginning.
     * 
     * @param encKey
     *            Requested Key
     * @param encMime
     *            MIME Type of Key
     * @param encDate
     *            Date
     * @param htl
     *            Hops To Live
     * @param tryNum
     *            Number of Retry
     */
    private String constructURI(String encKey, String encMime, String encDate, String encForce, int htl, int tryNum) {
        return reconstructRequest(encKey, encForce, Integer.toString(htl), encMime, encDate, Integer.toString(tryNum));
    }

    /**
     * Send back robots.txt
     * 
     * @param resp
     *            Description of the Parameter
     * @exception IOException
     *                Description of the Exception
     */
    protected void sendRobots(HttpServletResponse resp) throws IOException {
        if (logger.shouldLog(Logger.DEBUG, this)) logger.log(this, "Sending robots.txt", Logger.DEBUG);
        OutputStream out = resp.getOutputStream();

        //     if (!isLocalConnection) {
        // disallow all robots
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        byte[] buf = "Disallow: *".getBytes();
        resp.setContentLength(buf.length);
        out.write(buf);
        //     } else {
        //       // no robots.txt (i.e. full access)
        //       resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        //       resp.setContentType("text/plain");
        //       byte[] buf = "Okay, little robot, give me your best
        // shot!".getBytes();
        //       resp.setContentLength(buf.length);
        //       out.write(buf);
        //     }

        // commit response
        resp.flushBuffer();
    }

    /**
     * Send back error status
     * 
     * @param resp
     *            Description of the Parameter
     * @param status
     *            Description of the Parameter
     * @param detailMessage
     *            Description of the Parameter
     * @exception IOException
     *                Description of the Exception
     */
    protected void sendError(HttpServletResponse resp, int status, String detailMessage) throws IOException {

        // get status string
        String statusString = status + " " + HttpServletResponseImpl.getNameForStatus(status);

        // show it
        if (Core.logger.shouldLog(Logger.DEBUG, this)) Core.logger.log(this, "Sending HTTP error: " + statusString, Logger.DEBUG);
        resp.setStatus(status);
        resp.setContentType("text/html");
        HtmlTemplate template=new HtmlTemplate(this.simplePageTmp);
        template.set("TITLE", statusString);
        template.set("BODY", detailMessage);
        template.toHtml(resp.getWriter());
        resp.flushBuffer();
    }

    ////////////////////////////////////////////////////////////
    // Support checked jumps out of Freenet.

    /**
     * Description of the Method
     * 
     * @param url
     *            Description of the Parameter
     * @param resp
     *            Description of the Parameter
     * @return Description of the Return Value
     * @exception IOException
     *                Description of the Exception
     */
    protected final boolean handleCheckedJump(String url, HttpServletResponse resp) throws IOException {

        String decodedURL = getCheckedJumpURL(url);
        if (decodedURL == null) { return false; }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html");

        PrintWriter pagew = resp.getWriter();
        StringWriter sw = new StringWriter(200);
        PrintWriter pw = new PrintWriter(sw);

        titleBoxTmp.set("TITLE", "Warning: External Link Encountered");
        pw.println("<p>Browsing external links <b>is not</b> anonymous.</p>");
        pw.println("<p>Click on the link below to continue or hit the ");
        pw.println("back button on your browser to abort.</p>");
        pw.println("<p><a href=\"" + decodedURL + "\">" + decodedURL + "</a>");
        titleBoxTmp.set("CONTENT", sw.toString());

        simplePageTmp.set("TITLE", "External Link");
        simplePageTmp.set("BODY", titleBoxTmp);
        simplePageTmp.toHtml(pagew);
        pagew.flush();
        return true;
    }

    /** Description of the Field */
    protected final static String MSG_BADURL = "Couldn't decode checked jump url.";

    protected final static String[] ESCAPED_PROTOCOLS = { "/__CHECKED_HTTP__", "/__CHECKED_FTP__", "/__CHECKED_HTTPS__", "/__CHECKED_NNTP__",
            "/__CHECKED_NEWS__", "/__CHECKED_ABOUT__"};

    protected final static String[] UNESCAPED_PROTOCOLS = { "http://", "ftp://", "https://", "nntp://", "news:", "about:"};

    /**
     * Gets the checkedJumpURL attribute of the FproxyServlet class
     * 
     * @param url
     *            Description of the Parameter
     * @return The checkedJumpURL value
     */
    protected final static String getCheckedJumpURL(String url) {
        String ret = null;

        for (int x = 0; x < ESCAPED_PROTOCOLS.length; x++) {
            String escaped = ESCAPED_PROTOCOLS[x];
            int p = url.indexOf(escaped);
            if (p != -1) {
                if (ret != null) throw new IllegalArgumentException(MSG_BADURL);
                if (url.length() - p < escaped.length() + 1) { throw new IllegalArgumentException(MSG_BADURL); }
                ret = UNESCAPED_PROTOCOLS[x] + url.substring(escaped.length() + p);
            }
        }

        return ret;
    }

    /**
     * Description of the Method
     * 
     * @param key
     *            Description of the Parameter
     * @return Description of the Return Value
     */
    private static synchronized boolean checkForceKey(Object key) {
        return randkeys.get(key) != null;
    }

    /**
     * Description of the Method
     * 
     * @return Description of the Return Value
     */
    private static synchronized String makeForceKey() {
        synchronized (randkeys) {
            String forceKey = null;
            do {
                forceKey = Integer.toHexString(random.nextInt());
            } while (randkeys.containsKey(forceKey));

            randkeys.put(forceKey, forceKey);

            if (lastForceKey != null) {
                randkeys.remove(lastForceKey);
                randkeys.put(lastForceKey, forceKey);
            } else {
                firstForceKey = forceKey;
            }

            lastForceKey = forceKey;
            if (randkeys.size() > maxForceKeys) {
                Object newForceKey = randkeys.get(firstForceKey);
                randkeys.remove(firstForceKey);
                firstForceKey = newForceKey;
            }
            return forceKey;
        }
    }

    /**
     * Listen for RNF and DNFs so we can make informative HTTP 404 Not Found
     * messages.
     * 
     * @author ian
     */
    static class FailureListener implements ClientEventListener {

        private Logger logger;

        /**
         * Constructor, needed only to set logger
         */
        FailureListener(Logger logger) {
            this.logger = logger;
        }

        /**
         * Description of the Method
         * 
         * @param ce
         *            Description of the Parameter
         */
        public void receive(ClientEvent ce) {
            if (ce instanceof RouteNotFoundEvent) {
                rnf = (RouteNotFoundEvent) ce;
            } else if (ce instanceof DataNotFoundEvent) {
                dnf = (DataNotFoundEvent) ce;
            } else if (ce instanceof TransferFailedEvent) {
                tf = (TransferFailedEvent) ce;
            } else if (ce instanceof RedirectFollowedEvent) {
                RedirectFollowedEvent rfe = (RedirectFollowedEvent) ce;
                //logger.log(this, "METADATA: " + rfe.getMetadata(),
                // Logger.NORMAL);

                MetadataPart mp = rfe.getMetadataPart();
                //logger.log(this, "REDIRECT: " + mp.toString(),
                // Logger.DEBUG);

                if (mp instanceof DateRedirect) {
                    // store DateRedirect for use in message generation if the
                    // request fails
                    dr = (DateRedirect) mp;
                    //logger.log(this, "DATEREDIRECT: " + dr, Logger.DEBUG);
                }
            }
        }

        /**
         * Gets the rNF attribute of the FailureListener object
         * 
         * @return The rNF value
         */
        public final RouteNotFoundEvent getRNF() {
            return rnf;
        }

        /**
         * Gets the dNF attribute of the FailureListener object
         * 
         * @return The dNF value
         */
        public final DataNotFoundEvent getDNF() {
            return dnf;
        }

        public final TransferFailedEvent getTF() {
            return tf;
        }
        
        /**
         * Gets the exception attribute of the FailureListener object
         * 
         * @return The exception value
         */
        public final Exception getException(AutoRequester r, FreenetURI key, HttpServletRequest req) {
            String warning = "";
            String summary = "";
            if (rnf != null) {
                int total = rnf.getUnreachable() + rnf.getRestarted() + rnf.getRejected();

                if (total == rnf.getUnreachable()) {
                    if (SimpleAdvanced_ModeUtils.isAdvancedMode(req)) {
                        warning = "<p class=\"warning\">" + "The request couldn't even make it off of your node. "
                                + "Try again, perhaps with <a href=\"" + "/CHK@hdXaxkwZ9rA8-SidT0AN-bniQlgPAwI,"
                                + "XdCDmBuGsd-ulqbLnZ8v~w/GPL.txt\">the GPL</a> to " + "help your node learn about others. The publicly"
                                + " available seed nodes have been <b>very</b> busy " + "lately.  If possible try to get a friend to give "
                                + "you a reference to their node instead.</p>";
                    } else {
                        warning = "<p class=\"warning\">" + "Couldn't connect to the network. "
                                + "Are you sure you have configured Freenet correctly?" + " Also make sure that you are connected to the "
                                + "internet.</p>";
                    }
                }
                String msg;
                summary = "Route Not Found";
                if (SimpleAdvanced_ModeUtils.isAdvancedMode(req)) {
                    msg = "Error: <b>Route Not Found</b>" + "<p>Attempts were made to contact " + total + " nodes." + "<ul>" + "<li>"
                            + rnf.getUnreachable() + " were totally unreachable.</li>" + "<li>" + rnf.getRestarted() + " restarted.</li>" + "<li>"
                            + rnf.getRejected() + " cleanly rejected.</li><li>" + rnf.getBackedOff() + " backed off.</ul>" + warning
                            + "<p>Route Not Found messages mean that your node, or " + "the rest of the network, didn't find the data or "
                            + "enough nodes to send the request to. You should retry," + " with the same Hops-To-Live; if it persists, there may"
                            + " be a problem (check that your internet connection is" + " working). Try reseeding your node, and if that "
                            + "doesn't work, contact support@freenetproject.org.";
                } else {
                    if (warning.length() == 0)
                        msg = "<p>The network is busy, please try again later.</p>";
                    else
                        msg = warning;
                }
                return new RequestFailedException(msg, summary, false);
            } else if (dnf != null) {
                String dbr = "";
                if (dr != null) {
                    long time = dr.getRequestTime() - dr.getIncrement();
                    String prevDbrUrl = "/" + dr.getTarget().toString(false) + "//?date=" + Fields.secToDateTime(time);

                    String altDbrUrl = "/" + dr.getTargetForTime(key, time).toString(false);
                    if (SimpleAdvanced_ModeUtils.isAdvancedMode(req)) {
                        dbr = "</p><p>" + "The request followed a Date Based Redirect, " + "this is usually used to provide an updateable "
                                + "freesite. It appears that the current freesite " + "is not available. You could try retrieving " + "an <A HREF=\""
                                + prevDbrUrl + "\">earlier dated version</A> (<a href=\"" + altDbrUrl + "\">better but date-specific link</a>). "
                                + "The site updates every " + dr.getIncrement() / (60 * 60) + " hours.</p><hr>";
                    } else
                        dbr = "</p><p>This site updates every day, " + "you could try <A HREF=\"" + prevDbrUrl + "\"> yesterday's edition</A>.</p>";
                    // Also make sure that your computers clock is correctly
                    // set.
                }

                String msg;
                if (SimpleAdvanced_ModeUtils.isAdvancedMode(req)) {
                    msg = "Error: <b>Data Not Found</b>" + "<p>Data Not Found messages mean that your request "
                            + "passed through Hops-To-Live nodes without finding " + "the data. It may simply not be there, but you can "
                            + "try again, possibly with a higher Hops-To-Live " + "(which will make freenet try more nodes before giving " + "up)."
                            + dbr;
                } else
                    msg = "<p><b>Data not found</b> (Freenet could not find the data)" + dbr;
                summary = "Data Not Found";
                return new RequestFailedException(msg, summary, true);
            } else if (tf != null) {
                String msg = "Error: <b>Transfer Failed</b><p>Transfer Failed messages mean that your request almost succeeded,"
                    + " but failed while actually transferring data. It happens occasionally, just retry. It is also possible"
                    + " that this is caused by the node running out of disk space, please check if this happens repeatedly. If"
                    + " it still happens constantly, then there is a bug; report it to support@freenetproject.org";
                    summary = "Transfer Failed";
                return new RequestFailedException(msg, summary, false);
            } else {
                Throwable t = r.getThrowable();
                if (!(t instanceof Exception)) {
                    logger.log(this, "Non-Exception Throwable from AutoRequester", t, Logger.NORMAL);
                    return new Exception("Non-Exception Throwable from AutoRequester: " + t.toString());
                } else {
                    return (Exception) t;
                }
            }
        }

        private RouteNotFoundEvent rnf = null;

        private DataNotFoundEvent dnf = null;

        private DateRedirect dr = null;
        
        private TransferFailedEvent tf = null;
    }

    /**
     * Tag type.
     * 
     * @author ian
     */
    private static class RequestFailedException extends Exception {

        private boolean isDNF;

        private String summary;

        /**
         * Constructor for the RequestFailedException object
         * 
         * @param msg
         */
        public RequestFailedException(String msg, String summary, boolean isDNF) {
            super(msg);
            this.isDNF = isDNF;
            this.summary = summary;
        }
    }

    private static class BadKeyException extends Exception {

        /**
         * Constructor for BadKeyException object
         */
        public BadKeyException() {
            super();
        }
    }

}
