package freenet.support.servlet.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.Vector;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import freenet.Connection;
import freenet.interfaces.servlet.HttpServletContainer;
import freenet.support.MultiValueTable;
import freenet.support.servlet.BadRequestException;
import freenet.support.servlet.ServletInputStreamImpl;
import freenet.support.servlet.ServletRequestImpl;

/**
 * Implementation of HttpServletRequest.
 * Does all the HTTP protocol parsing.
 * 
 * @author oskar
 */

public class HttpServletRequestImpl extends ServletRequestImpl 
                                    implements HttpServletRequest {

    public static Random randSource = freenet.Core.getRandSource();
    public static final int LINE_BUFFER = 0x8000;  // 32k

    protected final HttpServletContainer container;

    protected String method, requestURI, versionString;

    protected String requestPath, queryString;
    
    protected int versionMajor, versionMinor;

    protected String authType;
    
    protected SessionHolder sessionHolder;
    protected HttpSessionImpl session;

    protected final MultiValueTable headers = new MultiValueTable();
    protected Cookie[] cookies;

    protected boolean keepAlive = false;

    protected boolean isValid = false;
    protected String invalidMsg;
    
    /**
     * Create an HTTP request from headers
     */
    public HttpServletRequestImpl(HttpServletContainer container,
				  Connection conn, int bufferSize,
				  SessionHolder sessionHolder,
				  String authType, byte[] headers,
				  int offset, int length) throws IOException {
	this(container, conn, bufferSize, sessionHolder, authType,
	     new ByteArrayInputStream(headers, offset, length));
    }
    
    /**
     * Read an HTTP request from the connection.
     */
    public HttpServletRequestImpl(HttpServletContainer container,
                                  Connection conn, int bufferSize,
                                  SessionHolder sessionHolder,
                                  String authType) throws IOException {
	this(container, conn, bufferSize, sessionHolder, authType, 
	     conn.getIn());
    }
    
    /**
     * Read an HTTP request from the connection.
     */
    public HttpServletRequestImpl(HttpServletContainer container,
                                  Connection conn, int bufferSize,
                                  SessionHolder sessionHolder,
                                  String authType, InputStream oin) 
	throws IOException {
	
        super(container, conn, bufferSize);

	ServletInputStreamImpl in =
	    new ServletInputStreamImpl(oin);
	
        this.container = container;
        this.sessionHolder = sessionHolder;
        this.authType = authType;
        
        byte[] buffer = new byte[LINE_BUFFER];

        try {
            StringTokenizer st =
                new StringTokenizer(HttpSupport.readHeader(in, buffer), " ");

            method = st.nextToken().toUpperCase();
            requestURI = st.nextToken();
            versionString = st.nextToken().toUpperCase();
            if (st.hasMoreTokens())
                throw new NoSuchElementException();
        }
        catch (BadRequestException e) {
            invalidMsg = "Malformed HTTP request: "+e.getMessage();
            return;
        }
        catch (NoSuchElementException e) {
            invalidMsg = "Not enough fields in HTTP request";
            return;
        }

        if (!versionString.startsWith("HTTP/")) {
            invalidMsg = "Invalid HTTP version string";
            return;
        }
            
        try {
            String versionNumber = versionString.substring(versionString.indexOf('/')+1);
            int dot = versionNumber.indexOf('.');
            versionMajor = Integer.parseInt(versionNumber.substring(0, dot));
            versionMinor = Integer.parseInt(versionNumber.substring(dot+1));
        }
        catch (StringIndexOutOfBoundsException e) {
            invalidMsg = "Unspecified HTTP version numbers";
            return;
        }
        catch (NumberFormatException e) {
            invalidMsg = "Corrupt HTTP version numbers";
            return;
        }

        
        // parse query string parameters
        int qmark = requestURI.indexOf('?');
        if (qmark == -1) {
            queryString = null;
        }
        else {
            queryString = requestURI.substring(qmark+1, requestURI.length());
            requestURI  = requestURI.substring(0, qmark);
            HttpSupport.parseQueryString(queryString, params);
        }

        if (requestURI.startsWith("/")) {
            requestPath = requestURI;
        }
        else {
            int x = requestURI.indexOf("://");
            if (x != -1) x = requestURI.indexOf('/', x+3);
            requestPath =
                (x == -1 ? "/" : requestURI.substring(x, requestURI.length()));
        }
        

        // New plan.  Use the ServletRequest to parse the incoming stream,
        // but go back to the ServletContainer to break down the paths
        // and figure out which context and servlet to get.


        // read headers
        try {
            HttpSupport.parseHttpHeaders(headers, in);
        }
        catch (BadRequestException e) {
            invalidMsg = e.getMessage();
            return;
        }

        
        String cls = (String) headers.get("content-length");
        contentLength = cls == null ? -1 : Integer.parseInt(cls);

        // FIXME - I guessed at this name;
        // is it in content-type?   "text/html; charset=blah"
        charEncoding = (String) headers.get("character-encoding");

        contentType = (String) headers.get("content-type");

        Vector cookieV = new Vector();
        String id = null;
        for (Enumeration e = headers.getAll("cookie"); e.hasMoreElements();) {
           Cookie c = HttpSupport.parseCookie((String) e.nextElement());
           cookieV.addElement(c);
           if (c.getName().equals("SESSION"))
               id = c.getValue();
        }
        cookies = new Cookie[cookieV.size()];
        cookieV.copyInto(cookies);

        if (id != null) {
            session = sessionHolder.getSession(id);
            if (session != null) {
                session.wasAccessed();
                session.notNew();
            }
        }

        
        if (versionMajor == 1 && versionMinor > 0 || versionMajor > 1) {
            String close = (String) headers.get("connection");
            if (close == null || !close.equalsIgnoreCase("close"))
                keepAlive = true;
        }

        // successfully parsed request
        isValid = true;


        // get form data
        // not sure this makes sense here, since there are no methods
        // to get at the data
        // FIXME .. there are other encodings
        //if (method.equals("POST")) {
        //    // FIXME .. like, what if contentLength is -1, or insanely big?
        //    HttpSupport.parseFormData(contentLength, super.input,
        //                            charEncoding, params);
        //}

        // 2002.01.06 -- WTF??  need to fix this ^^^  -tc

        
        if (method.equals("POST")
            && contentType.equals("application/x-www-form-urlencoded")
            && contentLength > 0) {
            
            HttpSupport.parseFormData(contentLength, conn.getIn(),
                                      charEncoding, params);
        }
        // 2002.01.10 .. i actually need POST parsing  -tc
    }


    /**
     * @return  true, if the request was correctly formed
     *          (otherwise a 400 needs to be returned)
     */
    public final boolean isValid() {
        return isValid;
    }

    /**
     * @return  a message explaining why the request is not valid
     *          (if isValid() == false)
     */
    public final String getBadRequestMessage() {
        return invalidMsg;
    }

    
    /**
     * @return  true, if the server should hold the connection open
     *          to read another request
     */
    public final boolean keepAlive() {
        return keepAlive;
    }

    
    public final String getProtocol() {
        return versionString;
    }

    public final String getScheme() {
        return isSecure() ? "https" : "http";
    }

    // FIXME - how do I map from the Accept-Language field in the request
    // to a java locale... and do I fucking need to?
    //public Locale getLocale() {
    //    return super.getLocale();
    //}

    // FIXME - ditto
    //public Enumeration getLocales() {
    //    return super.getLocales();
    //} 

    public final String getAuthType() {
        return authType;
    }

    public final Cookie[] getCookies() {
        return cookies;
    }
    

    
    public final Enumeration getHeaderNames() {
        return headers.keys();
    }

    public final String getHeader(String name) {
        return (String) headers.get(name.toLowerCase());
    }

    public final Enumeration getHeaders(String name) {
        return headers.getAll(name);
    }

    /*
     * It could be that this is meant to be header specific, ie I'm supposed
     * to know if some are hex and others decimal etc. Well fuck em.
     */
    public int getIntHeader(String name) {
        String s = getHeader(name);
        // it is supposed to throw NumberFormat
        return s == null ? -1 : Integer.parseInt(s);
    }

    public long getDateHeader(String name) {
        String s = (String) headers.get(name);
        if (s == null)
            return -1;
        try {
            // FIXME - maybe we should involve the locale?
            DateFormat df = DateFormat.getDateTimeInstance();
            Date d = df.parse(name);
            return d.getTime();
        } catch (ParseException e) {
            throw new IllegalArgumentException(name + 
                                               " didn't contain a date.");
        }
    }

    

    // all this stuff with breaking down the URI paths is really
    // in the province of the container, after parsing
    
    public final String getMethod() {
        return method;
    }
    
    public final String getRequestURI() {
        return requestURI;
    }

    /**
     * Returns the RequestURI with the scheme and hostname stripped. This
     * a new method.
     */
    public final String getRequestPath() {
        return requestPath;
    }

    public final String getQueryString() {
        return queryString;
    }

    public final String getContextPath() {
        return container.getContextPath(requestPath, method);
    }

    public final String getServletPath() {
        return container.getServletPath(requestPath, method);
    }

    public final String getPathInfo() {
        return container.getPathInfo(requestPath, method);
    }


    public final String getPathTranslated() {
        return getPathInfo() == null
            ? null
            : container.getRealPath(getContextPath()
                                    + getServletPath()
                                    + getPathInfo());
    }

    

    /**
     * Returns null.
     */
    public String getRemoteUser() {
        return null;
    }

    /**
     * Do they really need this shit? Returns false.
     */
    public boolean isUserInRole(String role) {
        return false;
    }

    /**
     * Returns null.
     */
    public java.security.Principal getUserPrincipal() {
        return null;
    }

    public String getRequestedSessionId() {
        return (session == null ? 
                null :
                session.getId());
    }

    public HttpSession getSession(boolean create) {
        if (create && session == null) {
            session = new HttpSessionImpl(Long.toString(randSource.nextLong()),
                                          1000 * 60 * 60);
			sessionHolder.putSession(session); //Store the session so that we can refind it later on. TODO: Fix the resulting Memory leak
        }
        return session;
    }
    
    public HttpSession getSession() {
        return getSession(true);
    }

    public boolean isRequestedSessionIdValid() {
        return session != null; // && session.isValid();  FIXME
    }

    /**
     * Returns false, I use cookies.
     */
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    public boolean isRequestedSessionIdFromUrl() {
        return isRequestedSessionIdFromURL();
    }

    public boolean isRequestedSessionIdFromCookie() {
        return true;
    }

}









