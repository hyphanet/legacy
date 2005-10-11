package freenet.client;

import java.util.Vector;

import freenet.BadAddressException;
import freenet.Core;
import freenet.client.events.ErrorEvent;
import freenet.client.events.ExceptionEvent;
import freenet.client.events.StateReachedEvent;
import freenet.client.listeners.DoneListener;
import freenet.client.metadata.DateRedirect;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InfoPart;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.MimeTypeUtils;
import freenet.client.metadata.Redirect;
import freenet.client.metadata.SplitFile;
import freenet.support.Bucket;
import freenet.support.Logger;
import freenet.support.TempBucketFactory;
import freenet.transport.TCP;

/**
 * A client that automatically does requests with a java interface. It's really
 * much smarter to use the RequestProcess classes themselves, but I know you
 * won't anyways, so I'll save my breath.
 * 
 * @author oskar
 */
public class AutoRequester {

	private static final TCP tcp = new TCP(0, false);
	private ClientFactory clientFactory;

	private boolean doRedirect = true;
	private boolean doDateRedirect = false;
	private long drOffset = DateRedirect.DEFAULT_OFFSET;
	private int drIncrement = DateRedirect.DEFAULT_INCREMENT;
	private long drTime = -1; /* <0 = 'now'    */
	private String error;
	private Throwable origThrowable;
	private Metadata metadata;
	private FreenetURI keyUri;

	private boolean aborting = false;
	private Client currentClient = null;
	private RequestProcess currentRequestProcess = null;

	private boolean nonLocal = false;
	private boolean handleSplitFiles = false;
	private int blockHtl = freenet.node.Node.maxHopsToLive;
	private int splitFileRetries = 5;
	private int splitFileRetryHtlIncrement = 5;
	private int splitFileThreads = 5;
	private int healPercentage = 100;
	private int healingHtl = freenet.node.Node.maxHopsToLive;
	private int maxLog2Size = 0;
	private boolean followContainers = true;
	private boolean doParanoidChecks = false;
	private String splitFileAlgoName = null;
	private boolean autoSplit = true;
	private BackgroundInserter inserter = null;
	private boolean randomSegs = true;

	public final static int MAXNONSPLITSIZE = 1024 * 1024;

	private String tmpDir = null;

	private final Vector listeners = new Vector();

	/**
	 * Creates an AutoRequester that uses the default protocol (FCP) over TCP.
	 * 
	 * @param inetAddress
	 *            The address of the node.
	 */
	public AutoRequester(String inetAddress) throws BadAddressException {
		this(new FCPClient(tcp.getAddress(inetAddress)));
	}

	/**
	 * Creates an AutoRequester that uses an arbitrary cleint backend.
	 * 
	 * @param clientFactory
	 *            The client backend to use when making Freenet requests.
	 */
	public AutoRequester(ClientFactory clientFactory) {
		this.clientFactory = clientFactory;
	}

	/**
	 * Sets whether to create and follow redirects when pertinent. Default is
	 * true.
	 * 
	 * @param val
	 *            New setting.
	 */
	public void doRedirect(boolean val) {
		this.doRedirect = val;
	}

	/**
	 * Sets whether to make DateRedirects to data that is inserted. Default is
	 * false.
	 * 
	 * @param val
	 *            New setting.
	 */
	public void doDateRedirect(boolean val) {
		this.doDateRedirect = val;
	}

	/**
	 * Sets the parameters to use if DateRedirects are used (default are the
	 * default as per the metadat standard).
	 * 
	 * @param offset
	 *            The start time of the redirect calculation.
	 * @param increment
	 *            The amount of time between updates
	 * @param time
	 *            The time to use for URI calculations, in millis of the epoch.
	 */
	public void setDateRedirectOptions(long offset, int increment, long time) {
		this.drOffset = offset;
		this.drIncrement = increment;
		this.drTime = time;
	}

	/**
	 * @param time
	 *            The time to use for URI calculations, in millis of the epoch.
	 */
	public void setDateRedirectTime(long time) {
		this.drTime = time;
	}

	/**
	 * Set the dir used for temp files
	 * 
	 * @param s
	 *            the name of the dir to use for temp files
	 */
	public void setTempDir(String s) {
		tmpDir = s;
	}

	/**
	 * Sets the time for URI calculations to the current time when the request
	 * is made.
	 */
	public void unsetDateRedirectTime() {
		this.drTime = -1;
	}

	// Set true to skip the local DataStore.
	public final void setNonLocal(boolean value) {
		nonLocal = value;
	}

	// Setters for SplitFile options.
	public final void setHandleSplitFiles(boolean value) {
		handleSplitFiles = value;
	}
	public final void setBlockHtl(int value) {
		blockHtl = value;
	}
	public final void setSplitFileRetries(int value) {
		splitFileRetries = value;
	}
	public final void setSplitFileRetryHtlIncrement(int value) {
		splitFileRetryHtlIncrement = value;
	}
	public final void setHealPercentage(int value) {
		healPercentage = value;
	}
	public final void setHealingHtl(int value) {
		healingHtl = value;
	}
	public final void setSplitFileThreads(int value) {
		splitFileThreads = value;
	}
	public final void enableParanoidChecks(boolean value) {
		doParanoidChecks = value;
	}
	public final void setSplitFileAlgoName(String value) {
		splitFileAlgoName = value;
	}
	public final void setAutoSplit(boolean value) {
		autoSplit = value;
	}

	public final void setBackgroundInserter(BackgroundInserter value) {
		inserter = value;
	}
	public final void setRandomSegs(boolean value) {
		randomSegs = value;
	}

	public final void setMaxLog2Size(int sz) {
		maxLog2Size = sz;
	}

	public final void setFollowContainers(boolean f) {
		followContainers = f;
	}
	// Not valid on splitfiles

	/**
	 * Aborts the currently running request as soon as possible. If no request
	 * is currently running calling this causes the *next* request to
	 * immediately abort.
	 */
	public synchronized void abort() {
		// NOTE: The reason aborting has these semantics is
		//       so that you can abort from a thread other
		//       than the one the request is running on without
		//       a race condition, and without having to hold
		//       a lock for the duration of the request.
		//
		//       See SplitFileRequestServlet.doRequest()/abort().
		aborting = true;
		if (currentRequestProcess != null) {
			// REDFLAG:
			// Not all RequestProcess implementations abort
			// immediately. Hunt down and clean up the
			// important ones.
			boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
			if (logDEBUG)
				Core.logger.log(this, "Cancelling current request process", Logger.DEBUG);
			currentRequestProcess.abort();

			if (logDEBUG)
				Core.logger.log(this, currentRequestProcess + ": cancelled " + "current request process", Logger.DEBUG);

			if (currentClient != null) {
				if (logDEBUG)
					Core.logger.log(this, currentClient + ": Cancelling " + "current client", Logger.DEBUG);
				currentClient.cancel();
				if (logDEBUG)
					Core.logger.log(this, currentClient + ": Cancelled " + "current client", Logger.DEBUG);
			}
		}
	}

	/**
     * Clears the abort state so you can be sure the next request won't abort.
     */
	public synchronized void resetAbort() {
		aborting = false;
		currentRequestProcess = null;
		currentClient = null;
	}

	/**
	 * Requests data for the given key from Freenet.
	 * 
	 * @param key
	 *            The key to request
	 * @param data
	 *            The bucket in which to place the resulting data.
	 * @param htl
	 *            Hops to live to give request.
	 * @return true if the request was successful.
	 */
	public boolean doGet(String key, Bucket data, int htl) {
		try {
			return doGet(new FreenetURI(key), data, htl);
		} catch (java.net.MalformedURLException e) {
			error = "Malformed URL: " + e;
			origThrowable = e;
			return false;
		}
	}

	public boolean doGet(FreenetURI key, Bucket data, int htl) {
		return doGet(key, data, htl, false);
	}

	/**
	 * Requests data for the given key from Freenet.
	 * 
	 * @param key
	 *            The key to request
	 * @param data
	 *            The bucket in which to place the resulting data.
	 * @param htl
	 *            Hops to live to give request.
	 * @return true if the request was successful.
	 */
	public boolean doGet(
		FreenetURI key,
		Bucket data,
		int htl,
		boolean justStart) {

		error = "";
		origThrowable = null;
		metadata = null;
		MetadataSettings ms = getSettings();
		return executeProcess(new GetRequestProcess(key, htl, data, new TempBucketFactory(tmpDir), 0, doRedirect, ms), justStart);
	}

	/**
     * Puts data for the given key into Freenet.
     * 
     * @param key
     *            The key to attempt to insert
     * @param data
     *            A bucket containing the data
     * @param htl
     *            Hops to live to give request.
     * @param contentType
     *            The content type to label the data as.
     * @return true if the request was successful.
     */
	public boolean doPut(
		String key,
		Bucket data,
		int htl,
		String contentType) {
		try {
			return doPut(new FreenetURI(key), data, htl, contentType);
		} catch (java.net.MalformedURLException e) {
			error = "Malformed key URI" + e;
			origThrowable = e;
			return false;
		}
	}

	public boolean doPut(
		FreenetURI key,
		Bucket data,
		int htl,
		String contentType) {
		return doPut(key, data, htl, contentType, false);
	}

	/**
	 * Puts data for the given key into Freenet.
	 * 
	 * @param key
	 *            The key to attempt to insert
	 * @param data
	 *            A bucket containing the data
	 * @param htl
	 *            Hops to live to give request.
	 * @param contentType
	 *            The content type to label the data as.
	 * @return true if the request was successful.
	 */
	public boolean doPut(
		FreenetURI key,
		Bucket data,
		int htl,
		String contentType,
		boolean justStart) {

		try {
			error = "";
			origThrowable = null;
			metadata = null;
			MetadataSettings ms = getSettings();

			Metadata metadata = new Metadata(ms);

			metadata = addRedirect(key, metadata);

			// NOTE: PutRequestProcess should automagically
			//       handle inserting large SplitFile metadata
			//       under a CHK redirect.
			//
			// Support automatic spliting of large files.
			if (autoSplit && (data.size() > MAXNONSPLITSIZE)) {
				DocumentCommand splitFile = new DocumentCommand(metadata);
				splitFile.addPart(new SplitFile());
				metadata = new Metadata(ms);
				metadata.addCommand(splitFile);
			}

			DocumentCommand mdc = metadata.getDefaultDocument();
			if (mdc == null) {
				mdc = new DocumentCommand(ms);
				metadata.addDocument(mdc);
			}
			mdc.addPart(new InfoPart("file", contentType));

			return executeProcess(new PutRequestProcess(key, htl, "Rijndael", metadata, metadata.getSettings(), data, new TempBucketFactory(tmpDir),
                    0, true), justStart);
		} catch (Throwable e) {
			error = "Internal error preparing insert metadata: " + e;
			origThrowable = e;
			return false;
		}
	}

	/**
	 * Inserts a set of buckets under a map file. Each file is inserted with as
	 * a redirect in the map file, named after the name of the Bucket
	 * 
	 * @see freenet.support.Bucket#getName()
	 * @param key
	 *            The key to the mapfile
	 * @param data
	 *            A list of Buckets containing the data.
	 * @param htl
	 *            The hops to live.
	 * @return true if the request was successful.
	 */
	public boolean doPutSite(String key, Bucket[] data, int htl) {
		try {
			return doPutSite(new FreenetURI(key), data, htl);
		} catch (java.net.MalformedURLException e) {
			error = "Malformed URL: " + e;
			origThrowable = e;
			return false;
		}
	}

	/**
	 * Inserts a set of buckets under a map file. Each file is inserted with as
	 * a redirect in the map file, named after the name of the Bucket
	 * 
	 * @see freenet.support.Bucket#getName()
	 * @param key
	 *            The key to the mapfile
	 * @param data
	 *            A list of Buckets containing the data.
	 * @param htl
	 *            The hops to live.
	 * @return true if the request was successful.
	 */
	public boolean doPutSite(FreenetURI key, Bucket[] data, int htl) {
		MetadataSettings ms = getSettings();
		return updateSite(key, data, htl, new Metadata(ms));
	}

	/**
	 * Update an old map with files that have changed.
	 * 
	 * @param key
	 *            The key to the mapfile.
	 * @param data
	 *            A list of of Buckets containing the data of exactly those
	 *            documents that have changed (and any new documents to add).
	 * @param htl
	 *            The hops to live.
	 * @param oldMap
	 *            The old map file, as returned from getMetadata() after it was
	 *            last inserted.
	 * @return true if the request was successful.
	 */
	public boolean updateSite(
		String key,
		Bucket[] data,
		int htl,
		Metadata oldMap) {
		try {
			return updateSite(new FreenetURI(key), data, htl, oldMap);
		} catch (java.net.MalformedURLException e) {
			error = "Malformed URL: " + e;
			origThrowable = e;
			return false;
		}
	}
	/**
	 * Update an old map with files that have changed.
	 * 
	 * @param key
	 *            The key to the mapfile.
	 * @param data
	 *            A list of of Buckets containing the data of exactly those
	 *            documents that have changed (and any new documents to add).
	 * @param htl
	 *            The hops to live.
	 * @param oldMap
	 *            The old map file, as returned from getMetadata() after it was
	 *            last inserted.
	 * @return true if the request was successful.
	 */
	public boolean updateSite(
		FreenetURI key,
		Bucket[] data,
		int htl,
		Metadata oldMap) {
		try {
			error = "";
			origThrowable = null;
			metadata = null;

			MetadataSettings settings = getSettings();

			for (int i = 0; i < data.length; i++) {
				//  buckets[0] = new FileBucket(new File(p.getArg(i)));
				String name = data[i].getName();

				if (metadata.getDocument(name) == null) {
					DocumentCommand dc = new DocumentCommand(settings, name);
					dc.addPart(new Redirect(new FreenetURI("CHK@")));
					String type = MimeTypeUtils.getExtType(name);
					if (type != null)
						dc.addPart(new InfoPart("file", type));
					metadata.addCommand(dc);
				}
			}

			Metadata qual = addRedirect(key, metadata);

			boolean success = executeProcess(new PutSiteProcess(key, htl, "Rijndael", qual, data, new TempBucketFactory(tmpDir)));
			// we want to make sure it's the map not the doc nfo

			return success;

		} catch (Throwable e) {
			error = "Internal error preparing insert metadata: " + e;
			origThrowable = e;
			return false;
		}
	}

	/**
	 * Calculate the CHK value of a piece of the data. No metadata will be
	 * included, as it is generally a bad idea to use metadata with CHKs
	 * anyways.
	 * 
	 * @param data
	 *            The data.
	 * @return true if the calculation was successful.
	 */
	public boolean doComputeCHK(Bucket data) {
		return executeProcess(
			new ComputeCHKProcess(
				"Rijndael",
				null,
				new MetadataSettings(),
				data));
	}

	/**
	 * Calculate the CHK value of a piece of the data. Only content type
	 * metadata is included.
	 * 
	 * @param data
	 *            The data.
	 * @param contentType
	 *            The content type to label the data as.
	 * @return true if the calculation was successful.
	 */
	public boolean doComputeCHK(Bucket data, String contentType) {
	    try {
		MetadataSettings ms = getSettings();
		Metadata metadata = new Metadata(ms);

		DocumentCommand mdc = metadata.getDefaultDocument();
		if (mdc == null) {
		    mdc = new DocumentCommand(ms);
		    metadata.addDocument(mdc);
		}
		mdc.addPart(new InfoPart("file", contentType));

		RequestProcess process =
		    new ComputeCHKProcess("Rijndael",
					  metadata,
					  metadata.getSettings(),
					  data);
		return executeProcess(process);

	    } catch (Throwable e) {
		error = "Internal error preparing metadata: " + e;
		origThrowable = e;
		return false;
	    }
	}

	/**
	 * Generate an SVK key pair.
	 * 
	 * @return { privateKey, publicKey }
	 */
	public String[] generateSVKPair() {
		ComputeSVKPairProcess rp = new ComputeSVKPairProcess(null);
		boolean success = executeProcess(rp);
		if (success)
			return new String[] { rp.getPrivateKey(), rp.getPublicKey()};
		else
			throw new RuntimeException("Fatal error generating keys: " + error);
	}

	/**
	 * If a request fails, this will return an error string.
	 * 
	 * @return Any error string generated during the last request.
	 */
	public String getError() {
		return error;
	}

	/**
	 * If a request fails, this will return an error Throwable
	 * 
	 * @return Any error exception generated during the last request.
	 */
	public Throwable getThrowable() {
		return origThrowable;
	}

	/**
	 * If a request is successful this will return the metadata.
	 * 
	 * @return Any metadata generated during the last request.
	 */
	public Metadata getMetadata() {
		return metadata;
	}

	/**
	 * If a request is successful, this will return the final key value.
	 * 
	 * @return The final key value genertaed from the last request.
	 */
	public FreenetURI getKey() {
		return keyUri;
	}

	/**
	 * Add a listener which receives events from all intermediate requests
	 * executed by the AutoRequester.
	 */
	public void addEventListener(ClientEventListener cel) {
		if (!listeners.contains(cel)) {
			listeners.addElement(cel);
		}
	}

	/**
	 * Removes a listener.
	 */
	public boolean removeEventListener(ClientEventListener cel) {
		boolean b = listeners.removeElement(cel);
		listeners.trimToSize();
		return b;
	}

	// Returns a MetadataSettings object set up for this
	public MetadataSettings getSettings() {
		MetadataSettings ms = new MetadataSettings();
		if (drTime >= 0)
			ms.setCurrentTime(drTime);

		ms.setNonLocal(nonLocal);
		ms.setHealPercentage(healPercentage);
		ms.setHealingHtl(healingHtl);

		// Set SplitFile params.
		ms.setHandleSplitFiles(handleSplitFiles);
		ms.setSplitFileAlgoName(splitFileAlgoName);
		ms.setBlockHtl(blockHtl);
		ms.setSplitFileRetryHtlIncrement(splitFileRetryHtlIncrement);
		ms.setSplitFileRetries(splitFileRetries);
		ms.setSplitFileThreads(splitFileThreads);
		ms.setClientFactory(clientFactory);
		ms.enableParanoidChecks(doParanoidChecks);
		ms.setBackgroundInserter(inserter);
		ms.setRandomSegs(randomSegs);
		ms.setMaxLog2Size(maxLog2Size);
		ms.setFollowContainers(followContainers);

		return ms;
	}

	// Adds the pertinent redirect to inserts.
	private Metadata addRedirect(FreenetURI key, Metadata metadata)
		throws InvalidPartException {

		if (!key.getKeyType().equals("CHK")
			&& (doRedirect || doDateRedirect)) {

			DocumentCommand redirect =
				new DocumentCommand(metadata.getSettings());
			try {

				// CONFUSED: Does a DateRedirect also go through
				//           a CHK or will this break if you try to
				//           DBR to data > 32k?

				redirect.addPart(
					doDateRedirect
						? new DateRedirect(
							drIncrement,
							drOffset,
							(drTime >= 0 ? drTime : System.currentTimeMillis())
								/ 1000,
							key)
						: new Redirect(new FreenetURI("CHK@")));
			} catch (java.net.MalformedURLException e) {
				throw new RuntimeException("Error in URI code: " + e);
			}
			metadata = new Metadata(metadata.getSettings());
			metadata.addCommand(redirect);
		}

		return metadata;
	}

	private final void addListeners(SimpleEventProducer p) {
		if (p == null) {
			return;
		}

		for (int i = 0; i < listeners.size(); i++) {
			p.addEventListener((ClientEventListener) listeners.elementAt(i));
		}
	}

	private final void removeListeners(SimpleEventProducer p) {
		if (p == null) {
			return;
		}

		for (int i = 0; i < listeners.size(); i++) {
			p.removeEventListener((ClientEventListener) listeners.elementAt(i));
		}
	}

	Request curRequest;
	Request prevRequest;

	private boolean startProcess(RequestProcess rp) {
		synchronized (this) {
			if (aborting) {
				error = "The request was aborted.";
				origThrowable = new Exception(error);
				return false;
			}
			currentRequestProcess = rp;
			if (Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(this, "Starting process: " + rp + " for " + this, Logger.DEBUG);
			currentClient = null;
			aborting = false;
		}

		curRequest = null;
		prevRequest = null;
		return true;
	}

	// FIXME: ABANDON HOPE ALL YE WHO ENTER HERE!

	// This could be made smarter.

	private boolean executeProcess(RequestProcess rp) {
		return executeProcess(rp, false);
	}

	private final Object waitForFinish = new Object();
	private boolean requestSucceeded;

	/**
	 * Execute a RequestProcess, normally blocking
	 * 
	 * @param justStart
	 *            if true, just start the request and return true (if it
	 *            succeeds immediately we also return true)
	 * @return whether we successfully executed the request (or successfully
	 *         started it if justStart == true)
	 */
	private boolean executeProcess(RequestProcess rp, boolean justStart) {
		if (!startProcess(rp))
			return false;

		finished = false;

		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);

		if (logDEBUG)
			Core.logger.log(this, "executeProcess() on " + this + " at " + System.currentTimeMillis(), Logger.DEBUG);
		curRequest = rp.getNextRequest();
		if (logDEBUG)
			Core.logger.log(this, "Got request on " + this + " at " + System.currentTimeMillis(), Logger.DEBUG);

		if (curRequest != null) {
			try {
				if (logDEBUG)
					Core.logger.log(this, "Trying to handle next request (" + curRequest + ") for " + this + " at " + System.currentTimeMillis(), Logger.DEBUG);
				if (!handleNextRequest())
					return false;
				if (logDEBUG)
					Core.logger.log(this, "Handled next request (" + curRequest + ") for " + this + " at " + System.currentTimeMillis(), Logger.DEBUG);
			} catch (Throwable e) {
				removeListeners(prevRequest);
				removeListeners(curRequest);
				error = "Request failed due to error: " + e;
				origThrowable = e;
				if (logDEBUG)
					Core.logger.log(this, "RequestProcess: " + rp + " failed with error: " + e + " for " + this, e, Logger.DEBUG);
				return false;
			}

			if (!justStart) {
				synchronized (waitForFinish) {
					try {
						if (logDEBUG)
							Core.logger.log(this, "Waiting for finish: " + this, Logger.DEBUG);
						while (!finished) {
							waitForFinish.wait(200);
						}
						if (logDEBUG)
							Core.logger.log(this, "Waited for finish: " + this, Logger.DEBUG);
					} catch (InterruptedException e) {
						if (logDEBUG)
							Core.logger.log(this, "Interrupted wait for finish: " + this, Logger.DEBUG);
					}
				}
			} else {
				if (logDEBUG)
					Core.logger.log(this, "Returning true because justStart for " + this, Logger.DEBUG);
				return true;
			}
		} else {
			if (logDEBUG)
				Core.logger.log(this, "curRequest == null in executeProcess: " + this, Logger.DEBUG);
		}

		return endValue(rp);
	}

	private boolean endValue(RequestProcess rp) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(this, "endValue(" + rp + ") on " + this, Logger.DEBUG);
		if (rp == null)
			throw new IllegalArgumentException("null rp for " + this);
		if (rp.failed()) {
			if (logDEBUG)
				Core.logger.log(this, "RequestProcess: " + rp + " failed gracefully: " + rp.getError() + " (" + rp.getThrowable() + ")"
                        + rp.getThrowable() + " for " + this, Logger.DEBUG);
			error = "Request failed gracefully: " + rp.getError();
			origThrowable = rp.getThrowable();
			return false;
		} else {
			if (logDEBUG)
				Core.logger.log(this, "RequestProcess: " + rp + " succeeded for " + this + ", returning true", Logger.DEBUG);
			keyUri = rp.getURI();
			metadata = rp.getMetadata();
			return true;
		}
	}

	private boolean handleNextRequest() throws Throwable {
		// REDFLAG: The add/remove listeners
		//          hack only works if none of the
		//          requests spawn threads.
		// Hmmm... I think I put this hack in.
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"handleNextRequest() on " + this,
				Logger.DEBUG);
		removeListeners(prevRequest);
		prevRequest = curRequest;
		if (!clientFactory.supportsRequest(curRequest.getClass())) {
			currentRequestProcess.abort();
			error =
				("A request needed to be made "
					+ " that was not supported by the "
					+ "current protocol");
			Core.logger.log(this, error + ": " + this, Logger.NORMAL);
			origThrowable = new Exception(error);
			return false;
		}
		Client c = clientFactory.getClient(curRequest);
		addListeners(curRequest);
		curRequest.addEventListener(new AutoListener());
		synchronized (this) {
			if (aborting) {
				error = "The request was aborted.";
				origThrowable = new Exception(error);
				currentClient = null;
				aborting = false;
				aborting = false;
				Core.logger.log(this, error + ": " + this, Logger.NORMAL);
				return false;
			}
			currentClient = c;
		}
		Core.logger.log(this, "Starting " + c + " for " + this, Logger.DEBUG);
		c.start();
		return true;
	}

	volatile boolean finished = false;

	private void doFinish(boolean success) {
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, "doFinish(" + success + ") for " + this, Logger.DEBUG);
		requestSucceeded = success;
		finished = true;
		synchronized (waitForFinish) {
			waitForFinish.notifyAll();
		}
	}

	private void cleanFinish() {
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, "Cleanly Finished Request " + this, Logger.DEBUG);

		RequestProcess rp = currentRequestProcess;

		synchronized (this) {
			currentRequestProcess = null;
			currentClient = null;
			aborting = false;
		}

		doFinish(endValue(rp));
	}

	private void finishWithThrowable(Throwable t) {
		removeListeners(prevRequest);
		removeListeners(curRequest);
		error = "Request failed due to error: " + t;
		origThrowable = t;
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, "RequestProcess: " + currentRequestProcess + " failed with error: " + t + " for " + this, t, Logger.DEBUG);
		doFinish(false);
	}

	private void onReachedState(StateReachedEvent sr) {
	    boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(this, "Got StateReachedEvent: " + sr.getDescription() + " for " + this, Logger.DEBUG);
		if (currentRequestProcess == null) {
			Core.logger.log(this, "Got StateReachedEvent (" + sr.getDescription() + ") with currentRequestProcess == null! for " + this, new Exception("debug"), Logger.ERROR);
		} else {
			curRequest = currentRequestProcess.getNextRequest();
			if(logDEBUG)
			    Core.logger.log(this, "Next request: "+curRequest, Logger.DEBUG);
			if (curRequest != null) {
				boolean b = false;
				try {
					b = handleNextRequest();
				} catch (Throwable t) {
					finishWithThrowable(t);
					return;
				}
				if (!b) {
					cleanFinish();
				}
			} else {
				cleanFinish();
			}
		}
	}

	public String toString() {
		StringBuffer s = new StringBuffer(super.toString());
		s.append(':');
		RequestProcess rp = currentRequestProcess;
		if (rp != null) {
			s.append(rp.toString());
		} else {
			s.append("(not requesting)");
		}
		if (error != null)
			s.append('(').append(error).append(')');
		if (keyUri != null)
			s.append(':').append(keyUri.toString());
		return s.toString();
	}

	private class AutoListener extends DoneListener {
		public void receive(ClientEvent ce) {
			if (Core.logger.shouldLog(Logger.DEBUG, this)) {
				Core.logger.log(
					this,
					"Received event: "
						+ ce.getDescription()
						+ " for "
						+ toString(),
					new Exception("debug"),
					Logger.DEBUG);
			}
			if (ce instanceof ErrorEvent || ce instanceof ExceptionEvent)
			    Core.logger.log(this, "Received: "+ce.getDescription(), Logger.ERROR);
			super.receive(ce);
		}

		protected void onDone(StateReachedEvent sr) {
			AutoRequester.this.onReachedState(sr);
		}

		public String toString() {
			return AutoListener.this.getClass().getName()
				+ " for "
				+ AutoRequester.this.toString();
		}
	}
}
