package freenet.support.mime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.http.HttpServletRequest;

import freenet.Core;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Logger;


/*
 * This code is part of fproxy, an HTTP proxy server for Freenet.
 * It is distributed under the GNU Public Licence (GPL) version 2.  See
 * http://www.gnu.org/ for further details of the GPL.
 *
 * Portions of this code are derived from mumail, copyright (C) 1998
 * by Mark Tuempfel and Uli Luckas
 */


/**
 * Reads a MIME message from an InputStream
 *
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 **/

public abstract class MIME
{
    // buffer size to use while scanning for boundaries
    protected static int BLOCKSIZE = 65536;
    protected MIMEheader header = null;

    protected BucketFactory factory = null;
    protected Logger logger = Core.logger;

    //@ Constructor if called directly on an InputStream
    public MIME(InputStream in, BucketFactory factory) throws IOException, MIMEFormatException {
        this.factory = factory;
	// parse header
	header = new MIMEheader(in);
	// System.out.println("Read header length " + header.length);
	// header.getProperties().list(System.out);
	extractBody(in);
    }


    //@ Constructor if called on an InputStream whose headers have
    //@ been read already by an HttpServlet
    public MIME(InputStream in, HttpServletRequest req, BucketFactory factory)
	throws IOException, MIMEFormatException {
        this.factory = factory;
	header = new HTTPheader(req);
	extractBody(in);
    }


    //@ Constructor if called to parse a submessage
    public MIME(MIMEheader hdr, InputStream in, BucketFactory factory)
	throws IOException, MIMEFormatException {

        this.factory = factory;

	// header was read for us already by constructMIME
	header = hdr;

	// unpack body
	extractBody(in);
    }


    //@ extraction is subclass-dependent
    protected abstract void extractBody(InputStream in)
	throws IOException, MIMEFormatException;


    //@ effectively a way of calling new MIME() even though MIME is
    //@ abstract -- we return whatever subclass is appropriate
    protected MIME constructMIME(Bucket b)
	throws IOException, MIMEFormatException {
        // Does every single code path really close the
        // InputStream? e.g. on exception.
        // If not, Buckets are hanging around longer than
        // they should.
	// return constructMIME(new FreeBucketInputStream(b.getInputStream(), factory, b));
        InputStream in = null;
        MIME ret = null;
        try {
            in = b.getInputStream();
            ret = constructMIME(in);
            in.close();
            in = null;
        }
        finally {
            if (in != null) {
                try { in.close(); } catch (IOException ioe) {} 
            }
        }
        return ret;
    }


    //@ effectively a way of calling new MIME() even though MIME is
    //@ abstract -- we return whatever subclass is appropriate
    protected MIME constructMIME(InputStream in)
	throws IOException, MIMEFormatException {
	// parse header
	MIMEheader subheader = new MIMEheader(in);
	// System.out.println("Read subheader length " + subheader.length);
	// subheader.getProperties().list(System.out);

	// unpack body according to type
	String majorType = subheader.getMajorContent_Type();
	// System.out.println("unpacking a " + majorType);
	if (majorType == null) {
	    return new MIME_binary(subheader, in, factory);
	}
	else if (majorType.equals("message")) {
	    return new MIME_mail(subheader, in, factory);
	}
	else if (majorType.equals("multipart")) {
	    return new MIME_multipart(subheader, in, factory);
	}
	else if (majorType.equals("application")) {
	    return new MIME_binary(subheader, in, factory);
	}
	else if (majorType.equals("text")) {
	    return new MIME_binary(subheader, in, factory);
	    // return new MIME_text(subheader, buf, start + subheader.length, end);
	}

	return new MIME_binary(subheader, in, factory);
    }


    public MIMEheader getHeader() {
	return header;
    }


    //@ test harness
    // FIXME: fix this to work with changes to Logger and uncomment
//    public static void main(String[] args)
//	throws IOException, MIMEFormatException {
//
//	// read MIME multipart message from stdin
//	Core.logger.addHook(new FileLoggerHook(System.out, null, null, 
//                                               Logger.DEBUG));
//
//        // REDFLAG: Doesn't clean up temp files.
//	MIME_multipart m = new MIME_multipart(System.in, new TempBucketFactory());
//
//	// recursively write it out
//	writeMIME(m, "1");
//    }


    public static void writeMIME(MIME part, String index) throws IOException {
	System.out.println("writing part " + index);

	// write parts to file part...{index}
	if (part instanceof MIME_mail) {
	    writeMIME(((MIME_mail) part).getBody(), index + ".1");
	}
	else if (part instanceof MIME_multipart) {
	    MIME_multipart p = (MIME_multipart) part;
	    for (int j = 0; j < p.getPartCount(); j++) {
		writeMIME(p.getPart(j), index + "." + (j+1));
	    }
	}
	else if (part instanceof MIME_binary) {
	    writeBinary((MIME_binary) part, index);
	}
    }


    public static void writeBinary(MIME_binary part, String index)
	throws IOException {
	InputStream in = new BufferedInputStream(part.getBody().getInputStream());
	OutputStream out = new BufferedOutputStream(new FileOutputStream(new File("part" + index)));
	
	int b;
	while ((b = in.read()) != -1)
	    out.write(b);
	out.close();
	part.freeBody();
    }
}
