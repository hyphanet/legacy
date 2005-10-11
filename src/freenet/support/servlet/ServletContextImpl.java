package freenet.support.servlet;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;

import freenet.config.Params;
import freenet.interfaces.servlet.ServletContainer;
import freenet.support.LimitedEnumeration;
import freenet.support.Logger;
import freenet.support.LoggerHookChain;

public class ServletContextImpl implements ServletContext {

    protected final ServletContainer container;

    protected Params initParams;
    protected Hashtable sysAttr = new Hashtable();

    protected Logger logger;
    protected int logLevel;
    
    public String toString() {
	StringBuffer sb = new StringBuffer(100);
	sb.append(super.toString());
	sb.append("logLevel=");
	sb.append(logLevel);
	sb.append("initParams=");
	sb.append(initParams.toString());
	for(Enumeration i = sysAttr.keys();i.hasMoreElements();) {
	    Object key = i.nextElement();
	    Object value = sysAttr.get(key);
	    sb.append(",");
	    sb.append(key.toString());
	    sb.append(":");
	    sb.append(value.toString());
	}
	return sb.toString();
    }
    
    /**
     * @param container   the servlet container managing this context
     * @param initParams  the context-wide initialization parameters
     * @param logger      system Logger (Core.logger)
     * @param logLevel    log level to use
     */
    public ServletContextImpl(ServletContainer container, Params initParams,
                              Logger logger, int logLevel) {
        
        this.container = container;
        this.initParams = initParams;
        this.logger = (logger == null ? new LoggerHookChain() : logger);
        this.logLevel = logLevel;
    }

    /**
     * @return  the ServletContext managing the given uri path
     */
    public final ServletContext getContext(String uripath) {
        // botch to allow us to use javax.servlet with added method stuff
        return container.getContext(uripath, "GET");
    }

    public final ServletContext getContext(String uripath, String method) {
        return container.getContext(uripath, method);
    }
   
    /**
     * We support 2.2
     */
    public final int getMajorVersion() {
        return 2;
    }
    
    /**
     * We support 2.2
     */
    public final int getMinorVersion() {
        return 2;
    }

    // FIXME - how do we do this, "the Freenet way"?
    // like this I think -
    public String getMimeType(String file) {
        return freenet.client.metadata.MimeTypeUtils.getExtType(file);
        //        return "application/octet-stream";
    }
    
    // FIXME
    public URL getResource(String path) throws MalformedURLException {
        // Silence warning
        if(false) throw new MalformedURLException();
        return null;
    }
    
    // FIXME
    public InputStream getResourceAsStream(String path) {
        return null;
    }
    
    // FIXME (blah)
    public RequestDispatcher getRequestDispatcher(String path) {
        return null;
    }

    // FIXME (halb)
    public RequestDispatcher getNamedDispatcher(String name) {
        return null;
    }
    
    /**
     * @deprecated
     * Always returns null.  Don't fix me.
     */
    public final Servlet getServlet(String name) {
        return null;
    }

    /**
     * @deprecated
     * Always returns an empty enumeration.
     */
    public final Enumeration getServlets() {
        return new LimitedEnumeration(null);
    }

    /**
     * @deprecated
     * Always returns an empty enumeration.
     */
    public final Enumeration getServletNames() {
        return new LimitedEnumeration(null);
    }

    /**
     * Logs to Fred's Core logger.
     */
    public void log(String msg) {
        logger.log(this, msg, logLevel);
    }

    /**
     * @deprecated
     * @see #log(String, Throwable)
     */
    public final void log(Exception e, String msg) {
        log(msg, e);
    }
    
    /**
     * Logs with stacktrace.
     */
    public void log(String msg, Throwable e) {
        logger.log(this, msg, e, logLevel);
    }
    
    /**
     * @return  the filesystem path for a virtual (url) path
     */
    public final String getRealPath(String path) {
        return container.getRealPath(path);
    }
    
    /**
     * @return  the server's info line
     */
    public final String getServerInfo() {
        return container.getServerInfo();
    }
    
    
    /**
     * Impresses women.
     */
    public String getInitParameter(String name) {
        return initParams.getString(name);
    }
    
    public Enumeration getInitParameterNames() {
		return new Enumeration() {
				private Iterator iter = initParams.keySet().iterator();
				public boolean hasMoreElements() {
					return iter.hasNext();
				}
				public Object nextElement() {
					return iter.next();
				}
		};
    }
    
    
    /**
     * Impresses dogs.
     */
    public Object getAttribute(String name) {
        return sysAttr.get(name);
    }
    
    public Enumeration getAttributeNames() {
        return sysAttr.keys();
    }
    
    public void setAttribute(String name, Object object) {
        sysAttr.put(name, object);
    }
    
    public void removeAttribute(String name) {
        sysAttr.remove(name);
    }
}


