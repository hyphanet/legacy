package freenet.interfaces.servlet;

import java.io.IOException;
import java.util.Hashtable;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Connection;
import freenet.Core;
import freenet.Version;
import freenet.client.ClientFactory;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.servlet.http.HttpServletRequestImpl;
import freenet.support.servlet.http.HttpServletResponseImpl;
import freenet.support.servlet.http.SessionHolder;
import freenet.support.servlet.http.SessionHolderImpl;

/**
 * General functionality for dealing with HTTP servlets. The contexts and
 * servlet pools are left in the domain of the implementation.
 * 
 * @author tavin
 */
public abstract class HttpServletContainer extends ServletContainer {

	public static final String SERVER_INFO =
		Version.nodeName
			+ " "
			+ Version.nodeVersion
			+ " (build "
			+ Version.buildNumber
			+ ") HTTP Servlets";

	/**
	 * The buffers are used for every servlet request and response regardless
	 * of the size of the data. Keep them on the smaller side unless
	 * HttpServletResponse and HttpServletRequest implement reusable buffers.
	 */
	public static final int INPUT_BUFFER = 4 * 1024, OUTPUT_BUFFER = 4 * 1024;

	protected final SessionHolder sessionHolder = new SessionHolderImpl();

	/** live ServletRequests and their associated ServletPool */
	protected final Hashtable liveTable = new Hashtable();

	/**
	 * In-process container.
	 */
	public HttpServletContainer(Node node) {
		super(node);
	}

	/**
	 * Out-of-process container.
	 */
	public HttpServletContainer(Logger logger, ClientFactory factory) {
		super(logger, factory);
	}

	/**
	 * @return the name of this Service (node-plugin)
	 */
	public String name() {
		return SERVER_INFO;
	}

	/**
	 * @return some descriptive nonsense about the container
	 */
	public String getServerInfo() {
		return SERVER_INFO;
	}

	/**
	 * @return the part of the URI path specifying the context
	 */
	public abstract String getContextPath(String uripath, String path);

	/**
	 * @return the part of the URI path specifying the servlet
	 */
	public abstract String getServletPath(String uripath, String path);

	/**
	 * @return the part of the URI path passed to the servlet as "path info"
	 *         (the end)
	 */
	public abstract String getPathInfo(String uripath, String method);

	/**
	 * @return the ServletPool registered for the path (associated with a
	 *         specific Servlet class) or null if not found
	 */
	protected abstract ServletPool getServletPool(
		String uripath,
		String method);

	protected ServletRequest getNextRequest(
		ServletRequest lastRequest,
		ServletResponse lastResponse,
		Connection conn)
		throws IOException {

		if (lastResponse == null
			|| ((HttpServletResponseImpl) lastResponse).keepAlive()) {

			return new HttpServletRequestImpl(
				this,
				conn,
				INPUT_BUFFER,
				sessionHolder,
				"BASIC");
		} else
			return null;
	}

	public ServletResponse getResponseFor(ServletRequest req)
		throws IOException {

		return new HttpServletResponseImpl(
			(HttpServletRequestImpl) req,
			OUTPUT_BUFFER);
	}

	public Servlet getServletFor(ServletRequest req_)
		throws ServletException, UnavailableException {

		HttpServletRequestImpl req = (HttpServletRequestImpl) req_;

		if (!req.isValid())
			return new DumbServlet(
				HttpServletResponse.SC_BAD_REQUEST,
				req.getBadRequestMessage());

		String uri = req.getRequestPath();
		String method = req.getMethod();
		Core.logger.log(
			this,
			"Getting servlet for " + method + " " + uri,
			new Exception("debug"),
			Logger.DEBUG);
		ServletPool pool = getServletPool(uri, method);

		if (pool == null) {
			Core.logger.log(
				this,
				"Null pool for " + method + " " + uri,
				Logger.DEBUG);
			// directory servlet check for URIs missing the terminal slash
			if (!uri.endsWith("/")
				&& getServletPool(uri + "/", method) != null) {
				Core.logger.log(
					this,
					"Missing slash, redirecting: " + method + " " + uri,
					Logger.DEBUG);
				return new MovedServlet(uri + "/");
			}
			Core.logger.log(this, "404ing " + method + " " + uri, Logger.DEBUG);
			return new DumbServlet(HttpServletResponse.SC_NOT_FOUND);
		}

		Servlet servlet = pool.getServlet();
		liveTable.put(req, pool);
		return servlet;
	}

	protected void returnServlet(ServletRequest req, Servlet servlet) {
		ServletPool pool = (ServletPool) liveTable.remove(req);
		if (pool != null)
			pool.returnServlet(servlet);
	}

	/**
	 * Used to return canned responses.
	 */
	protected final class DumbServlet extends HttpServlet {

		private final int sc;
		private final String msg;

		DumbServlet(int sc) {
			this(sc, null);
		}

		DumbServlet(int sc, String msg) {
			this.sc = sc;
			this.msg = msg;
		}

		public void service(
			HttpServletRequest request,
			HttpServletResponse response)
			throws IOException {
			try {
				if (msg == null)
					response.sendError(sc);
				else
					response.sendError(sc, msg);
			} catch (java.net.SocketException e) { /* do we care? */
			}
		}
	}

	/**
	 * Used for moved resources.
	 */
	protected final class MovedServlet extends HttpServlet {

		private String location;

		public MovedServlet(String location) {
			this.location = location;
		}

		public void service(
			HttpServletRequest request,
			HttpServletResponse response) {

			response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
			response.addHeader("location", location);
		}

	}
}
