package freenet.support.mime;

import freenet.support.io.*;

import java.io.*;
import java.util.*;


/*
 * This code is part of fproxy, an HTTP proxy server for Freenet.
 * It is distributed under the GNU Public Licence (GPL) version 2.  See
 * http://www.gnu.org/ for further details of the GPL.
 *
 * Portions of this code are derived from mumail, copyright (C) 1998
 * by Mark Tuempfel and Uli Luckas
 */


/**
 * Reads a MIME header from an InputStream
 *
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 **/

public class MIMEheader
{
    public int length = 0;
    protected Properties headers = null;

    public MIMEheader() {
    }

    //@ Constructor if called directly on an InputStream
    public MIMEheader(InputStream in) throws IOException {
	UnbufferedReader r = new UnbufferedReader(in);
	String line = null;
	String header = null;
	String value = null;
	char c = '\0';
	int i = -1;

	// read header from input stream
	headers = new Properties();
	while ((line = r.readLine()) != null && line.length() > 0) {
	    if ((c = line.charAt(0)) != ' ' && c != '\t') {
		// put previous header, if any
		if (header != null) {
		    headers.put(header.toLowerCase(), value);
		}

		// start new header
		if ((i = line.indexOf(':')) != -1) {
		    // sanity check
		    header = line.substring(0, i);
		    value = line.substring(i+1).trim();
		}
	    }
	    else {
		// accumulate header
		value = value + " " + line.trim();
	    }
	}

	// put last header
	if (header != null) {
	    headers.put(header.toLowerCase(), value);
	}

	// save header length
	length = r.bytesRead;
    }
    

    //@ Constructor if called to parse a submessage
    public MIMEheader(byte[] buf, int start, int end) throws IOException {
	this(new ByteArrayInputStream(buf, start, end-start));
    }


    public String get(String header) {
	return headers.getProperty(header);
    }


    public Properties getProperties() {
	return headers;
    }


    public String getContent_Type() {
	String contentTypeField = get("content-type");
	if (contentTypeField == null)
	    return null;

	String contentType;
	int i = contentTypeField.indexOf(';');

	if (i == -1) {
	    contentType = contentTypeField.trim().toLowerCase();
	} else {
	    contentType = contentTypeField.substring(0,i).trim().toLowerCase();
	}

	if (contentType.length()==0) {
	    contentType = "application/unspecified";
	}

	return contentType;
    }


    public String getMajorContent_Type(){ 
	String contentType = getContent_Type();
	if (contentType == null)
	    return null;

	String majorType;
	int i = contentType.indexOf('/');

	if (i == -1) {
	    majorType = contentType;
	} else {
	    majorType = contentType.substring(0, i);
	}

	return majorType;
    }		


    public String getMinorContent_Type(){ 
	String contentType = getContent_Type();
	if (contentType == null)
	    return null;

	String minorType;
	int i = contentType.indexOf('/');

	if (i == -1) {
	    minorType = "";
	} else {
	    minorType = contentType.substring(i + 1, contentType.length());
	}

	return minorType;
    }		


    public String getContent_TypeParameter(String paramName) {
	return getFieldParameter("content-type", paramName);
    }

    public String getContent_DispositionParameter(String paramName) {
	return getFieldParameter("content-disposition", paramName);
    }

    public String getFieldParameter(String fieldName, String paramName) {
	String Field = get(fieldName);
	if (Field == null) {
	    return null;
	}

	String Value = "";
	boolean found = false;
	int i = Field.indexOf(';');
	int j;
    
	while (!(found || (i == -1))) {
	    Field = Field.substring(i + 1).trim();
	    if (Field.startsWith(paramName)) {
		Value = Field.substring(paramName.length()).trim();
		if (Value.startsWith("=")) {
		    found = true;
		    Value = Value.substring(1).trim();
		    if (Value.startsWith("\"")) {
			j = Value.indexOf("\"", 1);
			if ( j != -1) {
			    Value = Value.substring(1, j);
			}
		    } else {
			j = Value.indexOf(';');
			if (j != -1) {
			    Value = Value.substring(0, j).trim();
			}
		    }
		}
	    }
	    i = Field.indexOf(';');
	}
	return Value;
    }

  
    public int getContent_Length() {
	String contentLength = get("content-length");
	if (contentLength != null)
	    return Integer.parseInt(contentLength);
	else
	    return 0;
    }
}
