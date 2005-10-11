package freenet.support.servlet.http;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Hashtable;
import java.util.StringTokenizer;

import javax.servlet.ServletInputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import freenet.support.Bucket;
import freenet.support.MultiValueTable;
import freenet.support.io.NullOutputStream;
import freenet.support.servlet.BadRequestException;

/**
 * Methods for parsing cookies, query strings, URL encoded strings,
 * form data, etc.
 *
 * @author oskar
 * @author tavin
 */
public abstract class HttpSupport {
    // i should never have named this HttpUtils
    // because of javax.servlet.http.HttpUtils -- which SUCKS

    /**
     * may occur as a parameter value (distinct from empty string) 
     * example query strings:  ?foo        (foo => NULLSTR)
     *                         ?foo=       (foo => "")
     */
    public static final String NULLSTR = new String();

    /**
     * @return  a Cookie parsed from a header field
     */
    public static Cookie parseCookie(String s) {
        int i = s.indexOf('=');
        if (i == -1)
            return null;
        String name = s.substring(0, i);
        
        StringTokenizer st = new StringTokenizer(s.substring(i + 1), ";");
        
        String value;
        if (st.hasMoreTokens()) 
            value = st.nextToken();
        else
            value = "";

        Cookie c = new Cookie(name, value);

        Hashtable h = new Hashtable();
        while (st.hasMoreTokens()) {
            String is = st.nextToken();
            int j = s.indexOf('=');
            if (j != -1) {
		if (j == is.length()) {
		    h.put(is.substring(0, j).trim().toLowerCase(), "");
		} else {
		    h.put(is.substring(0, j).trim().toLowerCase(),
			  is.substring(j + 1).trim());
		}
            }
        }
        
        try {
            if (h.contains("comment"))
                c.setComment((String) h.get("comment"));
            if (h.contains("domain"))
                c.setDomain((String) h.get("domain"));
            if (h.contains("max-age"))
                c.setMaxAge(Integer.parseInt((String) h.get("max-age")));
            if (h.contains("path"))
                c.setPath((String) h.get("path"));
            if (h.contains("secure"))
                c.setSecure("true".equalsIgnoreCase((String) h.get("secure")));
            if (h.contains("version"))
                c.setVersion(Integer.parseInt((String) h.get("version")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed info.");
        }

        return c;
    }

    /**
     * @return  a Cookie converted to its header field representation
     */
    public static String toHeader(Cookie c) {
        StringBuffer sb = new StringBuffer(c.getName());
        sb.append('=').append(c.getValue());
        if (c.getComment() != null)
            sb.append("; Comment=").append(c.getComment());
        if (c.getDomain() != null)
            sb.append("; Domain=").append(c.getDomain());
        if (c.getMaxAge() != -1)
            sb.append("; Max-age=").append(c.getMaxAge());
        if (c.getPath() != null)
            sb.append("; Path=").append(c.getPath());
        if (c.getSecure())
            sb.append("; Secure=").append("true");
        if (c.getVersion() != 0)
            sb.append("; Version=").append(c.getVersion());

        return sb.toString();
    }

    /**
     * Adds the parameters in the query string to the MultiValueTable.
     */
    public static void parseQueryString(String qs, MultiValueTable params) {

        StringBuffer tmpBuf = new StringBuffer();
        
        StringTokenizer st = new StringTokenizer(qs, "&");
        while (st.hasMoreTokens()) {
            String pair = st.nextToken();
            int eq = pair.indexOf('=');
            if (eq == -1) {
                params.put(pair, NULLSTR);
                continue;
            }
            String key = decodeURL(pair.substring(0, eq), tmpBuf);
            String val = decodeURL(pair.substring(eq+1, pair.length()), tmpBuf);
            params.put(key, val);
        }
    }

    /**
     * Adds the parameters in the POST body to the MultiValueTable.
     * Be conscious that the amount of memory consumed will be greater
     * than the number of input bytes by some ridiculous java factor.
     */
    public static void parseFormData(int len, InputStream in,
                                     String charEnc, MultiValueTable params)
                            throws IOException, UnsupportedEncodingException {
        
        if (len <= 0) return;
        
        byte[] inputBytes = new byte[len];
        (new DataInputStream(in)).readFully(inputBytes, 0, len);  // might throw
                                                                  // EOFException
        // FIXME - what about multipart form data

        String formData = (charEnc == null ? new String(inputBytes, 0, len)
                                           : new String(inputBytes, 0, len, charEnc));
        parseQueryString(formData, params);
    }

    /**
     * Decodes a multipart document 
     * @param attributes  The attributes line to the Content-type
     * @param buckets     An array of the buckets to save parts in, for each 
     *                    part, the method will attempt to find a bucket with
     *                    the same name (modulo case).
     */
    public static void readMultipart(String attributes, Bucket[] buckets, 
                                     ServletInputStream source)
        throws IOException, BadRequestException {
        
        String boundary = getAttribute(attributes, "boundary");
        byte[] tbs = boundary.getBytes();
        byte[] bbs = new byte[tbs.length + 4];
        System.arraycopy(tbs, 0, bbs, 2, tbs.length);
        bbs[bbs.length - 2] = (bbs[0] = (byte) '\r');
        bbs[bbs.length - 1] = (bbs[1] = (byte) '\n');
        byte[] buff = new byte[bbs.length];
        int buffp = 0, i;
        MultiValueTable headers = new MultiValueTable();
        part: while (true) {
            headers.clear();
            parseHttpHeaders(headers, source);
            String cd = (String) headers.get("content-disposition");
            String pname;
            OutputStream out = null;
            if (cd != null && (pname = getAttribute(cd, "name")) != null) {
                for (int j = 0 ; j < buckets.length ; j++) {
                    if (pname.equalsIgnoreCase(buckets[j].getName())) {
                        out = buckets[j].getOutputStream();
                        break;
                    }
                }
            }
            if (out == null)
                out = new NullOutputStream();
            buffer: while (true) {
                i = source.read(); // I should use array read, but...
                if (i == -1) {
                    out.write(buff, 0, buffp);
                    return;
                } else if (i == '\r') {
                    out.write(buff, 0, buffp);
                    buff[0] = (byte) i;
                    buffp = 1;
                    while (buffp < bbs.length) {
                        i = source.read();
                        if (i == -1) {
                            out.write(buff, 0, buffp);
                            return;
                        } else {
                            buff[buffp] = (byte) i;
                            if (buff[buffp] != bbs[buffp]) {
                                buffp++;
                                continue buffer;
                            } else {
                                buffp++;
                            }
                        }
                    }
                    // they all matched
                    buffp = 0;
                    continue part;
                } else {
                    if (buffp < buff.length) {
                        buff[buffp] = (byte) i;
                        buffp++;
                    } else {
                        out.write(buff, 0, buff.length);
                        buffp = 0;
                    }
                }
            }
        }
    }

    /**
     * Parses headers in Http format into the fieldset, terminating at an
     * empty line. All header names are converted to lower case, and values
     * are trimmed of spaces.
     */
    static void parseHttpHeaders(MultiValueTable headers, 
                                 ServletInputStream in) 
        throws IOException, BadRequestException {
     
        byte[] buffer = new byte[0x1000];
        // read headers
        while (true) {
            String header = readHeader(in, buffer).trim();
            if (header.length()==0) break;
            
            int i = header.indexOf(':');
            if (i != -1) {
                headers.put(header.substring(0, i).trim().toLowerCase(),
                            i <header.length()-1 ? header.substring(i+1).trim()
                            : "");
            }
        }
        
    }

    
    static String readHeader(ServletInputStream in, byte[] buffer)
        throws BadRequestException, IOException {
        // Maybe some day I will adjust this code to read again if 
        // it reaches the end of the buffer, but for now 65 k lines
        // are not allowed...
        int len = in.readLine(buffer, 0, buffer.length);
        if (len < 1)
            throw new BadRequestException("no headers");
        
        if (buffer[--len] == (byte) '\n') {
            if (len > 0 && buffer[len - 1] == (byte) '\r')
                --len;
        }
        else throw new BadRequestException("header exceeded max length");
        
        return new String(buffer, 0, len);
    }

    
    public static String getAttribute(String attribs, String name) {
        StringTokenizer st = new StringTokenizer(attribs, ";");
        while (st.hasMoreElements()) {
            String el = st.nextToken().trim();
            int ind = el.indexOf('=');
            if (ind != -1
                && el.substring(0, ind).trim().equalsIgnoreCase(name)) {
		return el.substring(ind+1, el.length()).trim();
            }
        }
        return null;
    }


    /**
     * Parses a URL encoded String using the supplied StringBuffer
     * for temporary processing.
     */
    public static String decodeURL(String s, StringBuffer sb) {
        sb.setLength(0);
        for (int i=0; i<s.length(); ++i) {
            char c = s.charAt(i); 
            switch (c) {
                case '+':
                    sb.append(' ');
                    break;
                case '%':
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i+1, i+3), 16));
                        i += 2;
                    }
                    catch (Exception e) {
                        sb.append('%');  // fuck em
                    }
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * @return  the URL a client would use to cause the given HttpServletRequest
     *
     * Returned as a StringBuffer so query strings can be added easily
     * (nice trick from the Apache team).
     */
    public static StringBuffer getRequestURL(HttpServletRequest req) {

        String reqURI = req.getRequestURI();
        String scheme = req.getScheme();
        
        StringBuffer url = new StringBuffer();
        url.append(scheme);
        url.append("://");
        
        if (reqURI.startsWith(url.toString()))
            return new StringBuffer(reqURI);
        
        url.append(req.getServerName());
        
        int port = req.getServerPort();
        if ((scheme.equals("http") && port != 80)
                || (scheme.equals("https") && port != 443)) {
            url.append(':');
            url.append(port);
        }
        
        url.append(reqURI);
        return url;
    }
}



