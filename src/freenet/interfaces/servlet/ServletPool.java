package freenet.interfaces.servlet;

import javax.servlet.*;

public interface ServletPool {

    /**
     * @return  the ServletContext associated with the Servlets
     *          generated from this pool
     */
    ServletContext getServletContext();

    /**
     * Check out a Servlet from this pool.
     */
    Servlet getServlet() throws ServletException, UnavailableException;

    /**
     * Return a Servlet to the pool.
     */
    void returnServlet(Servlet servlet);
}



