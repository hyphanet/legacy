package freenet.support.servlet.http;
import freenet.Core;
import freenet.support.servlet.*;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.HTMLEncoder;
import javax.servlet.http.*;
import javax.servlet.ServletOutputStream;
import java.util.Vector;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;

/**
 * HttpServletResponse implementation.
 *
 * @author oskar
 */
public class HttpServletResponseImpl extends ServletResponseImpl 
                                     implements HttpServletResponse {

    protected final HttpServletRequestImpl request;

    protected int status = -1;

    protected final Vector cookies = new Vector();
    protected final MultiValueTable headers = new MultiValueTable();

    protected boolean keepAlive = false;
    

    public HttpServletResponseImpl(HttpServletRequestImpl request,
                                   int bufferSize) 
	throws IOException {
        super(request, bufferSize);
        this.request = request;
	setTrivialDateHeader();
    }

    
    /**
     * @return  true, if the container should hold the connection open
     *          and accept another request after this response is sent
     *
     * This is determined in writeResponseHeaders(), based on the
     * keepAlive value of the HttpServletRequestImpl, and whether
     * the content-length is known.
     */
    public final boolean keepAlive() {
        return keepAlive;
    }
    

    public void addCookie(Cookie cookie) {
        cookies.addElement(cookie);
    }

    public boolean containsHeader(String name) {
        return headers.containsKey(name);
    }

    /**
     * I said: I use cookies!
     */
    public String encodeURL(String url) {
        return url;
    }

    public String encodeRedirectURL(String url) {
        return url;
    }

    public String encodeUrl(String url) {
        return encodeURL(url);
    }

    public String encodeRedirectUrl(String url) {
        return encodeRedirectURL(url);
    }

    public void sendError(int sc, String msg) throws IllegalStateException,
                                                     IOException {
        reset();
        setContentType("text/html");
        status = sc;

        String statusString = status + " " +
            freenet.support.servlet.http.HttpServletResponseImpl.getNameForStatus(status);

        StringBuffer fullmessage = new StringBuffer();
        fullmessage.append("<html>");
        fullmessage.append("<head><title>" + statusString + "</title></head>");
        fullmessage.append("<body bgcolor=\"#ffffff\">");
        fullmessage.append("<h1>" + statusString + "</h1>");
        fullmessage.append("<p>" + msg);
        fullmessage.append("<p><br /><br /><br /><b>");
        fullmessage.append(request.container.getServerInfo());
        fullmessage.append("</b> - ").append(new Date());
        fullmessage.append("</body>");
        fullmessage.append("</html>");

        byte[] b = fullmessage.toString().getBytes();
        contentLength = b.length;
        bufferOutput.write(b);
        flushBuffer();
        // hmm...
    }

    public void sendError(int sc) throws IllegalStateException, IOException {
        sendError(sc, "<HTML><TITLE>Error " + sc + "</TITLE><BODY>Error: " +
                  sc + " " + getNameForStatus(sc) + "</BODY></HTML>");
    }

    public void sendRedirect(String location) throws IllegalStateException,
                                                     IOException {
        reset();
        status = SC_MOVED_TEMPORARILY;

        setHeader("Location", encodeRedirectURL(location));
        setHeader("Expires", "Mon, 26 Jul 1997 05:00:00 GMT");    // Date in the past
	setHeader("Cache-Control","no-store, no-cache, must-revalidate");  // HTTP/1.1
	setHeader("Cache-Control","post-check=0, pre-check=0");
	setHeader("Pragma","no-cache");                          // HTTP/1.0
	setTrivialDateHeader();
	StringBuffer message = new StringBuffer();
	message.append("<html><head><title>Redirect</title>");
	message.append("<meta http-equiv=\"refresh\" content=\"0; url="+
		       encodeRedirectURL(location)+"\"></head>");
	message.append("<body><title>Redirect</title>");
	message.append("Click <a href=\""+HTMLEncoder.encode(location)+
		       "\">here</a>");
	message.append("</body></html>");
	setContentType("text/html");
	byte[] b = message.toString().getBytes();
	contentLength = b.length;
	bufferOutput.write(b);
        flushBuffer();
        // hmm...
    }

    public void setDateHeader(String name, long date) {
        headers.remove(name);
        addDateHeader(name, date);
    }

    public void setTrivialDateHeader() {
	setDateHeader("Date", System.currentTimeMillis());
    }

    static private SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);
    public void addDateHeader(String name, long date) {
        try {
            headers.put(name, dateFormat.format(new Date(date)));
        } catch (ArrayIndexOutOfBoundsException e) {
            // 1.5.0-b2 can occasionally throw this
            // So just catch it and ignore it.
            // Don't say "just don't use 1.5.0-b2", as almost ALL JVMs
            // appear to have SOME problems. Java is a black art. :(
            Core.logger.log(this, "Not setting date ("+date+") because of internal error, probably class library's fault: "+
                    e, e, Logger.NORMAL);
        }
    }


    public void setHeader(String name, String value) {
        headers.remove(name);
        addHeader(name, value);
    }

    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    public void setIntHeader(String name, int value) {
        headers.remove(name);
        addIntHeader(name, value);
    }

    public void addIntHeader(String name, int value) {
        headers.put(name, Integer.toString(value));
    }

    public void setStatus(int sc) {
        this.status = sc;
    }

    public void setStatus(int sc, String sm) {
        setStatus(sc); // I can do this because it is deprecated.
    }

    protected void writeResponseHeaders(OutputStream out) throws IOException {
        ServletOutputStream sout = new ServletOutputStreamImpl(out);
        sout.print("HTTP/1.1 ");
        sout.print(status == -1 ? SC_OK : status);
        sout.print(' ');
        sout.println(getNameForStatus(status));
        
        for (Enumeration e = headers.keys(); e.hasMoreElements() ;) {
            String name = (String) e.nextElement();
            Enumeration f = headers.getAll(name);
            while (f.hasMoreElements()) {
                sout.print(name);
                sout.print(": ");
                sout.println((String) f.nextElement());
            }
        }

         for (Enumeration e = cookies.elements(); e.hasMoreElements();) {
             sout.print("Set-cookie: ");
             sout.println(HttpSupport.toHeader((Cookie) e.nextElement()));
         }
        HttpSession hs = request.getSession(false);
         if (hs != null) {
             sout.print("Set-Cookie: ");
 			Cookie cSessionCookie = new Cookie("SESSION", hs.getId());
 			//Ideally this setPath step should be optional and decided by something in the session
 			//but then we have to clear old (and becuse of more-specific-path overriding) session cookies
 			//in case of switches between the two modes. I am not even sure that this is possible.... /Iakin
 			//If no explicit path where to be set the session would become servlet-specific instead of host:port-specific
 			cSessionCookie.setPath("/");
             sout.println(HttpSupport.toHeader(cSessionCookie));
         }
        if (contentLength != -1 && request.keepAlive())
            keepAlive = true;
        
        if (!keepAlive)
            sout.println("Connection: close");
 
        if (contentLength != -1) {
            sout.print("Content-length: ");
            sout.println(contentLength);
        }
        
        if ((contentType != null) && contentType.length()!=0) {
            sout.print("Content-type: ");
	    if(isTextual() && charEncoding != null)
		sout.println(contentType + ";charset="+charEncoding);
	    else
		sout.println(contentType);
        }
	
        sout.print("Server: ");
        sout.println(request.container.getServerInfo());
        
        sout.println();
        sout.flush();
    }

    public boolean isTextual() {
	return (contentType.length() > "text/".length() &&
		contentType.substring(0,5).equals("text/"));
    }
    
    public void reset() throws IllegalStateException {
        super.reset();
        headers.clear();
        cookies.setSize(0);
    }

    public static String getNameForStatus(int status) {
        switch(status) {
        case -1:
        case SC_OK:
            return "OK";
        case SC_CONTINUE:
            return "Continue";
        case SC_SWITCHING_PROTOCOLS:
            return "Switching Protocols";
        case SC_CREATED:
            return "Created";
        case SC_ACCEPTED:
            return "Accepted";
        case SC_NON_AUTHORITATIVE_INFORMATION:
            return "Non-Authoritative Information";
        case SC_NO_CONTENT:
            return "No Content";
        case SC_RESET_CONTENT:
            return "Reset Content";
        case SC_PARTIAL_CONTENT:
            return "Partial Content";
        case SC_MOVED_PERMANENTLY:
            return "Moved Permanently";
        case SC_MULTIPLE_CHOICES:
            return "Multiple Choices";
        case SC_MOVED_TEMPORARILY:
            return "Moved Temporarily";
        case SC_SEE_OTHER:
            return "See Other";
        case SC_USE_PROXY:
            return "Use Proxy";
        case SC_BAD_REQUEST:
            return "Bad Request";
        case SC_UNAUTHORIZED:
            return "Unauthorized";
        case SC_PAYMENT_REQUIRED:
            return "Payment Required";
        case SC_FORBIDDEN:
            return "Forbidden";
        case SC_METHOD_NOT_ALLOWED:
            return "Method Not Allowed";
        case SC_NOT_FOUND:
            return "Not Found";
        case SC_NOT_ACCEPTABLE:
            return "Not Acceptable";
        case SC_PROXY_AUTHENTICATION_REQUIRED:
            return "Authentication Required";
        case SC_REQUEST_TIMEOUT:
            return "Request Timeout";
        case SC_CONFLICT:
            return "Conflict";
        case SC_GONE:
            return "Gone";
        case SC_LENGTH_REQUIRED:
            return "Length Required";
        case SC_PRECONDITION_FAILED :
            return "Precondition Failed";
        case SC_REQUEST_ENTITY_TOO_LARGE:
            return "Request Entity Too Long";
        case SC_REQUEST_URI_TOO_LONG :
            return "Request URI Too Long";
        case SC_UNSUPPORTED_MEDIA_TYPE:
            return "Unsupported Media Type";
        case SC_REQUESTED_RANGE_NOT_SATISFIABLE:
            return "Requested Range Not Satisifiable";
        case SC_EXPECTATION_FAILED:
            return "Expectation Failed";
        case SC_INTERNAL_SERVER_ERROR:
            return "Internal Server Error";
        case SC_NOT_IMPLEMENTED:
            return "Not Implemented";
        case SC_BAD_GATEWAY:
            return "Bad Gateway";
        case SC_SERVICE_UNAVAILABLE:
            return "Service Unavailable";
        case SC_GATEWAY_TIMEOUT:
            return "Gateway Timeout";
        case SC_HTTP_VERSION_NOT_SUPPORTED:
            return "HTTP Version Not Supported";
        default:
            return "Unknown";
        }
    }
}


