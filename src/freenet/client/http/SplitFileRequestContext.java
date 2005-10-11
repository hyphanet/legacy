package freenet.client.http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.client.AutoRequester;
import freenet.client.BackgroundInserter;
import freenet.client.ClientEvent;
import freenet.client.ClientFactory;
import freenet.client.SplitFileStatus;
import freenet.client.events.SplitFileEvent;
import freenet.client.http.filter.ContentFilter;
import freenet.client.http.filter.ContentFilterFactory;
import freenet.client.http.filter.FilterException;
import freenet.client.metadata.MimeTypeUtils;
import freenet.client.metadata.SplitFile;
import freenet.message.client.FEC.SegmentHeader;
import freenet.support.ArrayBucket;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.URLEncoder;
import freenet.support.servlet.HtmlTemplate;

//We can make a Core just for the logger if we need to

//REDFLAG: Remove Node dependancies.
//
//Mattew:
//We need to get rid of the perturbHtl calls.
//I'm not convinced that they provide as much protection
//as people think. Anyway, that functionality belongs
//in the ClientFactory implementations (e.g. FCPClient, InternalClient)
//not sprinkled througout client code.
    //
//We should aim to make our serlvets run in non-freenet containers,
//like Tomcat.

// DESIGN DECISION:
// Keep presentation in SplitFileRequestServlet members.
// Only Servlet stuff in the SFRContext should be
// related to getting parameters and sending
// the data.

/**
 * BaseContext subclass containing code to request the SplitFile from Freenet
 * and information about the request's progress.
 */

public class SplitFileRequestContext extends BaseContext {

    public final static int STATE_INIT = 1;

    public final static int STATE_REQUESTING_METADATA = 2;

    public final static int STATE_STARTING = 3;

    public final static int STATE_WORKING = 4;

    public final static int STATE_FILTERING_DATA = 5;

    public final static int STATE_FILTER_FAILED = 6;

    public final static int STATE_SENDING_DATA = 7;

    public final static int STATE_DONE = 8;

    public final static int STATE_FAILED = 9;

    public final static int STATE_CANCELED = 10;

    public static String stateAsString(int state) {
        switch (state) {
        case STATE_INIT:
            return "STATE_INIT";
        case STATE_REQUESTING_METADATA:
            return "STATE_REQUESTING_METADATA";
        case STATE_STARTING:
            return "STATE_STARTING";
        case STATE_WORKING:
            return "STATE_WORKING";
        case STATE_FILTERING_DATA:
            return "STATE_FILTERING_DATA";
        case STATE_FILTER_FAILED:
            return "STATE_FILTER_FAILED";
        case STATE_SENDING_DATA:
            return "STATE_SENDING_DATA";
        case STATE_DONE:
            return "STATE_DONE";
        case STATE_FAILED:
            return "STATE_FAILED";
        case STATE_CANCELED:
            return "STATE_CANCELED";
        default:
            return "Unknown state: " + state;
        }
        }
        
    public String stateAsString() {
        return stateAsString(state);
    }

    static BucketFactory bf = null;

    protected ClientFactory cf = null;

    protected Logger logger = null;

    protected SplitFileRequestServlet servlet = null;

    SplitFileRequestContext(long lifeTimeMs, String uri, String path, SplitFileRequestServlet servlet, BucketFactory bf) {
        super(lifeTimeMs);
        this.uri = uri;
        SplitFileRequestContext.bf = bf;
        this.path = path;
        this.servlet = servlet;
        servlet.setDefaultContextValues(this);
        startTime = Calendar.getInstance();
        try {
            pageTmp = HtmlTemplate.createTemplate("SimplePage.html");
            refreshPageTmp = HtmlTemplate.createTemplate("RefreshPage.html");
            titleBoxTmp = HtmlTemplate.createTemplate("titleBox.tpl");
        } catch (IOException ioe1) {
            logger.log(this, "Template Initialization Failed!" + ioe1.getMessage(), Logger.ERROR);
        }
        }

    int state = STATE_INIT;

    // Stash some info about why the request failed.
    String errMsg = "";

    volatile boolean canceling = false;

    Thread downloadThread = null;

    // Does actual freenet request
    AutoRequester requester;

    // Receives download status events that we use
    // to update the presentation.
    /* FIXME */public SplitFileStatus status;

    /* FIXME */public String uri; // completely unencoded. Must be URLEncoded,

    // then HTMLEncoded, if it is to be included
    // in HTML code. Same applies to get*URL()

    // Keep root servlet path for making redirects? Push into base?
    String path;

    String mimeType;

    String contentDesc;

    /* FIXME */public SplitFile sf;

    // MUST free this.
    Bucket data;

    // SplitFile request parameters
    int htl;

    int blockHtl;

    int retries;

    int retryHtlIncrement;

    int threads;

    boolean doParanoidChecks;

    int healHtl;

    int healPercentage;

    boolean writeToDisk = false;

    boolean disableWriteToDisk = false;

    File writeDir = null;

    boolean forceSave = false;

    boolean skipDS = false;

    boolean randomSegs = false;

    boolean useUI = true;

    //int useUIMinSize = 1<<21;

    // Anonymity filtering stuff
    boolean runFilter = false;

    boolean filterParanoidStringCheck = false;

    int refreshIntervalSecs;

    String decoderErrMsg = null;

    protected HtmlTemplate titleBoxTmp;

    protected HtmlTemplate pageTmp;

    protected HtmlTemplate refreshPageTmp;

    /* FIXME */public Calendar startTime;

    Calendar endTime;

    /* FIXME */public SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z");

    synchronized void setState(int value) {
        state = value;
    }

    /*
     * Updates parameters from values set in urls query params. @return null if
     * ok, an error message if not
     */
    synchronized String updateParameters(HttpServletRequest req) {
        // hmmm... hard coded limit constants ok for now.
        blockHtl = ParamParse.readInt(req, logger, "blockHtl", blockHtl, 0, 100);
        retryHtlIncrement = ParamParse.readInt(req, logger, "retryHtlIncrement", retryHtlIncrement, 0, 100);
        healHtl = ParamParse.readInt(req, logger, "healHtl", healHtl, 0, 100);
        healPercentage = ParamParse.readInt(req, logger, "healPercentage", healPercentage, 0, 100);
        retries = ParamParse.readInt(req, logger, "retries", retries, 0, 50);
        threads = ParamParse.readInt(req, logger, "threads", threads, 0, 100);
        useUI = ParamParse.readBoolean(req, logger, "useUI", useUI);
        //useUIMinSize = ParamParse.readInt(req, logger, "useUIMinSize",
        // useUIMinSize, 0, Integer.MAX_VALUE);

        if ((!disableWriteToDisk) && ParamParse.readBoolean(req, logger, "writeToDisk", false)) {
            String filename = req.getParameter("saveToDir");
            if (filename == null || filename.length() == 0) {
                return "If writing directly to disk, you must specify a folder";
            } else {
                writeDir = new File(filename);
                if (!writeDir.exists()) {
                    if (!writeDir.mkdir()) return "The node could not create the specified folder " + writeDir;
            } 
                if (!writeDir.isDirectory() || !writeDir.canWrite())
                    return "You must specify the name of a folder writable by " + "the node, or which the node can create";
            else {
                    if (logger.shouldLog(Logger.DEBUG, this)) logger.log(this, "WriteToDisk set to true for " + this, Logger.DEBUG);
                    writeToDisk = true;
            }
        }
            }
        // NOTE: checkbox's return nothing at all in the unchecked
        //       state.
        if (ParamParse.readBoolean(req, logger, "usedForm", false)) {
            forceSave = ParamParse.readBoolean(req, logger, "forceSaveCB", false);
            skipDS = ParamParse.readBoolean(req, logger, "skipDSCB", false);
            randomSegs = ParamParse.readBoolean(req, logger, "randomSegs", false);
            runFilter = ParamParse.readBoolean(req, logger, "runFilterCB", false);
            filterParanoidStringCheck = ParamParse.readBoolean(req, logger, "filterParanoidStringCheck", false);
        } else {
            forceSave = ParamParse.readBoolean(req, logger, "forceSave", forceSave);
            skipDS = ParamParse.readBoolean(req, logger, "skipDS", skipDS);
            randomSegs = ParamParse.readBoolean(req, logger, "randomSegs", randomSegs);
            runFilter = ParamParse.readBoolean(req, logger, "filter", runFilter);
            filterParanoidStringCheck = ParamParse.readBoolean(req, logger, "paranoidFilter", false);
        }
        return null;
        }

    // REDFLAG: BUG: Canceling isn't working reliably yet.
    //               Need to look at how AutoRequester handles
    //               having it's thread interrupted.
    //               Also need to check that FEC decode
    //               plugin implementation handles interrupting
    //               correctly.

    // DO NOT call this on the downloading thread.
    // Cancels and releases all resources as soon as possible.
    synchronized void cancel() {
        boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        if (logDEBUG) logger.log(this, "In cancel(), state " + stateAsString(), Logger.DEBUG);
        if (canceling) {
            if (logDEBUG) logger.log(this, "Returned from cancel() because " + "already cancelling", Logger.DEBUG);
            return; // Already canceling.
        }
        canceling = true;
        switch (state) {
        case STATE_INIT:
            setState(STATE_CANCELED);
            break;
        case STATE_REQUESTING_METADATA:
            // There is no race condition because aborting
            // a request that hasn't started yet causes
            // the request to abort when it is started.
            requester.abort();
            downloadThread.interrupt();
            break;
        case STATE_STARTING:
            setState(STATE_CANCELED);
            break;
        case STATE_WORKING:
            requester.abort();
            downloadThread.interrupt();
            break;
        case STATE_FILTER_FAILED:
            setState(STATE_CANCELED);
            cleanup();
            break;
        case STATE_FILTERING_DATA:
        case STATE_SENDING_DATA:
            // Drop through on purpose.
            downloadThread.interrupt();
            break;

        case STATE_DONE:
        case STATE_FAILED:
        case STATE_CANCELED:
            // All NOPs. Fall through on purpose.
            break;
        default:
            throw new RuntimeException("Unknown state: " + state);
        }
        if (logDEBUG) logger.log(this, "Finished cancel()", Logger.DEBUG);
    }

    // Releases resources for request, but leaves UI around
    // MUST NOT THROW.
    void cleanup() {
        if (data != null) {
        try {
                bf.freeBucket(data);
            } catch (Exception e) {
        }
            data = null;
        }
    }

    // Releases context -- i.e. no more UI
    public boolean reap() {
        synchronized (SplitFileRequestContext.this) {
            if ((state != STATE_INIT) && (state != STATE_STARTING) && (state != STATE_FILTER_FAILED) && (state != STATE_DONE)
                    && (state != STATE_FAILED) && (state != STATE_CANCELED)) {

                // REDFLAG: test this code path.
                // Cleanly cancel the context times out.
                cancel();
                return false;
        }
    }
        try {
            cleanup();
        } catch (Error e) {
            super.reap();
            throw e;
        } catch (RuntimeException e) {
            super.reap();
            throw e;
            }
        // IMPORTANT: Deletes the context from the lookup table.
        return super.reap();
            }

    boolean getMetadata(HttpServletRequest req, HttpServletResponse resp) throws FilterException {

        synchronized (SplitFileRequestContext.this) {
            if (state != STATE_INIT) { throw new IllegalStateException("You can't restart a SplitFile metadata request. State was " + stateAsString()); }

            // Save in case we need to interrupt() later.
            downloadThread = Thread.currentThread();

            requester = new AutoRequester(cf);
            // Stops downloading when it hits the SplitFile metatata.
            requester.setHandleSplitFiles(false);

            setState(STATE_REQUESTING_METADATA);
    }

        boolean success = false;
        
        try {
            if (!requester.doGet(uri, new ArrayBucket(), htl)) {
                logger.log(this, "METADATA REQUEST FAILED.", Logger.ERROR);
                if (!canceling) {
                    // LATER: nicer error reporting
                    // AutoRequester can return errors that are like line
                    // noise.
                    //
                    // LATER: add retrying? This should never happen because
                    //        fproxy had to retrieve the metadata before
                    //        it redirected to this servlet.

                    errMsg = errMsg = "Download failed. Couldn't read SplitFile Metadata";
                    setState(STATE_FAILED);
                    servlet.handleMetadataRequestFailed(req, resp, this);
                }
                return false; // Finally handles cancels.
            }
            // Extract mime type.
            mimeType = MimeTypeUtils.fullMimeType(null, requester.getMetadata(), uri);

            synchronized (SplitFileRequestContext.this) {
                // Recheck predicate after acquiring lock.
                if (state == STATE_REQUESTING_METADATA && !canceling) {
                    sf = requester.getMetadata().getSplitFile();
                    if (sf == null) {
                        // No SplitFile metadata.
                        errMsg = "URI isn't a SplitFile!";
                        setState(STATE_FAILED);
                        servlet.handleNotASplitFile(req, resp, uri, this);
                        return false;
            }

                    // We handle non-redundant SplitFiles now.
                    if (sf.getFECAlgorithm() == null) {
                        // check for obsolete decoders
                        if (sf.getObsoleteDecoder() != null) {
                            decoderErrMsg = "This Splitfile requires an obsolete decoder (" + sf.getObsoleteDecoder()
                                    + ") which is not supported. Ask the content " + "author to re-insert it in a supported format.";
                        } else {
                            // No decoder at all.
                            decoderErrMsg = "No FEC decoder specified in SplitFile metadata!";
                            if (logger.shouldLog(Logger.DEBUG, this))
                                    logger.log(this, "No FEC decoder specified for " + uri, new Exception("debug"), Logger.DEBUG);
                }
            }

                    String fecInfo = "none";
                    if ((sf.getFECAlgorithm() != null) && (sf.getCheckBlockCount() > 0)) {
                        int redundancy = (100 * sf.getCheckBlockCount()) / sf.getBlockCount();
                        fecInfo = sf.getFECAlgorithm() + " (" + Integer.toString(redundancy) + "% redundancy)";
        }

                    // e.g. "120MB video/avi, FEC:OnionDecoder_0 (50%
                    // redundancy)";
                    contentDesc = ", &nbsp; &nbsp; " + formatByteCount(sf.getSize()) + " " + mimeType + ", FEC decoder: " + fecInfo;

                    // Groovy.
                    setState(STATE_STARTING);
        }
        }
        } catch (Exception e) {
            logger.log(this, "UNEXPECTED EXCEPTION: ", e, Logger.ERROR);
        } finally {
            synchronized (SplitFileRequestContext.this) {
                downloadThread = null;
                requester = null;
                success = state == STATE_STARTING;

                if ((state != STATE_STARTING) && (state != STATE_CANCELED)) {
                    if (canceling) {
                        setState(STATE_CANCELED);
                    } else {
                        setState(STATE_FAILED);
            }
        }
            }
        }
        return success;
        }

    // DO NOT call this on the downloading thread.
    synchronized void handleDroppedConnection() {
        cancel();
        errMsg = "The client dropped the connection.";
        }

    void preSetupFilter() {
        filter = ContentFilterFactory.newInstance(servlet.filterPassThroughMimeTypes, bf);
        String[] s = MimeTypeUtils.splitMimeType(mimeType);
        mimeTypeParsed = s[0];
        charset = s[1];
        try {
            filter.wantFilter(mimeTypeParsed, charset);
            if (mimeTypeParsed.startsWith("image/") || mimeTypeParsed.startsWith("text/")) {
                // Recognized MIME types in image/ or text/ will be inlined in
                // lower frame by default
                forceSave = false;
                writeToDisk = false;
        }
        } catch (FilterException e) {
            forceSave = true; // Force to disk if unrecognized MIME type
            // At this stage this can only be caused by an unrecognized MIME
            // type. Simply send it as application/octet-stream and we're okay.
            // Most uses will go through fproxy anyhow, and will therefore have
            // runFilter=false after the confirmation dialog
            mimeType = servlet.DEFAULT_MIME_TYPE;
    }
        }

    void setupFilter() {
        // FIXME: could the MIME type have changed between preSetupFilter and
        // setupFilter? Presuming so...
        String[] s = MimeTypeUtils.splitMimeType(mimeType);
        mimeTypeParsed = s[0];
        charset = s[1];
        try {
            if (!filter.wantFilter(mimeTypeParsed, charset))
                filter = null; // Don't filter if don't need to filter
        else {
                // Filter it
            }
        } catch (FilterException e) {
            filter = null; // At this stage this can only be caused by an
            // unrecognized MIME type. Simply send it as
            // application/octet-stream and we're okay. Most
            // uses will go through fproxy anyhow, and will
            // therefore have runFilter=false after the
            // confirmation dialog
            mimeType = servlet.DEFAULT_MIME_TYPE;
        }
    }
        
    RequestThread t = null;

    void doBackgroundRequest() {
        if (logger.shouldLog(Logger.DEBUG, this)) logger.log(this, "doBackgroundRequest for " + this, Logger.DEBUG);
        if (t == null) {
            t = new RequestThread();
            t.start();
        }
        }

    class RequestThread extends Thread {
        
        public void run() {
            if (logger.shouldLog(Logger.DEBUG, this)) logger.log(this, "Starting RequestThread", Logger.DEBUG);
            try {
                doRequest(null, null);
            } catch (Throwable t) {
                logger.log(this, "Got Throwable trying to do request in background, aborting", t, Logger.ERROR);
                if (!(state == STATE_DONE || state == STATE_FAILED || state == STATE_CANCELED)) {
                    requester.abort();
                    canceling = true; // so don't show up on list
        }
            } finally {
                logger.log(this, "Finished RequestThread: " + this, Logger.MINOR);
            }
        }
    }

    boolean running = false;
    
    void doRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException, FilterException {
        running = true;
        try {
            innerDoRequest(req, resp);
        } finally {
            running = false;
        }
    }
    
    // Synchronous
    final void innerDoRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException, FilterException {

        synchronized (SplitFileRequestContext.this) {
            if (state != STATE_STARTING) { throw new IllegalStateException("You can't restart a SplitFile request."); }

            // Save in case we need to interrupt() later.
            downloadThread = Thread.currentThread();

            setState(STATE_WORKING);

            // updateParameters() is now called from onSplitBottom()
            // because that is were the parameter form submits the
            // parameters to. We can't get them anymore once we are
            // here. /Bombe
            //updateParameters(req);

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

            requester.setHandleSplitFiles(true);
            requester.setBlockHtl(blockHtl);
            requester.setSplitFileRetries(retries);
            requester.setSplitFileRetryHtlIncrement(retryHtlIncrement);
            requester.setSplitFileThreads(threads);
            requester.setNonLocal(skipDS);
            requester.setRandomSegs(randomSegs);

            // TODO: make parameters, add to GUI options.

            // Ask the AutoRequester to re-insert 5% of the
            // unretrievable SplitFileBlocks at an HTL of 5.
            //
            // "Healing" the network like this should make
            // SplitFiles much more easily retrievable.
            // If most people do it, that is...
            requester.setHealPercentage(healPercentage);
            requester.setHealingHtl(healHtl);

            requester.enableParanoidChecks(doParanoidChecks);

            requester.setBackgroundInserter(BackgroundInserter.getInstance());

            // Uncomment this to get really verbose status info dumped
            // to std.err.
            //
            // requester.addEventListener(new
            //    freenet.client.cli.CLISplitFileStatus( new
            // PrintWriter(System.err)));

            requester.addEventListener(status);
            data = bf.makeBucket(-1);
        }

        //System.err.println("------------------------------------------------------------");
        //System.err.println("htl : " + htl);
        //System.err.println("blockHtl : " + blockHtl);
        //System.err.println("retries : " + retries);
        //System.err.println("retryHtlIncrement : " +
        //                   retryHtlIncrement);
        //System.err.println("healHtl : " + healHtl);
        //System.err.println("healPercentage : " + healPercentage);
        //System.err.println("threads : " + threads);
        //System.err.println("doParanoidChecks : " +
        //                   doParanoidChecks);
        //System.err.println("forceSave : " + forceSave);
        //System.err.println("skipDS : " + skipDS);
        //System.err.println("useUI : " + useUI);
        //System.err.println("useUIMinSize : " + useUIMinSize);
        //System.err.println("runFilter : " + runFilter);
        //System.err.println("filterParanoidStringCheck : " +
        //                   filterParanoidStringCheck);
        //System.err.println("------------------------------------------------------------");

        OutputStream out = null;
        if (forceSave || mimeType.equals(servlet.DEFAULT_MIME_TYPE)) {
            // IMPORTANT:
            // We can't send back an html error messages on this response.
            // once the headers are set. See below. If the user
            // is using the UI, the msg should be rendered in the
            // status pane.
            //
            // If they aren't running the UI the connection just
            // drops without all the data.
            // REDFLAG: Underwhelming but I can't see how
            //          to do better. :-(
            // REDFLAG: This will cause grief with clients like wget
            //          that retry if the error is not recoverable.
            // REVIST: Do I always need to do this? i.e. will the browser
            //         really wait tens of minutes without getting anything
            //         back on the socket?

            // Send the headers immediately so that the browser pops
            // up the save dialog box.
            mimeType = servlet.DEFAULT_MIME_TYPE;
            if (resp != null) out = sendDataHeaders(resp);
            runFilter = false; // don't filter unless need to - we can't do
            // anything with application/octet-stream
            filter = null;
    }

        ConnectionPoller poller = null;
        // RELEASE LOCK.
        try {
            if (req != null && servlet.pollForDroppedConnections) {
                poller = new ConnectionPoller(req, 10000, 
                // Anonymous adapter to cancel the request
                        // if the connection is dropped.
                        new Runnable() {

                            public void run() {
                                handleDroppedConnection();
                            }
                        });
            }

            if (!requester.doGet(uri, data, htl)) {
                if (!canceling) {
                    // LATER: nicer error reporting
                    // AutoRequester can return errors that are like line
                    // noise.
                    errMsg = requester.getError();
                    if (errMsg != null) {
                        if (errMsg.length() == 0) {
                            errMsg = "Download failed. Couldn't get status of last segement.";
                        }
                    }
                    setState(STATE_FAILED);
                    if (!forceSave) {
                        // Can only send html if we haven't already
                        // sent the headers to setup for sending the data.
                        if (req != null && resp != null) servlet.handleRequestFailed(req, resp, this);
                    }
                }
                return; // Finally handles cancels.
            }
            // IF we run the filter, we run it during download, streaming
            //                 if (runFilter) {
            //                     // We already have the mime type
            //                     setState(STATE_FILTERING_DATA);
            //                     try {
            //                         // Must be interruptable.
            //                         filterData();
            //                     }
            //                     catch (FilterException fe) {
            //                         synchronized (SFRContext.this) {
            //                             downloadThread = null;
            //                             setState(STATE_FILTER_FAILED);
            //                         }
            //                         if (!forceSave) {
            //                             handleFilterException(req, resp, fe, this);
            //                         }
            //                         return;
            //                     }
            //                 }

            if (poller != null) {
                // Don't let the poller run while sending
                // data because there would be a race
                // condition if the client dropped
                // the connection after receiving all the
                // data but before setState(STATE_DONE) was
                // called.
                //
                // The socket writes will throw if
                // connection has been dropped anyway.
                poller.stop();
                poller = null;

            }
            setState(STATE_SENDING_DATA);
            // SENDING
            // Must be interruptable.
            if (resp != null) {
                if (out == null) {
                    sendData(resp);
                } else {
                    sendDataWithoutHeaders(out);
                    out = null; // call above closes out.
	}
            } else {
                writeData();
	}
            setState(STATE_DONE);
        } catch (InterruptedException ie) {
            // NOP
            // cancel() interrupt()'s this thread.
        } finally {
            if (poller != null) {
                poller.stop();
	}
	
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {
	}
	}
            synchronized (SplitFileRequestContext.this) {
                downloadThread = null;
                if (state != STATE_FILTER_FAILED) {
                    cleanup();
    }
                if ((state != STATE_DONE) && (state != STATE_FAILED) && (state != STATE_CANCELED) && (state != STATE_FILTER_FAILED)) {
                    if (canceling) {
                        // REDFLAG: why didn't I set errMsg here?
                        setState(STATE_CANCELED);
                    } else {
                        if (state == STATE_SENDING_DATA) {
                            errMsg = "The file was downloaded from Freenet but couldn't be " + "sent to the browser.  Maybe the user dropped the "
                                    + "connection?";
            }
                        setState(STATE_FAILED);
        }
        }
    }
            endTime = Calendar.getInstance();
        }
        }

    // Synchronous
    // Called to send the data after the filter has failed.
    void doOverrideFilter(HttpServletRequest req, HttpServletResponse resp) throws IOException, FilterException {

        try {
            synchronized (SplitFileRequestContext.this) {
                if (state != STATE_FILTER_FAILED) { throw new IllegalStateException("Filter didn't fail?"); }
                
                // Save in case we need to interrupt() later.
                downloadThread = Thread.currentThread();
        
                setState(STATE_SENDING_DATA);
            }
        
            // Check to see if the mime type was overriden in
            // the url's query parameters.
            String oldMimeType = mimeType;
            updateParameters(req);
            if (mimeType != oldMimeType) {
                mimeType = MimeTypeUtils.fullMimeType(mimeType, null, null);
            }

            // SENDING
            // Must be interruptable.
            if (resp != null)
                sendData(resp);
            else
                writeData();
            setState(STATE_DONE);

        } catch (InterruptedException ie) {
            // NOP
            // cancel() interrupt()'s this thread.
        } finally {
            synchronized (SplitFileRequestContext.this) {
                downloadThread = null;
                cleanup();
                if (state != STATE_DONE) {
                    if (canceling) {
                        setState(STATE_CANCELED);
                    } else {
                        if (state == STATE_SENDING_DATA) {
                            errMsg = "The file was downloaded from Freenet but couldn't be " + "sent to the browser.  Maybe the user dropped the "
                                    + "connection?";
                        }
                        setState(STATE_FAILED);
                    }
                }
		    }
		}
            }

    ContentFilter filter = null;
            
    String mimeTypeParsed = null;
            
    String charset = null;
            
    //         void filterData() throws FilterException, InterruptedException {
    //             synchronized (SFRContext.this) {
    //                 if (state != STATE_FILTERING_DATA) {
    //                     throw new IllegalStateException();
            //                      }
            //                      }
            //  	    }
            
    OutputStream sendDataHeaders(HttpServletResponse resp) throws IOException {

        // Set response headers, 200
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(mimeType);
        resp.setContentLength((int) sf.getSize());
        OutputStream out = resp.getOutputStream();
        try {
            out.flush();
        } catch (IOException ioe) {
            try {
                out.close();
            } catch (Exception e) {
            }
            throw ioe;
            }

        return out;
    }

    void sendDataWithoutHeaders(OutputStream out) throws IOException, InterruptedException {
        InputStream in = null;
        Bucket fromFilter = null;
        try {
            if (runFilter && filter != null) {
                fromFilter = filter.run(data, mimeTypeParsed, charset);
                in = fromFilter.getInputStream();
                if (fromFilter == data) fromFilter = null;
            } else {
                in = data.getInputStream();
            }
            // stream bucket to output
            byte[] buf = new byte[16384];
            int bytes = 0;
            while ((bytes = in.read(buf)) != -1) {
                // Hmmmm.... yeild a little?
                if (canceling) { throw new InterruptedException("Request was canceled while copying data"); }
                out.write(buf, 0, bytes);
                }

            out.flush();
        } finally {
            if (in != null) {
                try {
            in.close();
        } catch (Exception e) {
        }
            }
            if (fromFilter != null) {
                    try {
                    bf.freeBucket(fromFilter);
                } catch (IOException e) {
                        }
                
                    }
                    }
                }

    void writeData() throws IOException, InterruptedException {
        if (writeDir == null) throw new IllegalStateException("writeDir null in writeData!");
        String filename = filename();
        FileOutputStream os = new FileOutputStream(new File(writeDir, filename));
        sendDataWithoutHeaders(os);
        os.close();
            }

    public String displayKey() {
        int p;
        if (uri.startsWith("CHK@") && ((p = uri.lastIndexOf("/")) != -1)) {
            return uri.substring(0, p);
        } else {
            return uri;
        }
    }

    public String filename() {
        String filename = uri;
        int p;
        // try to derive filename from URI
        if ((p = filename.lastIndexOf("/")) != -1) {
            filename = filename.substring(p + 1);
                    }
        if ((p = filename.lastIndexOf(File.separator)) != -1) {
            filename = filename.substring(p + 1);
        }
        if (filename.length() == 0) {
            filename = uri;
                    }
        filename = fsSafeChars(filename);
        if (filename.length() == 0) {
            filename = "defaultname";
                }
        return filename;
            }

    public static String fsSafeChars(String s) {
        StringBuffer out = new StringBuffer();
        for (int x = 0; x < s.length(); x++) {
            char c = s.charAt(x);
            if (":/\\".indexOf(c) == -1)
                out.append(c);
            else
                out.append('-');
            }
        return new String(out);
        }

    void sendData(HttpServletResponse resp) throws IOException, InterruptedException {

        synchronized (SplitFileRequestContext.this) {
            if ((state != STATE_SENDING_DATA) && (state != STATE_FILTER_FAILED)) { throw new IllegalStateException(); }
    }

        sendDataWithoutHeaders(sendDataHeaders(resp));
    }

    synchronized boolean isUpdating() {
        return (state != STATE_DONE) && (state != STATE_FAILED) && (state != STATE_CANCELED);
        }

    // URI to re-run this request from
    // scratch.
    synchronized String retryURL() {
        return path + "/" + uri;
        }

    synchronized String cancelURL() {
        return path + "/" + makeContextURL(DUMMY_TAG + "/cancel");
    }

    synchronized String overrideFilterURL() {
        return path + "/" + makeContextURL(DUMMY_TAG + "/override_filter");
                }

    public synchronized String progressURL() {
        return path + "/" + makeContextURL(DUMMY_TAG + "/status_progress");
                }

    synchronized String parameterURL() {
        return path + "/" + makeContextURL(DUMMY_TAG + "/parameter_form");
                    }

    synchronized String splitBottomURL() {
        return path + "/" + makeContextURL(DUMMY_TAG + "/split_bottom");
                        }

    synchronized String downloadURL() {
        int x = uri.lastIndexOf('/');
        String s = uri;
        if (x > 0 && x < (uri.length() - 1)) s = s.substring(x, uri.length());
        return path + "/" + makeContextURL(DUMMY_TAG + "/download/" + s);
                        }

    // REDFLAG: C&P from manifest tools, Factor this out somewhere.
    private final static String formatByteCount(long nBytes) {
        String unit = "";
        double scaled = 0.0;
        if (nBytes >= (1024 * 1024)) {
            scaled = ((double) nBytes) / (1024 * 1024);
            unit = "M";
        } else if (nBytes >= 1024) {
            scaled = ((double) nBytes) / 1024;
            unit = "K";
        } else {
            scaled = nBytes;
            unit = "bytes";
                    }

        // Truncate to one digit after the decimal point.
        String value = Double.toString(scaled);

        int p = value.indexOf(".");
        if ((p != -1) && (p < value.length() - 2)) {
            value = value.substring(0, p + 2);
        }

        if (value.endsWith(".0")) {
            value = value.substring(0, value.length() - 2);
                }

        return value + unit;
            }

    public double progress() {
        if (state == STATE_INIT || state == STATE_REQUESTING_METADATA || state == STATE_STARTING) return 0.0;
        if (state == STATE_DONE || state == STATE_FILTERING_DATA || state == STATE_FILTER_FAILED || state == STATE_SENDING_DATA) return 1.0;
        long totalSize = status == null ? 0 : status.dataSize();
        if (totalSize == 0) {
            totalSize = sf.getSize();
        }
        long retrievedBytes = status == null ? 0 : status.retrievedBytes();
        return (double) retrievedBytes / (double) totalSize;
        }

    public void writeHtml(PrintWriter sw) {
        writeHtml(sw, false);
        }

    /**
     * @param sw
     *            the stream to write to
     * @param preRun
     *            if true, don't even try to write download status
     */
    public void writeHtml(PrintWriter sw, boolean preRun) {
        if (canceling) return;
        if (!running) return;
        if (sf == null) return; // invalid splitfile
        SegmentHeader header = null;
        if (status != null) {
            header = status.segment();
        }

        String filename = filename();
        String key = displayKey();
        sw.println("<table border=\"0\"><tr>");
        sw.println("<td valign=\"top\">");
        String shortKey = key;
        if (shortKey.startsWith("freenet:")) shortKey = key.substring("freenet:".length(), key.length());
        String saveKey = uri;
        if (saveKey.startsWith("freenet:")) saveKey = saveKey.substring("freenet:".length(), saveKey.length());
        sw.println("<p><b>Key</b>: freenet:<a href=\"/" + HTMLEncoder.encode(URLEncoder.encode(saveKey)) + "\">" + shortKey + "</a><br />");
        if (filename != null) {
            sw.println("<b>Filename</b>: " + filename + ", " + "<b>Length:</b> " + format(sf.getSize()));
        } else {
            sw.println("<b>Length:</b> " + format(sf.getSize()));
        }
        sw.println("<br /><b>MIME Type</b>: " + mimeType);
        if (preRun) {
            sw.println("</p></td></tr></table>");
            return;
    }
        if (header != null) {
            double progress = progress();
            int width=100;

            sw.println("<br /><b>Status</b>: ");
            try {
                HtmlTemplate barTemplate=null;
                if (progress==0.0) {
                    barTemplate=HtmlTemplate.createTemplate("bar.tpl");
                    barTemplate.set("COLOR","");
                    barTemplate.set("WIDTH",Integer.toString(width));
                } else if (progress==1.0) {
                    barTemplate=HtmlTemplate.createTemplate("bar.tpl");
                    barTemplate.set("COLOR","g");
                    barTemplate.set("WIDTH",Integer.toString(width));
                } else {
                barTemplate=HtmlTemplate.createTemplate("relbar.tpl");
                barTemplate.set("LBAR","g");
                barTemplate.set("RBAR","");
                barTemplate.set("LBARWIDTH",Integer.toString((int) (progress * width)));
                barTemplate.set("RBARWIDTH",Integer.toString((int) ((1 - progress) * width)));
            }
            barTemplate.set("ALT",Integer.toString((int)progress*100)+"%");
            barTemplate.toHtml(sw);
            
            } catch (IOException e) {
                Core.logger.log(this,"Couldn't load template",e,Logger.NORMAL);
            }
            //long deltat = (System.currentTimeMillis() - status.touched()) /
            // 1000;
            //if (deltat < 60) {
            //    tpw.print(" <b>Idle</b>: " + deltat + " seconds");
            //} else {
            //    tpw.print(" <b>Idle</b>: " + (int) (deltat / 60) + " minutes");
            //}
            sw.print("<br /><b>Request Started:</b> " + dateFormat.format(startTime.getTime()));
            sw.println(", <b>Time Elapsed:</b> " + timeDistance(startTime, Calendar.getInstance()));
            if ((progress >= 0.1) || ((status.blocksProcessed() > 4) && (status.retrievedBytes() > 0))) {
                long start = startTime.getTime().getTime();
                long elapsed = Calendar.getInstance().getTime().getTime() - start;
                long end = start + (long) (elapsed / progress);
                Calendar eta = Calendar.getInstance();
                eta.setTime(new Date(end));
                sw.println("<br /><b>Estimated Finish Time:</b> " + dateFormat.format(eta.getTime()));
                long throughput = status.retrievedBytes() * 1000 / elapsed;
                sw.println("<br /><b>Speed:</b> " + format(throughput) + "/s");//gimme
                // what
                // ah
                // need..
            }
        }
        sw.println("<br /><a href=\"" + progressURL() + "\"><b>See full status</b></a>");
        sw.println("&nbsp;&nbsp; <a href=\"" + cancelURL() + "\"><b>Cancel download</b></a>");
        sw.println("</p></td></tr></table>");
        sw.println("<br />");

        //sw.println("<tr><td><a href=\"" + key + "\">Key</a></td>");
        //sw.println("<td><a href=\"" + sfrc.progressURL() + "\">View
        // progress</a></td>");
        //sw.println("<td><a href=\"" + sfrc.cancelURL() +
        // "\">Cancel</a></td>");
        //sw.println("<td align=\"right\">" + sfrc + "</td></tr>");
    }

    private NumberFormat nf = NumberFormat.getInstance();

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

    // FIXME: STRAIGHT COPY/PASTE FROM SplitFileRequestServlet
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

}
