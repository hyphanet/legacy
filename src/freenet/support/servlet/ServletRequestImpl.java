package freenet.support.servlet;

import freenet.*;
import freenet.interfaces.servlet.*;
import freenet.support.*;
import javax.servlet.*;
import java.util.*;
import java.io.*;

/**
 * Implementation of javax.servlet.ServletRequest
 *
 * @author oskar
 */

public class ServletRequestImpl implements ServletRequest {

    protected final ServletContainer container;
    protected final Connection conn;

    protected int contentLength;
    protected String charEncoding;
    protected String contentType;

    protected int bufferSize;
    protected ServletInputStream binaryInput;
    protected BufferedReader readerInput;


    /** request specific attributes (system/environment) */
    private Hashtable attr = new Hashtable();

    /** request specific parameters (i.e., query string, form data, etc.) */
    protected MultiValueTable params = new MultiValueTable();

    
    /**
     * Okay, it can just have the bloody Connection itself..
     */
    public ServletRequestImpl(ServletContainer container, Connection conn,
                              int bufferSize, int contentLength,
                              String charEncoding, String contentType) {
        this.container = container;
        this.conn = conn;
        this.bufferSize = bufferSize;
        this.contentLength = contentLength;
        this.charEncoding = charEncoding;
        this.contentType = contentType;
        
        // Keep the connection as an attribute so that 
        // servlets that do resource intensive operations
        // can poll the connection to see if the user has
        // aborted by dropping it.
        //
        // e.g. User hits the back button in the middle of
        //      a 200Mb split file insertion.  We want to
        //      stop inserting chunks immediately.
        //
        setAttribute("freenet.Connection", conn);
    }

    public ServletRequestImpl(ServletContainer container, Connection conn,
                              int bufferSize) {
        this(container, conn, bufferSize, -1, null, null);
    }

    /**
     * The servlet APIs insist you can only return one or the other.
     */
    public ServletInputStream getInputStream() {
        if (readerInput != null)
            throw new IllegalStateException("already got BufferedReader");
        else if (binaryInput != null)
            return binaryInput;
        else
            return binaryInput =
                new ServletInputStreamImpl(
                    bufferSize > 0
                    ? new BufferedInputStream(conn.getIn(), bufferSize)
                    : conn.getIn());
    }

    /**
     * The servlet APIs insist you can only return one or the other.  */
    public BufferedReader getReader() throws IOException {
        if (binaryInput != null)
            throw new IllegalStateException("already got ServletInputStream");
        else if (readerInput != null)
            return readerInput;
        else
            return readerInput =
                bufferSize > 0
                ? new BufferedReader(getISR(), bufferSize)
                : new BufferedReader(getISR());
    }

    private final InputStreamReader getISR() throws UnsupportedEncodingException {
        return charEncoding == null ? new InputStreamReader(conn.getIn())
                                    : new InputStreamReader(conn.getIn(), charEncoding);
    }

    public Object getAttribute(String s) {
        return attr.get(s);
    }

    public Enumeration getAttributeNames() {
        return attr.keys();
    }

    public void setAttribute(String s, Object o) {
        attr.put(s, o);
    }

    public void removeAttribute(String s) {
        attr.remove(s);
    }

    public String getCharacterEncoding() {
        return charEncoding;
    }

    public int getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public String getParameter(String s) {
        return (String) params.get(s);
    }

    public Enumeration getParameterNames() {
        return params.keys();
    }

    public String[] getParameterValues(String s) {
        Object[] o = params.getArray(s);
        if (o == null)
            return null;
        String[] r = new String[o.length];
        System.arraycopy(o, 0, r, 0, o.length);
        return r;
        
        // i know, go figure... ???
        //return (String[]) params.getArray(s);
    }

    /**
     * Subclasses should override.
     */ 
    public String getProtocol() {
        return "";
    }

    /**
     * Subclasses should override.
     */ 
    public String getScheme() {
        return "";
    }


    // pretty tied to TCP/IP here..
    
    public String getServerName() {
        String addr = conn.getMyAddress().getValString();
        int co = addr.indexOf(':');
        return co == -1 ? addr : addr.substring(0, co);
    }

    public int getServerPort() {
        String addr = conn.getMyAddress().getValString();
        int co = addr.indexOf(':');
        return co == -1 ? 0 : Integer.parseInt(addr.substring(co+1, addr.length()));
    }

    public String getRemoteHost() {
	Address a = conn.getPeerAddress();
	if(a == null) return null;
        String addr = a.getValString();
        int co = addr.indexOf(':');
        return co == -1 ? addr : addr.substring(0, co);
    }

    public String getRemoteAddr() {
        try {
	    String s = getRemoteHost();
	    if(s == null) return null;
            return java.net.InetAddress.getByName(s).getHostAddress();
        }
        catch (java.net.UnknownHostException e) {
            // what??!
            return "";
        }
    }

    
    /**
     * Default implementation returns systems default.
     */
    public Locale getLocale() {
        return Locale.getDefault();
    }

    /**
     * Default implementation returns only system default.
     */
    public Enumeration getLocales() {
        return new LimitedEnumeration(Locale.getDefault());
    }

    /**
     * Default implementation returns your gifts,
     * but not your calls.
     */
    public final boolean isSecure() {
        // so it's ugly.  it blends.
        return conn.getTransport().getName().equalsIgnoreCase("ssl");
    }

    // WTF is this? stupid shit.
    public RequestDispatcher getRequestDispatcher(String name) {
        // FIXME - if it's relative we have to add the absolute part
        return container.getRequestDispatcher(name);
    }

    /** @deprecated */
    public String getRealPath(String s) {
        return container.getRealPath(s);
    }

    /**
     * Adds a parameter value
     */
    protected void setParameterValue(String name, String val) {
        params.put(name, val);
    }
}

