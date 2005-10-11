package freenet.interfaces.servlet;

import java.util.Iterator;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;

import freenet.Core;
import freenet.client.ClientFactory;
import freenet.config.Config;
import freenet.config.Params;
import freenet.interfaces.ServiceException;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.QuickSorter;

/**
 * A container that can cope with multiple contexts
 * and multiple servlets within each context.
 * @author tavin
 */
public class MultipleHttpServletContainer extends HttpServletContainer {

	private static final Config config = new Config();
	private boolean initFirst;
	private static boolean logDebug = true;
	static {
		// hm, there is really no good to come from defining these until
		// there is better support for FieldSets in the config lib
	}

	// data containers for looking up servlets by URI

	/**
	 * For ordering URI path segments longest-first.
	 */
	private static abstract class PathData implements Comparable {
		final String path;
		final String method;
		PathData(String path, String method) {
			this.path = path;
			this.method = method;
		}
		public final int compareTo(Object o) {
			return compareTo((PathData) o);
		}
		public final int compareTo(PathData o) {
			return path.length() == o.path.length()
				? 0
				: (path.length() < o.path.length() ? 1 : -1);
		}
		public String toString() {
			return method + ":" + path;
		}
	}

	private static final class ServletData extends PathData {
		final ServletPool pool;
		ServletData(String path, String method, ServletPool pool) {
			super(path, method);
			this.pool = pool;
		}
	}

	private static final class ContextData extends PathData {
		final ServletData[] servletData;
		ContextData(String path, String method, ServletData[] servletData) {
			super(path, method);
			this.servletData = servletData;
		}
		public String toString() {
			String s = method + ":" + path + servletData.length;
			for (int x = 0; x < servletData.length; x++) {
				s += x + ":" + servletData[x] + "\n";
			}
			return s;
		}
	}

	// context data tuples ordered longest URI-path first
	private ContextData[] contextData;

	// Optional Servlet which handles only
	// "" and "/"
	private ContextData defaultContext;

	/**
	 * In-process.
	 */
	public MultipleHttpServletContainer(Node node, boolean initFirst) {
		super(node);
		this.initFirst = initFirst;
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
	}

	public MultipleHttpServletContainer(Node node) {
		super(node);
		initFirst = false;
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
	}

	public void setInitFirst(boolean b) {
		initFirst = b;
	}

	/**
	 * Out-of-process.
	 */
	public MultipleHttpServletContainer(
		Logger logger,
		ClientFactory factory,
		boolean initFirst) {
		super(logger, factory);
		this.initFirst = initFirst;
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
	}

	/**
	 * @return  the Config options for initializing this Service
	 *
	 * NOTE:  it's empty right now, since support for sub-FieldSets
	 *        is lacking in the config lib
	 */
	public final Config getConfig() {
		return config;
	}

	/**
	 * Sets up all contexts and servlets according to the config fields.
	 * @throws ServiceException
	 *         if the Class for any servlet was not specified,
	 *         or could not be loaded,
	 *         or if there is a URI collision
	 */
	public void init(Params params, String serviceName)
		throws ServiceException {
		setServiceName(serviceName);
		Params contexts = (Params) params.getSet("context");
		if (contexts == null) {
			contexts = new Params();
			if (params.getSet("servlet") != null) {
				Params defaultContext = new Params();
				defaultContext.put("uri", "");
				defaultContext.put("method", "GET");
				if (params.getSet("params") != null)
					defaultContext.put("params", params.getSet("params"));
				defaultContext.put("servlet", params.getSet("servlet"));
				contexts.put("1", defaultContext);
			}
		}

		// Special case servlet which handles only "" and "/"
		Params defaultCxt = (Params) params.getSet("defaultServlet");
		if (defaultCxt != null) {
			// 
			Params servletFS = new Params();
			Params oneFS = new Params();
			oneFS.put("1", defaultCxt);
			servletFS.put("servlet", oneFS);
			defaultContext = loadContext(servletFS);
		}

		contextData = new ContextData[contexts.size()];
		Iterator cte = contexts.values().iterator();
		for (int i = 0; i < contextData.length; ++i)
			contextData[i] = loadContext((Params) cte.next());

		QuickSorter.quickSort(new ArraySorter(contextData));
	}

	private ContextData loadContext(Params context) throws ServiceException {

		String uri = context.getString("uri");
		if (uri == null)
			uri = "";

		String method = context.getString("method");
		if (method == null)
			method = "GET";

		Params params = (Params) context.getSet("params");
		if (params == null)
			params = new Params();

		Params servlets = (Params) context.getSet("servlet");
		if (servlets == null)
			servlets = new Params();

		ServletContext servletContext = createServletContext(params);

		ServletData[] servletData = new ServletData[servlets.size()];
		Iterator se = servlets.keySet().iterator();
		for (int i = 0; i < servletData.length; ++i) {
			String name = (String) se.next();
            Params nxt = (Params) servlets.getSet(name);
            String servletContextPath =
            	getServiceName() + ".params.servlet." + name;
            servletContext.setAttribute(
            	"freenet.servlet.servletContextPath",
            	servletContextPath);
            Core.logger.log(
            	MultipleHttpServletContainer.class,
            	"Loading the servlet "
            		+ servletContextPath
            		+ " with params "
            		+ nxt,
            	Logger.DEBUG);
            servletData[i] = loadServlet(servletContext, nxt);
		}
		QuickSorter.quickSort(new ArraySorter(servletData));

		return new ContextData(uri, method, servletData);
	}

	private ServletData loadServlet(ServletContext context, Params servlet)
		throws ServiceException {

		String uri = servlet.getString("uri");
		if (uri == null)
			uri = "/";

		String method = servlet.getString("method");
		if (method == null)
			method = "GET";

		String className = servlet.getString("class");
		if (className == null)
			throw new ServiceException(
				"Class not specified for servlet: " + uri);

		Class cls;
		// FIXME: GROSS HACK!
		// Put in because of FUCKINGEVIL winconfig that doesn't write comments
		// where there should be comments
		if(className.equals("freenet.client.http.InsertServlet_"))
		    className = "freenet.client.http.InsertServlet";
		
		try {
			cls = Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw new ServiceException(e.toString());
		}

		String servletName = servlet.getString("name");
		if (servletName == null || servletName.trim().length()==0)
			servletName = cls.getName();

		Params params = (Params) servlet.getSet("params");
		if (params == null)
			params = new Params();

		ServletConfig config =
			createServletConfig(context, servletName, params);
		ServletPool pool = new SimpleServletPool(cls, context, config);

		return new ServletData(uri, method, pool);
	}

	public final String getContextPath(String uripath, String method) {
		ContextData contextData = findContext(uripath, method);
		return contextData == null ? null : contextData.path;
	}

	public final String getServletPath(String uripath, String method) {
		ContextData contextData = findContext(uripath, method);
		if (contextData != null) {
			ServletData servletData = findServlet(contextData, uripath, method);
			if (servletData != null)
				return servletData.path;
		}
		return null;
	}

	public final String getPathInfo(String uripath, String method) {
		ContextData contextData = findContext(uripath, method);
		if (contextData != null) {
			ServletData servletData = findServlet(contextData, uripath, method);
			if (servletData != null) {
				int n = contextData.path.length() + servletData.path.length();
				if (uripath.length() > n)
					return uripath.substring(n, uripath.length());
			}
		}
		return null;
	}

	public final ServletContext getContext(String uripath, String method) {
		return getServletPool(uripath, method).getServletContext();
	}

	protected final ServletPool getServletPool(String uripath, String method) {
		Core.logger.log(
			this,
			"getServletPool for " + method + " " + uripath,
			new Exception("debug"),
			Logger.DEBUG);
		ContextData contextData = findContext(uripath, method);
		if (contextData != null) {
			// 	    Core.logger.log(this, "contextData available for "+method+" "+
			// 			    uripath, Logger.DEBUG);
			ServletData servletData = findServlet(contextData, uripath, method);
			if (servletData != null) {
				// 		Core.logger.log(this, "servletData available for "+method+" "+
				// 				uripath, Logger.DEBUG);
				return servletData.pool;
			}
		}
		// 	Core.logger.log(this, "Cannot find ServletPool for "+method+" "+uripath,
		// 			Logger.DEBUG);
		return null;
	}

	private ContextData findContext(String uripath, String method) {
		//System.err.println("uripath: [" + uripath +"]");
		//System.err.println("findContext:- uripath: [" + uripath + "] method: [" + method + "] len: " + contextData.length);
		// 	Core.logger.log(this, "Trying to findContext for "+method+" "+uripath,
		// 			Logger.DEBUG);
		if ((defaultContext != null)
			&& (uripath.length()==0 || uripath.equals("/"))) {
			// Special case Servlet handles no URL
			// 	    Core.logger.log(this, "Returning defaultContext",
			// 			    Logger.DEBUG);
			return defaultContext;
		}

		for (int i = 0; i < contextData.length; ++i) {
			/*System.err.println("  trying: contextData[].path: [" + contextData[i].path  + "] method: [" + contextData[i].method + "]");
			    System.err.println("          method: " + contextData[i].method);
			System.err.println("          startsWith: " + uripath.startsWith(contextData[i].path));
			System.err.println("          equalsIgnoreCase(method): " + contextData[i].method.equalsIgnoreCase(method)); 
			System.err.println("          equalsIgnoreCase(BOTH): " + contextData[i].method.equalsIgnoreCase("BOTH")); */
			// 	    Core.logger.log(this, "Trying "+contextData[i].path+"("+i+
			// 			    ") against "+method+" "+uripath, 
			// 			    Logger.DEBUG);
			if (uripath.startsWith(contextData[i].path)
				/*&&
			(contextData[i].method.equalsIgnoreCase(method) ||
			contextData[i].method.equalsIgnoreCase("BOTH"))*/
				) {
				// 		Core.logger.log(this, "Returning "+contextData[i].path,
				// 				Logger.DEBUG);
				return contextData[i];
			}
		}
		// 	Core.logger.log(this, "Returning null from findContext",
		// 			Logger.DEBUG);
		return null;
	}

	private ServletData findServlet(
		ContextData contextData,
		String uripath,
		String method) {
		//System.err.println("findServlet:- uripath: [" + uripath + "] method: [" + method + "] len: " + contextData.servletData.length);
		// 	Core.logger.log(this, "findServlet("+method+","+uripath+") with "+
		// 			contextData, Logger.DEBUG);
		if (contextData == defaultContext) {
			// 	    Core.logger.log(this, "Returning defaultContext",
			// 			    Logger.DEBUG);
			return defaultContext.servletData[0];
		}

		uripath =
			uripath.substring(contextData.path.length(), uripath.length());
		// 	Core.logger.log(this, "uripath now: "+uripath,
		// 			Logger.DEBUG);
		ServletData[] servletData = contextData.servletData;
		String uripath2 = uripath;
		if (!uripath2.endsWith("/"))
			uripath2 += '/';
		for (int i = 0; i < servletData.length; ++i) {
			/*System.err.println("  trying: servletData[].path: [" + servletData[i].path  + "] method: [" + servletData[i].method + "]");
			    System.err.println("          method: " + servletData[i].method);
			System.err.println("          startsWith: " + uripath.startsWith(servletData[i].path));
			System.err.println("          equalsIgnoreCase(method): " + servletData[i].method.equalsIgnoreCase(method)); 
			System.err.println("          equalsIgnoreCase(BOTH): " + servletData[i].method.equalsIgnoreCase("BOTH")); */
			// 	    Core.logger.log(this, "Trying "+i+": "+servletData[i].path,
			// 			    Logger.DEBUG);
			if ((uripath.startsWith(servletData[i].path)
				|| (uripath2.startsWith(servletData[i].path)))
				&& (servletData[i].method.equalsIgnoreCase(method)
					|| servletData[i].method.equalsIgnoreCase("BOTH"))) {
				Core.logger.log(this, "Returning " + i, Logger.DEBUG);
				return servletData[i];
			}
		}
		// 	Core.logger.log(this, "Returning null", Logger.DEBUG);
		return null;
	}

	public void starting() {
		if (initFirst) {
			starting(defaultContext);
			for (int x = 0; x < contextData.length; x++)
				starting(contextData[x]);
		}
	}

	protected void starting(ContextData c) {
		for (int x = 0; x < c.servletData.length; x++) {
			if (logDebug)
				Core.logger.log(
					this,
					x + ": " + c.servletData[x],
					Logger.DEBUG);
			// A really nasty bug once showed up around here
			try {
				ServletPool servletPool = c.servletData[x].pool;
				servletPool.returnServlet(servletPool.getServlet());
			} catch (UnavailableException e) {
				if (logDebug)
					Core.logger.log(
						this,
						"UnavailableException preinitializing",
						e,
						Logger.DEBUG);
			} catch (ServletException e) {
				if (logDebug)
					Core.logger.log(
						this,
						"ServletException preinitializing",
						e,
						Logger.DEBUG);
			}
		}
	}

}
