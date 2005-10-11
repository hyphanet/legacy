package freenet.interfaces.servlet;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;

import freenet.Connection;
import freenet.Core;
import freenet.client.ClientFactory;
import freenet.config.Params;
import freenet.interfaces.Service;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.servlet.BadRequestException;
import freenet.support.servlet.ServletConfigImpl;
import freenet.support.servlet.ServletContextImpl;

/**
 * Generic interface for parsing servlet requests from a connection and
 * dispatching them.
 * 
 * @author tavin
 */
public abstract class ServletContainer implements Service {

	protected final Node node;
	protected final Logger logger;
	protected final ClientFactory factory;
	private String serviceName;

	/**
	 * Constructor for servlet containers running within the node.
	 */
	protected ServletContainer(Node node) {
		this.node = node;
		this.logger = Node.logger;
		this.factory = node.client;
	}

	/**
	 * Constructor for servlet containers running independently of the node.
	 */
	protected ServletContainer(Logger logger, ClientFactory factory) {
		this.node = null;
		this.logger = logger;
		this.factory = factory;
	}

	/**
	 * Reads a ServletRequest from the connection, then obtains a
	 * ServletResponse and a Servlet instance and calls the service() method of
	 * the Servlet.
	 * 
	 * If the implementation throws a BadRequestException it will result in the
	 * connection simply being closed. Therefore, the implementation may
	 * instead wish to return an alternate Servlet that issues an error
	 * response to the client.
	 */
	public void handle(Connection conn) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		try {
			conn.enableThrottle();
			ServletRequest request = null;
			ServletResponse response = null;
			Servlet servlet;

			while (null
				!= (request = getNextRequest(request, response, conn))) {
				response = getResponseFor(request);
				servlet = getServletFor(request);
				if(logDEBUG)
					Core.logger.log(this, "request: "+request+", response="+
							response+", servlet="+servlet, Logger.DEBUG);
				try {
					servlet.service(request, response);
					response.flushBuffer();
				} finally {
					returnServlet(request, servlet);
				}
			}
		} catch (BadRequestException e) {
			Core.logger.log(this, "Bad servlet request", e, Logger.NORMAL);
		} catch (UnavailableException e) {
			Core.logger.log(
				this,
				"Servlet unavailable for connection: " + conn,
				e,
				Logger.ERROR);
		} catch (ServletException e) {
			Core.logger.log(
				this,
				"Servlet failure: " + e.getMessage(),
				e,
				Logger.NORMAL);
		} catch (java.net.SocketException e) {
			// Not important - users close HTTP sockets _all the time_
			Core.logger.log(this, "Socket error in servlet", e, Logger.DEBUG);
		} catch (IOException e) {
			Core.logger.log(this, "I/O error in servlet", e, Logger.NORMAL);
		} finally {
			conn.close();
			if(Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(this, "Closing "+conn, Logger.DEBUG);
		}
	}

	/**
	 * @return the filesystem path for the given virtual path The subclass
	 *         should override this if it wants path translation to work.
	 */
	public String getRealPath(String path) {
		return null;
	}

	/**
	 * @return the RequestDispatcher for the named resource
	 */
	public RequestDispatcher getRequestDispatcher(String name) {
		return null; // FIXME
	}

	/**
	 * @return a line of some descriptive nonsense
	 */
	public abstract String getServerInfo();

	/**
	 * @return the context for the uri path (some containers might ignore the
	 *         path and just use one context)
	 */
	public abstract ServletContext getContext(String uripath, String method);

	// thinking about turning this stuff into a system of Factories

	protected ServletContext createServletContext(Params contextParams) {
		ServletContext context =
			new ServletContextImpl(this, contextParams, logger, Logger.MINOR);

		if (node != null) {
			context.setAttribute("freenet.node.Node", node);
			context.setAttribute("freenet.support.BucketFactory", node.bf);
		}
		context.setAttribute("freenet.client.ClientFactory", factory);
		context.setAttribute(
			"freenet.crypt.RandomSource",
			Core.getRandSource());

		context.setAttribute("freenet.serviceName", getServiceName());
		Core.logger.log(
			ServletContainer.class,
			"Creating servlet context w/ service name ["
				+ getServiceName()
				+ "]",
			Logger.DEBUG);

		return context;
	}

	protected ServletConfig createServletConfig(
		ServletContext context,
		String servletName,
		Params servletParams) {
		if (context.getAttribute("freenet.servlet.servletContextPath") != null)
			servletParams.put(
				"servletContextPath",
				(String) context.getAttribute(
					"freenet.servlet.servletContextPath"));
		ServletConfigImpl config =
			new ServletConfigImpl(context, servletName, servletParams);
		return config;
	}

	/**
	 * Should keep returning ServletRequests until the connection is ready to
	 * be closed, at which time it should return null.
	 * 
	 * @param lastRequest
	 *            the last ServletRequest produced (null on the first call)
	 * @param lastResponse
	 *            the last ServletResponse produced (null on the first call)
	 */
	protected abstract ServletRequest getNextRequest(
		ServletRequest lastRequest,
		ServletResponse lastResponse,
		Connection conn)
		throws IOException, BadRequestException;

	/**
	 * Create the ServletResponse object for the given ServletRequest.
	 */
	protected abstract ServletResponse getResponseFor(ServletRequest req)
		throws IOException;

	/**
	 * Get a Servlet instance to handle the request.
	 */
	protected abstract Servlet getServletFor(ServletRequest req)
		throws ServletException, UnavailableException;

	/**
	 * Return the Servlet instance to the pool.
	 */
	protected abstract void returnServlet(ServletRequest req, Servlet servlet);

	public void setServiceName(String name) {
		serviceName = name;
	}
	public String getServiceName() {
		return serviceName;
	}

	public boolean needsThread() {
		return true;
	}
}
