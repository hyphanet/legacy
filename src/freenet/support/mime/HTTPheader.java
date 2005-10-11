package freenet.support.mime;

import javax.servlet.http.*;


/*
 * This code is part of fproxy, an HTTP proxy server for Freenet.
 * It is distributed under the GNU Public Licence (GPL) version 2.  See
 * http://www.gnu.org/ for further details of the GPL.
 *
 * Portions of this code are derived from mumail, copyright (C) 1998
 * by Mark Tuempfel and Uli Luckas
 */


/**
 * Adapter between an HttpServletRequest and a MIMEheader
 *
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 **/

public class HTTPheader extends MIMEheader
{
    protected HttpServletRequest req;


    public HTTPheader(HttpServletRequest req) {
	this.req = req;
    }    


    public String get(String header) {
	return req.getHeader(header);
    }
}
