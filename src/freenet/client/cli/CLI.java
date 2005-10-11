package freenet.client.cli;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;

import freenet.client.Client;
import freenet.client.Request;
import freenet.client.RequestProcess;
import freenet.client.listeners.EventLogger;
import freenet.config.Config;
import freenet.config.Params;
import freenet.diagnostics.VoidDiagnostics;
import freenet.support.AlwaysFalseBooleanCallback;
import freenet.support.Bucket;
import freenet.support.FileBucket;
import freenet.support.Loader;
import freenet.support.Logger;
import freenet.support.NullBucket;
import freenet.transport.tcpConnection;
/**
 * This class contains methods for reading options in CLI clients.
 *
 * @author oskar (Mostly ripped out of old the Freenet clients though.)
 **/

public class CLI {

    /** Configuration defaults **/
    static public final Config options = new Config();

    static {

        options.addOption("logLevel",             1, "normal", 5001    );     
        options.addOption("logFormat",            1, "m", 5002  );
        options.addOption("logDate",              1, "",  5003     );
        options.addOption("logLevelDetail",            1, "",  5004     );
        options.addOption("logFile",              1, "stderr", 5004  );
        options.addOption("version",              0, null, 10 );
        options.addOption("help",            'h', 0, null, 20 ); 
        options.addOption("htl",                  1, 20, 100);
        options.addOption("clientFactory",   'c', 1, 
                           "freenet.client.cli.CLIFCPClient", 5100);   
        options.addOption("cipher",               1, "Rijndael", 5200);
        options.addOption("metadata",        'm', 1, "", 5300);
        options.addOption("noredirect",        0, null, 500);
	options.addOption("followContainers",  0, true, 501);
        options.addOption("manual",           0, null, 30);
        // options.addOption("contentType",     1, "", 4500); // see below 
        options.addOption("dontGuessType",        0, null, 4600);
        options.addOption("requestTime",    1, "", 4700);
 
        // Temp dir
        options.addOption("tempDir",    1, 10, 4705);

        // SplitFileOptions
        options.addOption("blockHtl",    1, 10, 4805);
        options.addOption("retryHtlIncrement",    1, 5, 4810);
        options.addOption("healPercentage",    1, 0, 4812);
        options.addOption("healingHtl",    1, 5, 4814);
        options.addOption("retries",    1, 3, 4820);
        options.addOption("threads",    1, 20, 4830);
        options.addOption("skipDS",    0, false, 4840);
        options.addOption("algoName",    1, "", 4843);
        options.addOption("doParanoidChecks", 1, true, 4844);
        
        // logLevel
        options.argDesc   ("logLevel", "<word>");
        options.shortDesc ("logLevel", "error, normal, minor, or debug");
        options.longDesc  ("logLevel",
            "The error reporting threshold, one of:",
            "  Error:   Errors only",
            "  Normal:  Report significant events",
            "  Minor:   Report minor events",
            "  Debug:   Report events only of relevance when debugging"
        );
        
        // logFormat
        options.setExpert ("logFormat", true);
        options.argDesc   ("logFormat", "<tmpl.>");
        options.shortDesc ("logFormat", "template, like d:c:h:t:p:m");
        options.longDesc  ("logFormat",
            "A template string for log messages.  All non-alphabet characters are",
            "reproduced verbatim.  Alphabet characters are substituted as follows:",
            "d = date (timestamp), c = class name of the source object,",
            "h = hashcode of the object, t = thread name, p = priority,",
            "m = the actual log message, u = name the local interface"
        );

        // logDate
        options.setExpert ("logDate", true);
        options.argDesc   ("logDate", "<tmpl.>");
        options.shortDesc ("logDate", "java style date/time template");
        options.longDesc  ("logDate",
            "A template for formatting the timestamp in log messages.  Defaults to",
            "the locale specific fully specified date format.  The template string",
            "is an ordinary java date/time template - see:",
            "http://java.sun.com/products/jdk/1.1/docs/api/java.text.SimpleDateFormat.html"
        );

        // logLevelDetail
        options.setExpert ("logLevelDetail", true);
        options.argDesc   ("logLevelDetail", "<prefix:prio,prefix:prio...>");
        options.shortDesc ("logLevelDetail", "Equivalent to logLevelDetail in node");
        options.longDesc  ("logLevelDetail", "List of classes to override log priority for");
        
        // logFile
        options.argDesc   ("logFile", "<filename>");
        options.shortDesc ("logFile", "stdout, stderr, or a filename to send log messages to");

        options.shortDesc("version","Print version information.");

        options.shortDesc("help","Print usage information.");

        options.argDesc("htl","<integer>");
        options.shortDesc("htl","The \"hops to live\" value to use");
        options.longDesc("htl",
                          "The number of nodes that the request should route through.",
                          "Greater HTL values may help find data, but the effect is limited.");
                       

        options.argDesc("tempDir","<string>");
        options.shortDesc("tempDir","Directory to use for temp files.");

        options.argDesc("blockHtl","<integer>");
        options.shortDesc("blockHtl","Htl to use for SplitFile block requests.");


        options.argDesc("retryHtlIncrement","<integer>");
        options.shortDesc("retryHtlIncrement","The amount to increase the htl on retries.");
        options.longDesc("retryHtlIncrement",
                         "The SplitFile block request htl increases by this amount on each retry.",
                         "This allows Main to get the easiest (fewest hops) blocks first.");

        options.argDesc("healPercentage","0-100");
        options.shortDesc("healPercentage","Percentage of unretrievable blocks to re-insert.");
        options.longDesc("healPercentage",
                         "This percentage of blocks is re-inserted after a SplitFile download succeeds.",
                         "Setting this > 0 \"heals\" the network. ");

        options.argDesc("healingHtl","<integer>");
        options.shortDesc("healingHtl","Htl to use when re-inserting unretreivable blocks.");


        options.argDesc("retries","<integer>");
        options.shortDesc("retries","The number of times to retry SplitFile block requests.");

        options.argDesc("threads","<integer>");
        options.shortDesc("threads","The number of concurrent SplitFile threads.");

        options.shortDesc("skipDS","When set ignore keys in local DataStore.");

        options.shortDesc("algoName","Algorithm to use for FEC encoding.");

        options.argDesc("clientFactory","<class name>");
        options.shortDesc("clientFactory","The type of client to use.");
        options.longDesc("clientFactory",
                          "The client can use a number of different pluggable client factories to",
                          "interface with nodes different manners. This includes FCP (Freenet client",
                          "protocol), FNP (Freenet node protocol), various kinds of RPC, and even ",
                          "directly interfacing a node instance.");

        options.argDesc("cipher","<name>");
        options.shortDesc("cipher","The default cipher to use for data.");

        options.argDesc("metadata","<filename>");
        options.shortDesc("metadata","File for metadata");

        options.shortDesc("noredirect","If set, won't follow or make redirects.");
	
	options.shortDesc("followContainers", "If false, won't follow containers");
	
        options.shortDesc("manual", "Generate an HTML manual with more detailed help");
        options.longDesc("manual", 
                         "Automatically generates a manual for the client in HTML. Note that manuals",
                         "are clientFactory specific, see \"clientFactory\" below.");

        /*
          There is no need for this. The only time it was useful can be
          better solved by giving the metadata manually
        options.argDesc("contentType","<mime type>");
        options.shortDesc("contentType","MIME Content-type to use as default");
        options.longDesc("contentType",
                         "The MIME Content-type to give new data during a \"put\" command and to",
                         "use as default otherwise");
        */

        options.shortDesc("dontGuessType","don't get MIME type from file ext... see manual");
        options.longDesc("dontGuessType",
                         "Disables the client from guessing the mime type of data that is retrieved",
                         "or inserted based on the file extension. If enabled, and no additional",
			 "metadata specified, client will insert file without any metadata");
	
	
        options.shortDesc("requestTime","YYYYMMDD[-HH:MM:SS] date based URIs");
        options.longDesc("requestTime",
                         "Some Freenet URIs are modified by the the date to allow regular updates.",
                         "Setting this option to a value greater than 0 overrides using the current",
                         "time for these, with the time given. The format must be YYYYMMDD with an",
                         "optional time part making the format YYYYMMDD-HH:MM:SS.");
                         

    }

    /** version string **/
    static protected final String clientVersion = "2.002";
    /** default port to listen on **/
    static protected final int defaultListenPort = 0;
    /** default address of target node **/
    static protected final String defaultServerAddress = "tcp/127.0.0.1:19114";
    /** default hops to live to give request **/
    static protected final int defaultHopsToLive = 10;
    /** Default logging threshold **/
    static protected final String defaultLogging = "NORMAL";
    /** Default logging verbosity **/
    static protected final String defaultLogFormat = "m";
    /** Default cipher to use for encryption **/
    static protected final String defaultCipherName = "Twofish";

     /** The exit status to of the client **/
    public static int exitState = 0;
    /** Options */
    protected Params params;
    protected Logger logger;
    /** The standard input stream (rather than System.in) */
    protected InputStream in;
    /** The printwriter to use for output (rather than System.out) **/
    protected PrintStream out;
    /** The printwriter to use for error output (rather than System.err) **/
    protected PrintStream err;
    /** The client to use */

    private CLIClientFactory clientFactory;

    /* List of available commands */
    private Hashtable commands;


    /**
     * Creates a new CLI.
     * @param params  An object containing the settings.
     * @exception  CLIException on failure..
     */
    public CLI(Params params) throws CLIException {
	this(params, loadLogger(params), System.in, System.out, System.err);
    }

    /**
     * Creates a new CLI with more control over logging.  This is just a 
     * temporary hack to minimize disruption; really we should make logging
     * a bit more configurable.
     *
     * @param params  An object containing the settings.
     * @param log     A Logger object.  If this is null, a new one will
     *                be created.
     * @exception  CLIException is thrown on failure.
     */
    public CLI(Params params, Logger log) throws CLIException {
	this(params, log, System.in, System.out, System.err);
    }


    /**
     * Creates a new CLI with more control over logging.  This is just a 
     * temporary hack to minimize disruption; really we should make logging
     * a bit more configurable.
     *
     * @param params  An object containing the settings.
     * @param log     A Logger object.  If this is null, a new one will
     *                be created.
     * @exception  CLIException is thrown on failure.
     **/
    public CLI(Params params, Logger log,
	       InputStream in, PrintStream out, PrintStream err)
	throws CLIException {
        
        this(params, loadClientFactory(params, log), log, in, out, err);
    }

    public CLI(Params params, CLIClientFactory cf, Logger log, InputStream in, 
               PrintStream out, PrintStream err) throws CLIException{
        try {
            this.params = params;

            params.addOptions(options.getOptions());
            //params.addOptions(Metadata.options.getOptions());

            this.in = in;
            this.out = out;
            this.err = err;

            logger = log;
            /* Tavin killed the bunny!
               if (params.getString("jump","no").equalsIgnoreCase("yes")) 
               jump();
            */
            
            commands = new Hashtable();
            addCommand(new GetCommand(cf));
            addCommand(new PutCommand(cf));
            addCommand(new SVKPairCommand());
            addCommand(new InvertPrivateKeyCommand());
            addCommand(new ComputeCHKCommand());
            addCommand(new ComputeSHA1Command());
            addCommand(new PutSiteCommand());
	    addCommand(new GetSizeCommand());

            // process options
            //            htl = params.getInt("htl");
            //safer = !params.getString("safer","no").equalsIgnoreCase("no");

            this.clientFactory = cf;
            
        } catch (Throwable e) {
            if (clientFactory != null)
                clientFactory.stop();
            if (e instanceof CLIException) 
                throw (CLIException) e;
            else if (e instanceof RuntimeException)
                throw (RuntimeException) e;
            else 
                throw (Error) e;
        }
    }

    public static Logger loadLogger(Params params) {
        params.addOptions(options.getOptions());
        freenet.Core.setupLogger(params,false);
        
        tcpConnection.startSelectorLoops(freenet.Core.logger,
                new VoidDiagnostics(),new AlwaysFalseBooleanCallback(),false,false);
        return freenet.Core.logger;
    }
    
    public static CLIClientFactory loadClientFactory(Params params,
                                                     Logger logger) 
        throws CLIException {

        params.addOptions(options.getOptions());
        String cfactory = params.getString("clientFactory");
        try {
            Object o = 
                Loader.getInstance(cfactory,
                                   new Class[] {
                                       Params.class, Logger.class 
                                   },
                                   new Object[] {
                                       params, logger
                                   });
            if (!(o instanceof CLIClientFactory)) {
                throw new CLIException("Unsupported client:"
                                       + cfactory);
            }
            return (CLIClientFactory) o;
        } catch (InvocationTargetException e) {
            //            e.getTargetException().printStackTrace();
            Throwable t = e.getTargetException();
            if (t instanceof CLIException) {
                throw (CLIException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            }
            throw new CLIException("Client " + cfactory 
                                   + " threw error:" + t.getMessage());
            
        } catch (NoSuchMethodException e) {
            throw new CLIException("Client " + cfactory 
                                   + " not supported");
        } catch (InstantiationException e) {
            throw new CLIException("Could not instantiate " +
                                   cfactory + " :" + e);
        } catch (IllegalAccessException e) {
            throw new CLIException("Access to " + cfactory 
                                   + " illegal.");
        } catch (ClassNotFoundException e) {
            throw new CLIException("No such client: " + cfactory);
        }
    }

    /**
     * Adds a command to the list available ones.
     */
    public void addCommand(ClientCommand cc) {
        commands.put(cc.getName() , cc);
    }

    public ClientCommand getCommand(String name) {
        return (ClientCommand) commands.get(name);
    }

    /**
     * Stops the client.
     */
    public void stop() {
        clientFactory.stop();
    }


    public boolean execute() {
        if (params.getParam("version") != null) {
            version();
            return false;
        } else if (params.getParam("help") != null) {
            usage();
            return true;
        } else if (params.getParam("manual") != null) {
            manual();
            return true;
        } else if (params.getNumArgs() < 1) {
            usage();
            return false;
        } else {
            ClientCommand cc = getCommand(params.getArg(0));
            if (cc == null) {
                logger.log(this, "Command time: " + cc + " not supported.", Logger.ERROR);
                usage();
                return true;
            }
            //Bucket metadata = null;
            // bad. NPEs all ovah da playz
            Bucket metadata = new NullBucket();
            String mdf = params.getString("metadata");
            if (!"".equals(mdf)) 
                metadata = new FileBucket(new File(mdf));

            Bucket data;
            boolean stream;
            try {
                if (params.getNumArgs() > 1 + cc.argCount()) {
                    stream = false;
                    data = 
                        new FileBucket(new File(params.getArg(cc.argCount()
                                                              + 1)));
                } else {
                    stream = true;
                    data = new FileBucket();
                    if (cc.takesData()) {
                        OutputStream bout = data.getOutputStream();
                        byte[] b = new byte[0xffff];
                        int i;
                        while ((i = in.read(b)) != -1) {
                            bout.write(b, 0, i);
                        }
                    }
                }
            } catch (IOException e) {
                logger.log(this, "Error with data: " + e, e, Logger.ERROR);
                return false;
            }

            RequestProcess rp;
            try {
                rp = cc.getProcess(params, metadata, data);
            } catch (CLIException e) {
                logger.log(this, e.getMessage(), e, Logger.ERROR);
                return false;
            }
	    
	    if(rp == null) return true; // trivial request already finished
	    
            Request r;
            EventLogger logl = new EventLogger(logger);
            CLISplitFileStatus sfs = new CLISplitFileStatus(logger); 
            while ((r = rp.getNextRequest()) != null) {
                if (!clientFactory.supportsRequest(r.getClass())) {
                    logger.log(this, "Current client cannot make request of type " 
                                + r, Logger.ERROR);
                    rp.abort();
                    return false;
                }
                
                r.addEventListener(logl);
                // Print extra status messages for SplitFile requests.
                r.addEventListener(sfs);

                try {
                    Client c = clientFactory.getClient(r);
                    c.start();
                } catch (IOException e) {
                    logger.log(this, "IO error: " + e, e, Logger.ERROR);
                    return false;
                } catch (freenet.KeyException e) {
		    logger.log(this, "Key error: " + e, e, Logger.ERROR);
		    return false;
		}
            }
            if (!rp.failed() && rp.getMetadata() != null) {
                if (logger.shouldLog(Logger.NORMAL,this)) {
                    logger.log(this, "Document metadata:", Logger.NORMAL);
                    logger.log(this, ""+rp.getMetadata(), Logger.NORMAL);
                    if (metadata != null) {
                        try {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            rp.getMetadata().writeTo(baos);
                            baos.close();
                            logger.log(this, new String(baos.toByteArray()), Logger.NORMAL);
                        } catch (IOException e) {
                            logger.log(this, "IO error writing metadata: " +
                                        e.getMessage(), e, Logger.ERROR);
                        }
                    }                    
                }
            } else if (rp.failed())
                logger.log(this, "Request failed.", Logger.ERROR);

            if (!rp.failed() && cc.givesData() && stream) {
                try {
                    // FIXME: move more than a byte at a time!
                    InputStream dataIn = data.getInputStream();
                    int i;
                    byte[] b = new byte[0xffff];
                    while ((i = dataIn.read(b)) != -1) {
                        out.write(b, 0, i);
                    }
                } catch (IOException e) {
                    logger.log(this, "IO error writing data: " + e.getMessage(), e, Logger.ERROR);
                    return false;
                }
            }

            return !rp.failed();
        }
        
    }


    /**
     * Print usage. By default prints options to out, subclasses should 
     * overried.
     */
    public void usage() {
        version();
        out.println("usage: cli <command> [file name] [options]...");
        out.println();
        out.println("Available Commands");
        out.println("------------------");
        for (Enumeration e = commands.elements() ; e.hasMoreElements() ;) {
            ClientCommand cc = (ClientCommand) e.nextElement();
            out.println(cc.getUsage());
        }
        out.println();
        printOptions(out);
    }

    /**
     * Prints the options in the manner the constructor of this class expects
     * then to the PrintWriter, with 80 character lines.
     * @param p  Where to write the options.
     **/
    public void printOptions(PrintStream p) {
        String s = "Standard options";
        StringBuffer l = new StringBuffer("----------------");
        p.println(s);
        p.println(l);
        options.printUsage(p);
        p.println();
        s = "Client: " + clientFactory.getDescription() + " options";
        for (l = new StringBuffer("-------") ; l.length() < s.length() ; 
             l.append('-')); 
        p.println(s);
        p.println(l);
        clientFactory.getOptions().printUsage(p);
    }

    /**
     * Prints version information about the client stdout.
     */
    public void version() {
        out.println(versionString());
    }

    public String versionString() {
	StringBuffer sb = new StringBuffer("cli version ");
        sb.append(clientVersion);
        sb.append(" (factory: ");
        String c = clientFactory.getDescription();
        if (sb.length() + c.length() + 1 > 80) {
            sb.append(System.getProperty("line.separator"));
        }
        sb.append(c);
        sb.append(')');
        return sb.toString();
    }

    public void manual() {
        out.println("<html><body>");
        out.println("<br /><br />");
        out.println("<h2>Freenet Standard CLI Client Documentation</h2>");
        out.println("<h3>" + Config.htmlEnc(versionString()) + "</h3>");
        out.println("<br />");
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance();
        out.println("<i>(This manual was automatically generated by the " + 
                    "--manual switch (see below) on " + 
                    Config.htmlEnc(df.format(new Date()))
                    + ". If you have updated Freenet since then, you " +
                    "may wish regenerate it.)</i>");
        out.println("<br /><br />");
        out.println("freenet.client.cli.Main is a command line front end to "
                    + "the reference client libraries that come with Freenet. "
                    + "It is a rather powerful tool, with automatic metadata "
                    + "handling, and the ability to use arbitrary protocol "
                    + "backends.");
        out.println("<br /><br />");
        out.println("See the <a href=\"http://www.freenetproject.org/"
                    + "index.php?page=documentation\"> project documentation" +
                    " pages</a> for more information, or ask pointed & " +
                    " specific questions on the <a href=\"" +
                    "http://www.freenetproject.org/index.php?page=lists\">" +
                    "mailing lists</a>.");
        out.println("<br /><br />");
        out.println("<b>Usage: </b>java freenet.client.cli.Main &lt;command&gt; [file name] " 
                    + "[options]...");
        out.println("<br />");
        out.println("<h3>Commands:</h3>");
        out.println("<hr></hr>");
        for (Enumeration e = commands.elements() ; e.hasMoreElements() ;) {
            ClientCommand cc = (ClientCommand) e.nextElement();
            out.println("<b>" + Config.htmlEnc(cc.getUsage()) + 
                        "</b><br><br>");
            String[] desc = cc.getDescription();
            for (int i = 0 ; i < desc.length ; i++)
                out.println(desc[i]);
            out.println("<br /><hr></hr>");
        }
        out.println("<br />");
        out.println("<h3>Standard Options:</h3>");
        out.println("<hr></hr>");
        options.printManual(out);
        out.println("<br>");
        out.println("<h3>Client factory Options:</h3>");
        out.println("<hr>");
        clientFactory.getOptions().printManual(out);
        out.println("</body></html>");
    }
}





