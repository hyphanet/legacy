package freenet.support.mime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;

import freenet.support.BoyerMoore;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Logger;

/*
 * This code is part of fproxy, an HTTP proxy server for Freenet. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 *
 * Portions of this code are derived from mumail, copyright (C) 1998 by Mark
 * Tuempfel and Uli Luckas
 */

/**
 * Reads a MIME multipart message from an InputStream
 *
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 */
public class MIME_multipart extends MIME {
    private Vector parts;

    public MIME_multipart(InputStream in, BucketFactory factory)
	throws IOException, MIMEFormatException {
	super(in, factory);
    }

	public MIME_multipart(
		InputStream in,
		HttpServletRequest req,
		BucketFactory factory)
	throws IOException, MIMEFormatException {
	super(in, req, factory);
    }

	public MIME_multipart(
		MIMEheader hdr,
		InputStream in,
		BucketFactory factory)
	throws IOException, MIMEFormatException {
	super(hdr, in, factory);
    }

    // Doesn't close in.
	protected void extractBody(InputStream in)
		throws IOException, MIMEFormatException {
	int length = header.getContent_Length();
	if(logger.shouldLog(Logger.DEBUG,this))
	    logger.log(this, "Reading "+length+" bytes", Logger.DEBUG);
	
	// initialize
	parts = new Vector();
	byte[] buf = new byte[BLOCKSIZE];
		byte[] boundary =
			("--" + header.getContent_TypeParameter("boundary")).getBytes();
	BoyerMoore bm = new BoyerMoore(boundary);
	Bucket b = null;
	OutputStream out = null;
	boolean firstBoundary = true;
	boolean matchFromPartial = false;
	int bytes = 0;
	int partial = 0;
	int thisBound = -1;
	int nextBound = -1;
	boolean endFound = false;

        try {
            // read blocks
	read: while ((bytes = in.read(buf, 0, BLOCKSIZE)) >= 0) {
	    logger.log(this, bytes + " bytes read", Logger.DEBUG);
	    matchFromPartial = false;

	    // check saved partial match, if any
	    if (partial > 0) {
		int i;
					for (i = 0;
						i < bytes && i < boundary.length - partial;
						i++) {
		    if (boundary[partial+i] != buf[i])
			break;
		}

		if (i == boundary.length-partial) {
		    // match found
		    matchFromPartial = true;
		    thisBound = i;
					} else if (i == bytes) {
		    // continue partial match to next block
		    partial += i;
		    continue;
					} else {
		    // no match; write out saved bytes
		    for (int j = 0; j < partial; j++)
			out.write(boundary[j]);
		    thisBound = 0;
		}
				} else {
		thisBound = 0;
	    }
	    
	    // read bounded parts in this block
				while (matchFromPartial
					|| (nextBound = bm.search(buf, thisBound, bytes)) >= 0) {

		// unpack data read so far, except ignore data before
		// the first boundary
		if (!firstBoundary) {
		    // write out remaining bytes, if any
		    if (!matchFromPartial)
			out.write(buf, thisBound, nextBound-thisBound);
		    MIME part = constructMIME(b);
		    parts.addElement(part);
		}

		// skip over the boundary itself
		if (!matchFromPartial)
		    thisBound = nextBound + boundary.length;

		// peek at next two bytes
		int byte1 = 0;
		int byte2 = 0;
		if (thisBound < bytes)
		    byte1 = buf[thisBound++];
		else
		    byte1 = in.read();
		if (thisBound < bytes)
		    byte2 = buf[thisBound++];
		else
		    byte2 = in.read();

		// check for end of message
		if (byte1 == '-' && byte2 == '-') {
		    // trailing -- indicates last boundary
		    logger.log(this, "End boundary found", Logger.DEBUG);
		    endFound = true;
		    break read;
					} else if (byte1 == -1 || byte2 == -1) {
		    // end of stream
						logger.log(
							this,
							"End of message encountered",
							Logger.DEBUG);
		    break read;
		}

		// start next part
		// size is at most the size of this message (i.e. `length')

                // Hmmm... not sure that this is required,
                if (out != null) {
                    //System.err.println("Closing out.");
                    out.close();
                    out = null;
                }
                if (b != null) {
                    //System.err.println("Freeing old bucket.");
                    factory.freeBucket(b);
                }

                //System.err.println("Making bucket.");
                b = factory.makeBucket(length);
                //System.err.println("Opening out.");
		out = b.getOutputStream();
                //System.err.println("Opened out.");

		// write peeked bytes, unless they are CRLF
		if (byte1 != '\015' || byte2 != '\012') {
		    out.write(byte1);
		    out.write(byte2);
		}
		    
		// prepend CRLF to subsequent boundaries if this is
		// the first one
		if (firstBoundary) {
		    byte[] newbound = new byte[boundary.length+2];
		    newbound[0] = (byte) 13; 
		    newbound[1] = (byte) 10;
						System.arraycopy(
							boundary,
							0,
							newbound,
							2,
				     boundary.length);
		    boundary = newbound;
		    bm.compile(boundary);
		    firstBoundary = false;
		}

		matchFromPartial = false;
	    }

	    // check for trailing partial match
	    partial = bm.partialMatch();

	    // no boundary found
	    // write this piece (except for any trailing partial match)
	    // and continue to next block
	    if (b != null && thisBound < bytes) {
		out.write(buf, thisBound, bytes-thisBound-partial);
	    }
	}
        } // why is my emacs indentation messed up here?
        finally {
            // Free temp Bucket as soon as
            // we are done with it.
            if (out != null) {
				try {
					out.close();
				} catch (IOException e) {
				}
                out = null;
            }
            if (b != null) {
				try {
					factory.freeBucket(b);
				} catch (IOException e) {
				}
                b = null;
            }
        }
	// cleanup
	// REDFLAG: This causes FproxyServlet.doPost to croak.
	//in.close(); 

	if (firstBoundary) {
			throw new MIMEFormatException(
				"MIME_multipart: starting boundary not found: "
					+ new String(boundary));
	}

	if (!endFound) {
			throw new MIMEFormatException(
				"MIME_multipart: ending boundary not found: "
					+ new String(boundary)
					+ "--");
	}
    }

    public int getPartCount() {
	return parts.size();
    }
  
    public MIME getPart(int i) {
	if (i < 0 || i >= getPartCount()) {
	    //IllegalArgumentException 
	    return null;
		} else {
	    return (MIME) parts.elementAt(i);
	}
    }  
}
