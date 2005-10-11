package freenet.interfaces.servlet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;

import freenet.Core;
import freenet.client.ClientFactory;
import freenet.config.Config;
import freenet.config.Params;
import freenet.node.Node;
import freenet.support.Logger;

/**
 * A simple container for running only one servlet on an interface.
 * @author tavin
 */
public class SingleHttpServletContainer extends HttpServletContainer {

    private final Class cls;
    private ServletPool servletPool;
    private boolean initFirst;
    
    /**
     * In-process.
     */
	public SingleHttpServletContainer(
		Node node,
		Class cls,
				      boolean initFirst) {
        super(node);
        this.cls = cls;
	this.initFirst = initFirst;
    }

    /**
     * Out-of-process.
     */
	public SingleHttpServletContainer(
		Logger logger,
                                      ClientFactory factory,
		Class cls,
		boolean initFirst) {
        super(logger, factory);
        this.cls = cls;
	this.initFirst = initFirst;
    }

    public void setInitFirst(boolean b) {
	initFirst = b;
    }

    public final Config getConfig() {
        return new Config();
    }

	public final void init(Params params, String serviceName) {
        setServiceName(serviceName);
        ServletContext context = createServletContext(params);
        String servletContextPath = getServiceName() + ".params.servlet";
		context.setAttribute(
			"freenet.servlet.servletContextPath",
			servletContextPath);
		Core.logger.log(
			SingleHttpServletContainer.class,
			"Loading the single servlet " + servletContextPath,
			Logger.NORMAL);
		
		ServletConfig config =
			createServletConfig(context, cls.getName(), params);
	    servletPool = new SimpleServletPool(cls, context, config);
    }

    public final String getContextPath(String uripath, String path) {
        return "";
    }
    
    public final String getServletPath(String uripath, String method) {
        return "/";
    }

    public final String getPathInfo(String uripath, String method) {
        return uripath.length() > 1
            ? uripath.substring(1, uripath.length())
            : null;
    }
    
    public final ServletContext getContext(String uripath, String method) {
        return servletPool.getServletContext();
    }
    
	protected final ServletPool getServletPool(
		String uripath,
					       String getPath) {
        return servletPool;
    }
    
    public void starting() {
	if(initFirst) {
	    try {
		servletPool.returnServlet(servletPool.getServlet());
	    } catch (UnavailableException e) {
				Core.logger.log(
					this,
					"UnavailableException preinitializing",
					e,
					Logger.DEBUG);
	    } catch (ServletException e) {
				Core.logger.log(
					this,
					"ServletException preinitializing",
					e,
					Logger.DEBUG);
	    }
	}
    }
}
