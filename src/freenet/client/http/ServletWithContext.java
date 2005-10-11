package freenet.client.http;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.client.ClientFactory;
import freenet.node.Node;
import freenet.support.BucketFactory;
import freenet.support.FileBucket;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.LoggerHookChain;
import freenet.support.TempBucketFactory;

//REDFLAG: get rid of the node dependency

/**
 * Helper base class for constructing Servlets that
 * keep session context information *without* using 
 * cookies.
 * <p>
 * Subclasses can subclass BaseContext to contain 
 * relevant per-session information and use
 * makeContextURL() to make session specific URLs
 * then use getContextFromURL() to retrieve the
 * session information on a later request.
 * </p>
 * 
 * @author <a href="mailto:giannijohansson@attbi.com">Gianni Johansson</a>
 **/
class ServletWithContext extends HttpServlet {

    private final static String START_TAG="__ID_";
    private final static String END_TAG="_ID__";

    public final static String DUMMY_TAG="__DUMMY__";

    private static ContextManager single_cm;
    protected /*private*/ static Reaper single_reaper;

    static {
        // Poll for reapable objects every 60 seconds
        single_reaper = new Reaper(60000);
        Thread reaperThread = new Thread(single_reaper, "Polling thread for single Reaper instance.");
        reaperThread.setDaemon(true);
        reaperThread.start();
        single_cm = new ContextManager();
    }

    // This can return null if the context ID in the URL is bad
    // or if the BaseContext has been reaped.
    protected final static BaseContext getContextFromURL(String url) {
        return (BaseContext)single_cm.lookup(getContextID(url));
    }

    // Returns first path elemenent after ID tag.
    // e.g. /servlet/foo/__ID_897897897897987_ID__/cancel/lala
    // returns "cancel"
    protected final static String getFirstPathElement(String url) {
        int start = url.indexOf(START_TAG);
        if (start == -1) {
            return null;
        }

        int end = url.indexOf(END_TAG, start);
        if (end == -1) {
            return null;
        }

        start += START_TAG.length();
        if (end - start < 1) {
            return null;
        }

        int elementStart = url.indexOf("/", end);
        if (elementStart == -1) {
            return null;
        }
        elementStart++;
        if (elementStart >= url.length() - 1) {
            return null;
        }

        int elementEnd = url.indexOf("/", elementStart);
        if (elementEnd == -1) {
            // Checked that there is at least one character above.
            return url.substring(elementStart);
        }
        if (elementEnd - elementStart > 0) {
            return url.substring(elementStart, elementEnd);
        }
        
        // traps "//"
        return null;
    }

    private final static String getContextID(String url) {
        int start = url.indexOf(START_TAG);
        if (start == -1) {
            return null;
        }

        int end = url.indexOf(END_TAG, start);
        if (end == -1) {
            return null;
        }

        start += START_TAG.length();
        if (end - start < 1) {
            return null;
        }

        return url.substring(start, end);
    }

    protected final static boolean hasContextID(String url) {
        return getContextID(url) != null;
    }

    ////////////////////////////////////////////////////////////
    // Helper functions report errors.
    //
    protected void sendHtml(HttpServletResponse resp, int status,
                            String html)
        throws IOException {
        
        PrintWriter pw = resp.getWriter();
        resp.setStatus(status);
        resp.setContentType("text/html");
        pw.println(html);
        resp.flushBuffer();
    }

    protected void handleBadURI(HttpServletRequest req, HttpServletResponse resp)
        throws IOException {

        sendHtml(resp, HttpServletResponse.SC_BAD_REQUEST,
                 "<html> " +
                 "<head> " +
                 "<title>Bad URI</title> " +
                 "</head> " +
                 "<body> " +
                 "<h1>Bad URI</h1> " +
                 "</body> " +
                 "</html> ");

    }

    protected void handleBadContext(HttpServletRequest req, HttpServletResponse resp)
        throws IOException {

        sendHtml(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                 "<html> " +
                 "<head> " +
                 "<title>Bad Context</title> " +
                 "</head> " +
                 "<body> " +
                 "<h1>Bad Context</h1> " +
                 "The context was bogus or has timed out. " +
                 "</body> " +
                 "</html> ");

        // REDFLAG: fix text. this msg will make users insane.
    }

    protected void handleUnknownCommand(HttpServletRequest req, HttpServletResponse resp,
                                    BaseContext context)
        throws IOException {
        
        sendHtml(resp, HttpServletResponse.SC_BAD_REQUEST,
                 "<html> " +
                 "<head> " +
                 "<title>Unknown Command</title> " +
                 "</head> " +
                 "<body> " +
                 "<h1>Unknown Command</h1> " +
                 "</body> " +
                 "</html> ");

    }

    ////////////////////////////////////////////////////////////
    // Initialization helper functions for 
    // subclasses.

    static BucketFactory bf = null;
    protected ClientFactory cf = null;
    protected LoggerHookChain logger = null;
    protected String tmpDir = null;

    // REDFLAG: Could this be pushed even lower
    //          into a FreenetServlet base class, that
    //          all servlet would derive from?  That
    //          would fix the code reuse issues once
    //          and for all.
    protected void setupLogger(ServletContext context) {

        // REDFLAG: This is horrible.  Better to pass 
        //          the logger instance as a servlet
        //          attribute?  I think that's the way I originally
        //          implemented it before Tavin ripped it
        //          out. 
        Logger parentLog = Node.logger;

        // LATER: Write a logger implementation that
        //        writes to the official servlet log file.
        //        That would make servlets play nice in
        //        real containers (e.g. tomcat).

        String logFile = getInitParameter("logFile");
        //int logLevel = Logger.DEBUG;
        if ((logFile == null) && (parentLog != null)) {
            // Use the logger owned by whatever object
            // created the HttpContainerImpl instance.
            logger = (LoggerHookChain)parentLog;
        } else {
            // Create our own logger
        	String logLevel = getInitParameter("logLevel"); 
            if (logLevel == null) {
                logLevel = "DEBUG";
            }
            
            if (logger == null) {
                logger = new LoggerHookChain(logLevel);
            }
            
            FileLoggerHook lh = null;
            if (logFile != null) {
                try {
                    System.err.println("LOGFILE: " + logFile);
                    if (logFile.toLowerCase().equals("no")) {
                        lh = new FileLoggerHook(System.err,
                                                null, null,
                                                logLevel);
                        
                    } else {
                        lh = new FileLoggerHook(new PrintStream(new FileOutputStream(logFile)),
                                                null, null,
                                                logLevel);
                    }
                } catch (Exception e) {
                }
            }
            if (lh == null) {
                lh = new FileLoggerHook(System.out, null, null, logLevel);
            }
            logger.addHook(lh);
            lh.start();
        }
    }

    // NOTE: Only the first caller gets to set the temp dir.
    //       This is kind of gnotty, but it makes more
    //       sense than having every instances create a new
    //       TempFileBucketFactory.
    // 
    // REDFLAG: just ripped code out of fproxy, this needs to be cleaned up
    //          and moved somewhere else.
    protected void setupBucketFactory(ServletContext context) {
	BucketFactory tbf = 
	    (BucketFactory) context.getAttribute("freenet.support.BucketFactory");
	if(tbf == null) {
	    String s = getInitParameter("tempDir");
	    if(s == null || s.trim().length()==0) {
		s = null;
		if(Node.tempDir != null)
		    s = Node.tempDir.toString();
	    }
	    if(s != null) s = s.trim();
	    if(s != null && s.length()!=0) {
		try {
		    FileBucket.setTempDir(s);
		    tmpDir = FileBucket.getTempDir();
		} catch (IllegalArgumentException ia) {
		    logger.log(this, "WARNING: Couldn't set tempDir: " +
			       getInitParameter("tempDir")
			       , Logger.ERROR);
		}
	    }
	}
        synchronized (SplitFileRequestServlet.class) {
            if (bf == null) {
		if(tbf != null) bf = tbf;
		else bf = new TempBucketFactory(tmpDir);
            }
        }
    }
    
    // Later: support FCP server/port
    protected void setupClientFactory(ServletContext context) {
        cf = (ClientFactory) context.getAttribute("freenet.client.ClientFactory");
    }
}
