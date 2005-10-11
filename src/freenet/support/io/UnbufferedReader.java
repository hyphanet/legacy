package freenet.support.io;
import java.io.*;


/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/


/**
 * This class provides a reasonable approximation of the BufferedReader's
 * readLine() method, without doing the buffering.  This is useful for
 * mixing line-oriented operations on this class with byte-oriented
 * operations on the underlying InputStream without the buffering
 * messing up the order of the bytes that you get.
 *
 * <p> Of course, buffering is generally <b>a good thing</a>(TM).  So
 * wrap this around a BufferedInputStream already.
 *
 * <p> Note: InputStreamReader also performs unwanted buffering, so
 * this class is intended to replace both InputStreamReader and
 * BufferedReader.  In particular, its constructor takes an
 * InputStream as an argument, not a Reader.
 *
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 **/

public class UnbufferedReader
{
    public int bytesRead = 0;

    private InputStream in;
    private boolean strict;

    /**
     * Constructs an UnbufferedReader in "lenient" mode. Lenient mode
     * means that lines are terminated by LF or by CRLF.
     *
     * @param in the InputStream to read from.
     */
    
    public UnbufferedReader(InputStream in) {
	this(in, false);
    }

    /**
     * Constructs an UnbufferedReader in "strict" mode. Strict mode
     * means that lines are terminated by CRLF only.
     *
     * @param in the InputStream to read from.
     */
    
    public UnbufferedReader(InputStream in, boolean strict) {
	this.in = in;
	this.strict = strict;
    }


    /**
     * Read a line of text.
     *
     * A line is considered to be terminated by a carriage return
     * ('\r') followed immediately by a linefeed ('\n'), or a single
     * line feed by itself.  In strict mode, only CRLF is used,
     * permitting the byte version to be exactly reconstructed from
     * the line version.  Note: the BufferedReader version terminates
     * on a single carriage return as well.
     *
     * @return  A String containing the contents of the line, not including
     * any line-termination characters, or null if the end of the stream has
     * been reached.
     *
     * @throws   IOException  If an I/O error occurs
     **/
    public String readLine() throws IOException {
	StringBuffer buf = new StringBuffer();
	boolean crPrevious = false;
	int i;

	// read to LF, CRLF, or EOF
	while ((i = in.read()) != -1) {
	    bytesRead++;
	    if (i == '\012' && (!strict || crPrevious))
		break;
	    buf.append((char) i);
	    crPrevious = (i == '\015');
	}

	// did we read anything at all?
	if (bytesRead == 0) {
	    return null;
	}

	// chop trailing CR, if any
	String result = buf.toString();
	if (crPrevious) {
	    return result.substring(0, result.length()-1);
	}
	else {
	    return result;
	}
    }
}
