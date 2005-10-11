package freenet.support.mime;

import java.io.*;


/*
 * This code is part of fproxy, an HTTP proxy server for Freenet.
 * It is distributed under the GNU Public Licence (GPL) version 2.  See
 * http://www.gnu.org/ for further details of the GPL.
 *
 * Portions of this code are derived from mumail, copyright (C) 1998
 * by Mark Tuempfel and Uli Luckas
 */


public class MIMEcoder {
    static final int toBase64Table[] = {
	'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
	'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
	'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd',
	'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n',
	'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x',
	'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7',
	'8', '9', '+', '/'
    };
    static int fromBase64Table[] = new int[256];

    static {
	int i;

	for ( i = 0; i < 256; i++) {
	    fromBase64Table[i] = -1;
	}
	for ( i = 0; i < 64; i++) {
	    fromBase64Table[toBase64Table[i]] = i;
	}
    }

    public static void decodeQuoted_Printable(InputStream in, OutputStream out) {
	return;

	/*
	int encoded = -1;
	int                   i, j, l;
	boolean               softLineBreak = false;

	while ((encoded = in.read()) != -1) {
	    if (encoded != '=') {
		out.write(encoded);
	    }
	    else 

	for (i = startIndex; i < stopIndex; i++) {
	    l = Line[i].length();
	    for (j = 0; j < l; j++) {
		if (Line[i].charAt(j) != '=') {
		    buffer.write((int) Line[i].charAt(j));
		} else if (j == l - 1) {
		    softLineBreak = true;
		} else if (j == l - 2) {
		    buffer.write((int) Line[i].charAt(j));
		} else {
		    buffer.write(Integer.valueOf(Line[i].substring(j + 1, j + 3).toLowerCase(), 16).intValue());
		    j+= 2;
		}
	    }
	    if (softLineBreak) {
		softLineBreak = false;
	    } else {
		buffer.write('\r'); buffer.write('\n');
	    }
	}
	*/
    }


    public static void decodeBase64(InputStream in, OutputStream out)
	throws IOException {
	int encoded = -1;
	int value = 0;
	int bits = 0;

	while ((encoded = in.read()) != -1) {
	    // look for encapsulation boundary
	    if (encoded == '-') break;

	    // discard whitespace and extraneous characters (including '=')
	    if (fromBase64Table[encoded] == -1) continue;

	    // accumulate next 6 encoded bits
	    value = (value << 6) | fromBase64Table[encoded];
	    bits += 6;
	    if (bits >= 8) {
		// got enough bits to write a full byte
		bits -= 8;
		out.write(value >> bits);
		value &= ((1 << bits) - 1);
	    }
	}
    }


    public static void decode8Bit(InputStream in, OutputStream out) {
	return;

	/*
	int i, j, l;
	ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	PrintWriter pw = new PrintWriter(buffer);

	for (i = startIndex; i < stopIndex; i++) {
	    pw.println(Line[i]);
	}
	pw.flush();

	return buffer.toByteArray();
	*/
    }

    public static void decodeBinary(InputStream in, OutputStream out) {
	return;

	/*
	int i, j, l;
	ByteArrayOutputStream buffer = new ByteArrayOutputStream();

	for (i = startIndex; i < stopIndex; i++) {
	    // put CRLF between lines, not after them,
	    // so as not to write an extra CRLF at the end
	    if (i > startIndex) {
		buffer.write('\r'); buffer.write('\n');
	    }

	    l = Line[i].length();
	    for (j = 0; j < l; j++) {
		buffer.write((int) Line[i].charAt(j));
	    }
	}
    
	return buffer.toByteArray();
	*/
    }
}

