package freenet.interfaces.servlet;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.SingleThreadModel;
import javax.servlet.UnavailableException;

import freenet.Core;
import freenet.support.Logger;

/**
 * It only maintains a single instance.  So if the internal state
 * of that instance gets h0rked it'll stay that way..
 */
public class SimpleServletPool implements ServletPool {

	private final Class servletClass;
	private final ServletContext context;
	private final ServletConfig config;

	private Servlet instance = null;

	public SimpleServletPool(
		Class servletClass,
		ServletContext context,
		ServletConfig config) {

		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"Initializing "
					+ this
					+ " ("
					+ servletClass
					+ ","
					+ context
					+ ","
					+ config,
				new Exception("debug"),
				Logger.DEBUG);
		this.servletClass = servletClass;
		this.context = context;
		this.config = config;
	}

	public Servlet createServlet()
		throws ServletException, UnavailableException {
		try {
			Servlet servlet = (Servlet) servletClass.newInstance();
			servlet.init(config);
			return servlet;
		} catch (InstantiationException e) {
			throw new UnavailableException("" + e);
		} catch (IllegalAccessException e) {
			throw new UnavailableException("" + e);
		}
	}

	public synchronized Servlet getServlet()
		throws ServletException, UnavailableException {
		Servlet servlet;
		if (instance == null) {
			servlet = createServlet();
			if (!(servlet instanceof SingleThreadModel))
				instance = servlet;
		} else {
			servlet = instance;
			if (servlet instanceof SingleThreadModel)
				instance = null;
		}
		return servlet;
	}

	public synchronized void returnServlet(Servlet servlet) {
		if (instance == null)
			instance = servlet;
		else if (servlet != instance)
			servlet.destroy();
	}

	public final ServletContext getServletContext() {
		return context;
	}
}
