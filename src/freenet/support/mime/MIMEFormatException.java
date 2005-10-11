package freenet.support.mime;

/*
 * This code is part of fproxy, an HTTP proxy server for Freenet.
 * It is distributed under the GNU Public Licence (GPL) version 2.  See
 * http://www.gnu.org/ for further details of the GPL.
 *
 * Portions of this code are derived from mumail, copyright (C) 1998
 * by Mark Tuempfel and Uli Luckas
 */


/**
 * Thrown when trying to decode a MIME body which is somehow invalid.
 **/

public class MIMEFormatException extends Exception {
    MIMEFormatException () {}
    MIMEFormatException (String s) { super(s); }
}
