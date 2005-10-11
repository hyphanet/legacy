package freenet.support.servlet;

import java.util.Enumeration;
import java.util.Iterator;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

import freenet.config.Params;

public class ServletConfigImpl implements ServletConfig {

    protected ServletContext context;
    protected String servletName;
    protected Params initParams;
    
    public ServletConfigImpl(ServletContext context, String servletName,
                             Params initParams) {
        this.context = context;
        this.servletName = servletName;
        this.initParams  = initParams;
    }

    public ServletContext getServletContext() {
        return context;
    }
    
    public String getServletName() {
        return servletName;
    }
    
    public String getInitParameter(String name) {
        return initParams.getString(name, true);
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
}
