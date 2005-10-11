package freenet.support.mime;

import java.io.IOException;
import java.io.InputStream;

import freenet.support.BucketFactory;


/*
 * This code is part of fproxy, an HTTP proxy server for Freenet.
 * It is distributed under the GNU Public Licence (GPL) version 2.  See
 * http://www.gnu.org/ for further details of the GPL.
 *
 * Portions of this code are derived from mumail, copyright (C) 1998
 * by Mark Tuempfel and Uli Luckas
 */


/**
 * Reads a MIME mail message from an InputStream
 *
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 **/

public class MIME_mail extends MIME
{
    MIME body;

    public MIME_mail(InputStream in, BucketFactory factory)
	throws IOException, MIMEFormatException {
	super(in, factory);
    }

    public MIME_mail(MIMEheader hdr, InputStream in, BucketFactory factory)
	throws IOException, MIMEFormatException {
	super(hdr, in, factory);
    }

    protected void extractBody(InputStream in)
	throws IOException, MIMEFormatException {
	// recurse once to get the MIME submessage
	// effectively, body = new MIME(buf, start, end),
	// except you can't since MIME is abstract
	body = constructMIME(in);
    }


    public MIME getBody() {
	return body;
    }
}
