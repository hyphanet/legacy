package freenet.interfaces.servlet;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.Enumeration;

/**
 * Simple test servlet that shows the container is basically sane,
 * and that all parameters are being passed through properly.
 * @author tavin
 */
public class TestHttpServlet extends HttpServlet {

    
    public void doGet(HttpServletRequest req, HttpServletResponse resp)
                                                    throws IOException {
       
        log("Test logger: "+TestHttpServlet.class.getName());
        log("Test exception logger: "+TestHttpServlet.class.getName(),
            new Exception());
        
        resp.setContentType("text/plain");

        PrintWriter pw = resp.getWriter();

        pw.println("Server: " + getServletContext().getServerInfo());
        pw.println("Servlet: " + getServletName());
        pw.println();
        
        pw.println("Protocol: " + req.getProtocol());
        pw.println("Remote IP: " + req.getRemoteAddr());
        pw.println("Remote host: " + req.getRemoteHost());
        pw.println("Scheme: " + req.getScheme());
        pw.println("Secure: " + req.isSecure());
        pw.println("Server name: " + req.getServerName());
        pw.println("Server port: " + req.getServerPort());
        pw.println();

        pw.println("Auth type: " + req.getAuthType());
        pw.println("Method: " + req.getMethod());
        pw.println();
        
        pw.println("Request URI: " + req.getRequestURI());
        pw.println("Query string: " + req.getQueryString());
        pw.println("Context path: " + req.getContextPath());
        pw.println("Servlet path: " + req.getServletPath());
        pw.println("Path info: " + req.getPathInfo());
        pw.println("Path translated: " + req.getPathTranslated());
        pw.println();
    
        printSystemAttributes(pw);
        printContextParameters(pw);
        printServletParameters(pw);
        
        resp.flushBuffer();
    }
        
    private void printSystemAttributes(PrintWriter pw) {
        ServletContext context = getServletContext();
        pw.println("System attributes");
        pw.println("-----------------");
        Enumeration names = context.getAttributeNames();
        while (names.hasMoreElements()) {
            String attr = (String) names.nextElement();
            pw.println(attr + " = " + context.getAttribute(attr));
        }
        pw.println();
    }

    private void printContextParameters(PrintWriter pw) {
        ServletContext context = getServletContext();
        pw.println("Context initialization parameters");
        pw.println("---------------------------------");
        Enumeration names = context.getInitParameterNames();
        while (names.hasMoreElements()) {
            String attr = (String) names.nextElement();
            pw.println(attr + " = " + context.getInitParameter(attr));
        }
        pw.println();
    }

    private void printServletParameters(PrintWriter pw) {
        pw.println("Servlet initialization parameters");
        pw.println("---------------------------------");
        Enumeration names = getInitParameterNames();
        while (names.hasMoreElements()) {
            String attr = (String) names.nextElement();
            pw.println(attr + " = " + getInitParameter(attr));
        }
        pw.println();
    }
}



