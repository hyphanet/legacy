/*
 * This code is part of fproxy, an HTTP proxy server for Freenet. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

package freenet.client.http;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.Vector;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import freenet.Core;
import freenet.Version;
import freenet.client.ClientFactory;
import freenet.client.cli.CLIClientFactory;
import freenet.client.cli.CLIException;
import freenet.config.Config;
import freenet.config.Params;
import freenet.config.Setup;
import freenet.interfaces.Interface;
import freenet.interfaces.LocalInterface;
import freenet.interfaces.Service;
import freenet.interfaces.ServiceException;
import freenet.interfaces.servlet.SingleHttpServletContainer;
import freenet.node.Node;
import freenet.support.LoggerHookChain;
import freenet.support.Fields;
import freenet.support.FileLoggerHook;
import freenet.support.Loader;
import freenet.support.Logger;
import freenet.thread.FastThreadFactory;
import freenet.thread.ThreadFactory;

/**
 * App to run HttpServlets in a separate JVM from the node.
 * <p>
 * Attribution: Most of this code was pillaged from Freenet.node.Main.
 * <p>
 * 
 * @author <a href="mailto:giannijohansson@mediaone.net">Gianni Johansson</a>
 */
public class HttpServletRunner {

	public static final String[] defaultRCfiles =
		new String[] { "freenet.conf", "freenet.ini", ".freenetrc" };

	private static final Config switches = new Config();
	static {
		switches.addOption("help", 'h', 0, null, 10);
		switches.addOption("version", 'v', 0, null, 11);
		switches.addOption("manual", 0, null, 12);
		switches.addOption("paramFile", 'p', 1, null, 41);

		switches.addOption("paramFile", 'p', 1, null, 41);

		switches.addOption("logLevel", 1, "debug", 50);
		switches.addOption("externalServicesLogFile", 1, "NO", 60);
		switches.addOption("logFormat", 1, "m", 70);
		switches.addOption("logDate", 1, "", 80);
		switches.addOption(
			"clientFactory",
			1,
			"freenet.client.cli.CLIFCPClient",
			90);

		switches.shortDesc("help", "prints this help message");
		switches.shortDesc("version", "prints out version info");
		switches.shortDesc("manual", "prints a manual in HTML");

		switches.argDesc("paramFile", "<file>");
		switches.shortDesc(
			"paramFile",
			"path to a config file in a non-default location");

		switches.argDesc("clientFactory", "<class name>");
		switches.shortDesc(
			"clientFactory",
			"CLIClientFactory used to connect to the node.");

		switches.argDesc("externalServicesLogFile", "<file>");
		switches.shortDesc(
			"externalServicesLogFile",
			"Log file name. Use NO to dump log to stderr.");

	}

	/**
	 * Run all HttpServlets in the <code>externalServices</code> section of
	 * the config file in their own JVM
	 */
	static void main(String[] args) {
		try {
			// process command line
			Params sw = new Params(switches.getOptions());
			sw.readArgs(args);
			if (sw.getParam("help") != null) {
				usage(sw);
				return;
			}
			if (sw.getParam("version") != null) {
				version();
				return;
			}
			if (sw.getParam("manual") != null) {
				manual(sw);
				return;
			}

			Params params = new Params(switches.getOptions());

			// attempt to load config file
			String paramFile = sw.getParam("paramFile");
			try {
				if (paramFile == null)
					params.readParams(defaultRCfiles);
				else
					params.readParams(paramFile);
			} catch (FileNotFoundException e) {
				if (sw.getParam("config") == null) {
					if (paramFile == null) {
						System.err.println(
							"Couldn't find any of the following configuration files:");
						System.err.println(
							"    " + Fields.commaList(defaultRCfiles));
					} else {
						System.err.println(
							"Couldn't find configuration file: " + paramFile);
					}
					return;
				}
			}

			params.readArgs(args);
			// I want config after readArgs, which must be after readParams
			// which mandates the hack in the catch block above.
			if (sw.getParam("config") != null) {
				try {
					Setup set =
						new Setup(
							System.in,
							System.out,
							new File(sw.getString("config")),
							false,
							params);
					set.dumpConfig();
				} catch (IOException e) {
					System.err.println("Error while creating config: " + e);
				}
				return;
			}

			// Setup logging.
			Logger log = createLogger(params);
			Core.logger = log;

			log.log(
				HttpServletRunner.class,
				"The logger was created.",
				Logger.DEBUG);

			// Need to make a client factory
			ClientFactory clientFactory = createClientFactory(params, log);

			ThreadFactory tf = new FastThreadFactory(null, 100);

			// Set load and initialize all external services.
			Vector iv = new Vector();
			String[] services = params.getList("externalServices");
			if (services != null) {
				for (int i = 0; i < services.length; ++i) {
					System.err.println("loading service: " + services[i]);
					Params fs = (Params) params.getSet(services[i]);
					if (fs == null) {
						log.log(
							HttpServletRunner.class,
							"No configuration parameters found for: "
								+ services[i],
							Logger.ERROR);
						continue;
					}
					try {
						Service svc =
							loadHttpServletService(fs, clientFactory, log);
						iv.addElement(
							LocalInterface.make(
								fs,
								tf,
								svc,
								Node.dontLimitClients,
								Node.maxThreads / 6,
								Node.maxThreads / 4));
					}

					//catch (Exception e) {
					// Need to catch link errors.
					catch (Throwable e) {
						log.log(
							HttpServletRunner.class,
							"Failed to load service: " + services[i],
							e,
							Logger.ERROR);
					}
				}
			}

			if (iv.size() == 0) {
				log.log(
					HttpServletRunner.class,
					"No external services could be initialized.",
					Logger.NORMAL);
				log.log(HttpServletRunner.class, "EXITING.", Logger.NORMAL);
				System.exit(-1);
			}

			Interface[] interfaces = new Interface[iv.size()];
			iv.copyInto(interfaces);
			Thread[] interfaceThreads = new Thread[interfaces.length];

			log.log(
				HttpServletRunner.class,
				"Starting interfaces..",
				Logger.NORMAL);
			int i;
			for (i = 0; i < interfaces.length; ++i) {
				interfaceThreads[i] =
					new Thread(interfaces[i], interfaces[i].toString());
				if (interfaceThreads[i] == null) {
					log.log(
						HttpServletRunner.class,
						"Ran out of threads.",
						Logger.NORMAL);
					log.log(HttpServletRunner.class, "EXITING.", Logger.NORMAL);
					System.exit(-1);
				}
			}

			for (i = 0; i < interfaceThreads.length; ++i) {
				interfaceThreads[i].setDaemon(true);
				interfaceThreads[i].start();
			}

			//tm.run(); // blocks
			// REDFLAG: TODO FIXME: User controlled shutdown
			// Use whatever mechanism tavin comes up with for
			// runtime node admin???

			System.exit(0);
		} catch (Exception e) {
			System.err.println("HttpServletRunner FAILED: " + e);
			e.printStackTrace();
			System.exit(-1);
		}
	}

	private static final Logger createLogger(Params params) {
		// set up runtime logging
		String thresh = params.getString("logLevel");

		String fname = params.getString("externalServicesLogFile");
		String logFormat = params.getString("logFormat");
		String logDate = params.getString("logDate");

		LoggerHookChain log = new LoggerHookChain(thresh);
		try {
			FileLoggerHook lh;
			if (!fname.equalsIgnoreCase("NO"))
				lh = 
					new FileLoggerHook(
						fname,
						logFormat,
						logDate,
						thresh,
						false,
						false);
			else
				lh = new FileLoggerHook(System.err, logFormat, logDate, thresh);
				lh.start();
			log.addHook(lh);
		} catch (IOException e) {
			System.err.println("Opening log file failed!");
		}
		return log;
	}

	public static CLIClientFactory createClientFactory(
		Params params,
		Logger logger)
		throws CLIException {

		params.addOptions(switches.getOptions());
		String cfactory = params.getString("clientFactory");
		try {
			Object o =
				Loader.getInstance(
					cfactory,
					new Class[] { Params.class, Logger.class },
					new Object[] { params, logger });
			if (!(o instanceof CLIClientFactory)) {
				throw new CLIException("Unsupported client:" + cfactory);
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
			throw new CLIException(
				"Client " + cfactory + " threw error:" + t.getMessage());

		} catch (NoSuchMethodException e) {
			throw new CLIException("Client " + cfactory + " not supported");
		} catch (InstantiationException e) {
			throw new CLIException(
				"Could not instantiate " + cfactory + " :" + e);
		} catch (IllegalAccessException e) {
			throw new CLIException("Access to " + cfactory + " illegal.");
		} catch (ClassNotFoundException e) {
			throw new CLIException("No such client: " + cfactory);
		}
	}

	private static Service loadHttpServletService(
		Params fs,
		ClientFactory factory,
		Logger logger)
		throws IOException, ServiceException {
		Service service;

		String className = fs.getString("class");
		if (className == null || className.trim().length()==0)
			throw new ServiceException("No class given");
		Class cls;
		try {
			cls = Class.forName(className.trim());
		} catch (ClassNotFoundException e) {
			throw new ServiceException("" + e);
		}

		if (Servlet.class.isAssignableFrom(cls)) {
			if (HttpServlet.class.isAssignableFrom(cls))
				service =
					new SingleHttpServletContainer(logger, factory, cls, true);
			else
				throw new RuntimeException("I'm too dumb for: " + cls);
		} else {
			throw new RuntimeException(
				"Only HttpServlets are supported: " + cls);
		}

		Config serviceConf = service.getConfig();
		if (serviceConf != null) {
			Params params;
			if (fs.getSet("params") != null) { // read from Params
				params =
					new Params(serviceConf.getOptions(), fs.getSet("params"));
			} else if (fs.getString("params") != null) { // or external file
				params = new Params(serviceConf.getOptions());
				params.readParams(fs.getParam("params"));
			} else {
				params = new Params(serviceConf.getOptions());
			}
			service.init(params, "main-runner");
		}

		return service;
	}

	/**
	 * Print version information
	 */
	public static void version() {
		System.out.println(
			Version.nodeName
				+ " version "
				+ Version.nodeVersion
				+ ", protocol version "
				+ Version.protocolVersion
				+ " (build "
				+ Version.buildNumber
				+ ", last good build "
				+ Version.lastGoodBuild
				+ ")");
	}

	/**
	 * Print usage information
	 */
	public static void usage(Params params) {
		// REDFLAG: update
		version();
		System.out.println(
			"Usage: java freenet.client.http.HttpSerlvetRunner [options]");
		System.out.println("");
		//          System.out.println("Configurable options");
		//          System.out.println("--------------------");
		//          Node.config.printUsage(System.out);
		//          System.out.println("");
		System.out.println("Command-line switches");
		System.out.println("---------------------");
		switches.printUsage(System.out);
		System.out.println("");
		System.out.println("ClientFactory specific Options");
		System.out.println("---------------------");
		Config opt = clientFactoryOptions(params);
		if (opt != null) {
			opt.printUsage(System.out);
		} else {
			System.out.println("Couldn't load ClientFactory!");
		}
		System.out.println(
			"Send support requests to support@freenetproject.org.");
		System.out.println("Bug reports go to devl@freenetproject.org.");
	}

	/**
	 * Get options for the ClientFactory
	 */
	public static Config clientFactoryOptions(Params params) {
		try {
			// Need to make a client factory
			CLIClientFactory clientFactory =
				createClientFactory(params, new LoggerHookChain());
			return clientFactory.getOptions();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Print HTML manual
	 */
	public static void manual(Params params) {
		PrintStream out = System.out;
		out.println("<html><body>");
		out.println("<br /><br />");
		out.println(
			"<h2>Freenet External HttpServlet Runner  Documentation</h2>");
		out.println(
			"<h3>" + Config.htmlEnc(Version.getVersionString()) + "</h3>");
		out.println("<br />");
		java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance();
		out.println(
			"<i>(This manual was automatically generated by the "
				+ "--manual switch (see below) on "
				+ Config.htmlEnc(df.format(new Date()))
				+ ". If you have updated Freenet since then, you "
				+ "may wish regenerate it.)</i>");
		out.println("<br /><br />");
		out.println(
			"HttpServletRunner is a command line tool for "
				+ "running a collection of HttpServlets in a separate "
				+ "JVM from FRED. Having servlets run in an external JVM "
				+ "keeps bugs in servlets from affecting the stability of FRED "
				+ "and thus degrading the robustness of the network.");
		out.println("<br /><br />");
		out.println(
			"See the <a href=\"http://www.freenetproject.org/"
				+ "index.php?page=documentation\"> project documentation"
				+ " pages</a> for more information, or ask pointed & "
				+ " specific questions on the <a href=\""
				+ "http://www.freenetproject.org/index.php?page=lists\">"
				+ "mailing lists</a>.");
		out.println("<br /><br />");
		out.println("<br />");
		out.println("<h3>Command line switches: </h3>");
		out.println("<hr></hr>");
		switches.printManual(out);
		out.println("<h3>ClientFactory specific Options: </h3>");
		out.println("<hr></hr>");
		Config opt = clientFactoryOptions(params);
		if (opt != null) {
			opt.printManual(System.out);
		} else {
			System.out.println("Couldn't load ClientFactory!");
		}
		out.println("<hr></hr>");
		out.println("</body></html>");
	}
}
