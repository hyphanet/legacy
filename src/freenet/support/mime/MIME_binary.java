package freenet.support.mime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;

import freenet.support.Bucket;
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
 * Reads a MIME binary message from an InputStream
 *
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 **/

public class MIME_binary extends MIME
{
    private Bucket body;

    public MIME_binary(InputStream in, BucketFactory factory)
	throws IOException, MIMEFormatException {
	super(in, factory);
    }


    public MIME_binary(MIMEheader hdr, InputStream in, BucketFactory factory)
	throws IOException, MIMEFormatException {
	super(hdr, in, factory);
    }


    // terminates recursion, since there are no more MIME submessages
    protected void extractBody(InputStream in) throws IOException {
        Bucket tmp = null;
        OutputStream out = null;
        try {
            // initialize
            String encoding = header.get("content-transfer-encoding");
            int length = header.getContent_Length();
            // System.out.println("decoding a " + encoding + ", length " + length);

	    // create bucket to hold body
            tmp = factory.makeBucket(length);
            out = tmp.getOutputStream();

	    // decode
            if (encoding == null) {
                // no encoding, just copy across
                copy(in, out);
            }
            else if (encoding.equals("quoted-printable")) {
                MIMEcoder.decodeQuoted_Printable(in, out);
            }
            else if (encoding.equals("base64")) {
                MIMEcoder.decodeBase64(in, out);
            }
            else if (encoding.equals("8bit")) {
                MIMEcoder.decode8Bit(in, out);
            }
            else {
                // unrecognized encoding, just copy across
                copy(in, out);
            }
            // System.out.println("length after decoding was " + body.size());
            in.close();
            out.close();
            out = null;
            body = tmp;
            tmp = null;
        }
        finally {
            // Clean up resources on exception.
            if (out != null) {
                try { out.close(); } catch (IOException ioe) {}
            }

            if (tmp != null) {
                try { factory.freeBucket(tmp); } catch (IOException ioe) {}
            }
        }
    }


    public Bucket getBody() {
	return body;
    }


    public String getBodyAsString() throws IOException {
	Reader in = new BufferedReader(new InputStreamReader(body.getInputStream()));
	StringWriter out = new StringWriter();
	char[] cbuf = new char[BLOCKSIZE];
	int chars = 0;

	while ((chars = in.read(cbuf, 0, BLOCKSIZE)) != -1) {
	    out.write(cbuf, 0, chars);
	}

	return out.toString();
    }


    public void freeBody() throws IOException {
	factory.freeBucket(body);
    }


    protected void copy(InputStream in, OutputStream out)
	throws IOException {
	byte[] buf = new byte[BLOCKSIZE];
	int bytes = 0;
	while ((bytes = in.read(buf, 0, BLOCKSIZE)) != -1) {
	    out.write(buf, 0, bytes);
	    // System.out.println(bytes + " bytes copied");
	}
    }
}
