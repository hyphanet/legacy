package freenet.client.http;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A RedirectServlet redirects all HTTP GET
 * requests to a different targetURL on the 
 * same host and port.
 * <p>
 * Note: targetURL must start with "/".
 **/
public class RedirectServlet extends HttpServlet {

    private String targetURL = null;
    
    public void init() {
        targetURL = getInitParameter("targetURL");
        if ((targetURL != null) && !targetURL.startsWith("/")) {
            // Keep clueless people from accidentally
            // configuring external redirects.
            targetURL=null;
        }
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws IOException {

        if (targetURL == null) {
            throw new IOException("RedirectServlet targetURL " +
                                  "isn't set correctly in the config file.");
        }
        resp.sendRedirect(targetURL);
        resp.flushBuffer();
    }
}

