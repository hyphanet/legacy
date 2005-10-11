// Copyright 2000, Mr. Bad of Pigdog Journal (http://www.pigdog.org/).
// Copyright 2002 Robert Bihlmeyer
// All Rights Reserved.

// This software is distributed under the GNU Public License, which
// should have come with this file.

// THIS PACKAGE IS PROVIDED "AS IS" AND WITHOUT ANY EXPRESS OR
// IMPLIED WARRANTIES, INCLUDING, WITHOUT LIMITATION, THE IMPLIED
// WARRANTIES OF MERCHANTIBILITY AND FITNESS FOR A PARTICULAR PURPOSE.

package freenet.client.metadata;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Hashtable;

import freenet.Core;
import freenet.support.Logger;

public class MimeTypeUtils {

    static Hashtable typeMap = new Hashtable();

    static String MIME_TYPES = "/etc/mime.types";

    // Ruthlessly ripped off from apache mime types.

    static {
	typeMap.put("csm", "application/cu-seeme");
	typeMap.put("cu", "application/cu-seeme");
	typeMap.put("tsp", "application/dsptype");
	typeMap.put("xls", "application/excel");
	typeMap.put("spl", "application/futuresplash");
	typeMap.put("hqx", "application/mac-binhex40");
	typeMap.put("doc", "application/msword");
	typeMap.put("dot", "application/msword");
	typeMap.put("bin", "application/octet-stream");
	typeMap.put("oda", "application/oda");
	typeMap.put("pdf", "application/pdf");
	typeMap.put("asc", "application/pgp-keys");
	typeMap.put("pgp", "application/pgp-signature");
	typeMap.put("ps", "application/postscript");
	typeMap.put("ai", "application/postscript");
	typeMap.put("eps", "application/postscript");
	typeMap.put("ppt", "application/powerpoint");
	typeMap.put("rtf", "application/rtf");
	typeMap.put("wp5", "application/wordperfect5.1");
	typeMap.put("zip", "application/zip");
	typeMap.put("wk", "application/x-123");
	typeMap.put("bcpio", "application/x-bcpio");
	typeMap.put("pgn", "application/x-chess-pgn");
	typeMap.put("cpio", "application/x-cpio");
	typeMap.put("deb", "application/x-debian-package");
	typeMap.put("dcr", "application/x-director");
	typeMap.put("dir", "application/x-director");
	typeMap.put("dxr", "application/x-director");
	typeMap.put("dvi", "application/x-dvi");
	typeMap.put("pfa", "application/x-font");
	typeMap.put("pfb", "application/x-font");
	typeMap.put("gsf", "application/x-font");
	typeMap.put("pcf", "application/x-font");
	typeMap.put("pcf.Z", "application/x-font");
	typeMap.put("gtar", "application/x-gtar");
	typeMap.put("tgz", "application/x-gtar");
	typeMap.put("hdf", "application/x-hdf");
	typeMap.put("phtml", "application/x-httpd-php");
	typeMap.put("pht", "application/x-httpd-php");
	typeMap.put("php", "application/x-httpd-php");
	typeMap.put("php3", "application/x-httpd-php3");
	typeMap.put("phps", "application/x-httpd-php3-source");
	typeMap.put("php3p", "application/x-httpd-php3-preprocessed");
	typeMap.put("class", "application/x-java");
	typeMap.put("latex", "application/x-latex");
	typeMap.put("frm", "application/x-maker");
	typeMap.put("maker", "application/x-maker");
	typeMap.put("frame", "application/x-maker");
	typeMap.put("fm", "application/x-maker");
	typeMap.put("fb", "application/x-maker");
	typeMap.put("book", "application/x-maker");
	typeMap.put("fbdoc", "application/x-maker");
	typeMap.put("mif", "application/x-mif");
	typeMap.put("com", "application/x-msdos-program");
	typeMap.put("exe", "application/x-msdos-program");
	typeMap.put("bat", "application/x-msdos-program");
	typeMap.put("dll", "application/x-msdos-program");
	typeMap.put("nc", "application/x-netcdf");
	typeMap.put("cdf", "application/x-netcdf");
	typeMap.put("pac", "application/x-ns-proxy-autoconfig");
	typeMap.put("o", "application/x-object");
	typeMap.put("pl", "application/x-perl");
	typeMap.put("pm", "application/x-perl");
	typeMap.put("shar", "application/x-shar");
	typeMap.put("swf", "application/x-shockwave-flash");
	typeMap.put("swfl", "application/x-shockwave-flash");
	typeMap.put("sit", "application/x-stuffit");
	typeMap.put("sv4cpio", "application/x-sv4cpio");
	typeMap.put("sv4crc", "application/x-sv4crc");
	typeMap.put("tar", "application/x-tar");
	typeMap.put("gf", "application/x-tex-gf");
	typeMap.put("pk", "application/x-tex-pk");
	typeMap.put("PK", "application/x-tex-pk");
	typeMap.put("texinfo", "application/x-texinfo");
	typeMap.put("texi", "application/x-texinfo");
	typeMap.put("~", "application/x-trash");
	typeMap.put("%", "application/x-trash");
	typeMap.put("bak", "application/x-trash");
	typeMap.put("old", "application/x-trash");
	typeMap.put("sik", "application/x-trash");
	typeMap.put("t", "application/x-troff");
	typeMap.put("tr", "application/x-troff");
	typeMap.put("roff", "application/x-troff");
	typeMap.put("man", "application/x-troff-man");
	typeMap.put("me", "application/x-troff-me");
	typeMap.put("ms", "application/x-troff-ms");
	typeMap.put("ustar", "application/x-ustar");
	typeMap.put("src", "application/x-wais-source");
	typeMap.put("wz", "application/x-wingz");
	typeMap.put("au", "audio/basic");
	typeMap.put("snd", "audio/basic");
	typeMap.put("mid", "audio/midi");
	typeMap.put("midi", "audio/midi");
	typeMap.put("mpga", "audio/mpeg");
	typeMap.put("mpega", "audio/mpeg");
	typeMap.put("mp2", "audio/mpeg");
	typeMap.put("mp3", "audio/mpeg");
	typeMap.put("m3u", "audio/mpegurl");
	typeMap.put("aif", "audio/x-aiff");
	typeMap.put("aiff", "audio/x-aiff");
	typeMap.put("aifc", "audio/x-aiff");
	typeMap.put("gsm", "audio/x-gsm");
	typeMap.put("ra", "audio/x-pn-realaudio");
	typeMap.put("rm", "audio/x-pn-realaudio");
	typeMap.put("ram", "audio/x-pn-realaudio");
	typeMap.put("rpm", "audio/x-pn-realaudio-plugin");
	typeMap.put("wav", "audio/x-wav");
	typeMap.put("gif", "image/gif");
	typeMap.put("ief", "image/ief");
	typeMap.put("jpeg", "image/jpeg");
	typeMap.put("jpg", "image/jpeg");
	typeMap.put("jpe", "image/jpeg");
	typeMap.put("png", "image/png");
	typeMap.put("tiff", "image/tiff");
	typeMap.put("tif", "image/tiff");
	typeMap.put("ras", "image/x-cmu-raster");
	typeMap.put("bmp", "image/x-ms-bmp"); //TODO: Consider image/bmp here. See comment in DibEncoder().getMimeType()
	typeMap.put("pnm", "image/x-portable-anymap");
	typeMap.put("pbm", "image/x-portable-bitmap");
	typeMap.put("pgm", "image/x-portable-graymap");
	typeMap.put("ppm", "image/x-portable-pixmap");
	typeMap.put("rgb", "image/x-rgb");
	typeMap.put("xbm", "image/x-xbitmap");
	typeMap.put("xpm", "image/x-xpixmap");
	typeMap.put("xwd", "image/x-xwindowdump");
	typeMap.put("csv", "text/comma-separated-values");
	typeMap.put("html", "text/html");
	typeMap.put("htm", "text/html");
	typeMap.put("mml", "text/mathml");
	typeMap.put("txt", "text/plain");
	typeMap.put("rtx", "text/richtext");
	typeMap.put("tsv", "text/tab-separated-values");
	typeMap.put("h++", "text/x-c++hdr");
	typeMap.put("hpp", "text/x-c++hdr");
	typeMap.put("hxx", "text/x-c++hdr");
	typeMap.put("hh", "text/x-c++hdr");
	typeMap.put("c++", "text/x-c++src");
	typeMap.put("cpp", "text/x-c++src");
	typeMap.put("cxx", "text/x-c++src");
	typeMap.put("cc", "text/x-c++src");
	typeMap.put("h", "text/x-chdr");
	typeMap.put("csh", "text/x-csh");
	typeMap.put("c", "text/x-csrc");
	typeMap.put("java", "text/x-java");
	typeMap.put("moc", "text/x-moc");
	typeMap.put("p", "text/x-pascal");
	typeMap.put("pas", "text/x-pascal");
	typeMap.put("etx", "text/x-setext");
	typeMap.put("sh", "text/x-sh");
	typeMap.put("tcl", "text/x-tcl");
	typeMap.put("tk", "text/x-tcl");
	typeMap.put("tex", "text/x-tex");
	typeMap.put("ltx", "text/x-tex");
	typeMap.put("sty", "text/x-tex");
	typeMap.put("cls", "text/x-tex");
	typeMap.put("vcs", "text/x-vCalendar");
	typeMap.put("vcf", "text/x-vCard");
	typeMap.put("dl", "video/dl");
	typeMap.put("fli", "video/fli");
	typeMap.put("gl", "video/gl");
	typeMap.put("mpeg", "video/mpeg");
	typeMap.put("mpg", "video/mpeg");
	typeMap.put("mpe", "video/mpeg");
	typeMap.put("qt", "video/quicktime");
	typeMap.put("mov", "video/quicktime");
	typeMap.put("asf", "video/x-ms-asf");
	typeMap.put("asx", "video/x-ms-asf");
	typeMap.put("avi", "video/x-msvideo");
	typeMap.put("movie", "video/x-sgi-movie");
	typeMap.put("vrm", "x-world/x-vrml");
	typeMap.put("vrml", "x-world/x-vrml");
	typeMap.put("wrl", "x-world/x-vrml");

	try {
	    BufferedReader in =
		new BufferedReader(new FileReader(MIME_TYPES));
	    try {
		String line;
		while ((line = in.readLine()) != null) {
		    int i, j;
		    for (i = 0; i < line.length()
			     && Character.isWhitespace(line.charAt(i)); ++i)
			; 
		    if (i >= line.length() || line.charAt(i) == '#')
			continue; // ignore empty/comment lines
		    for (j = i; j < line.length()
			     && !Character.isWhitespace(line.charAt(j)); ++j)
			;
		    String type = line.substring(i, j);
		    while ((i = j + 1) < line.length()) {
			for (; i < line.length()
				 && Character.isWhitespace(line.charAt(i));
			     ++i)
			    ;
			for (j = i; j < line.length()
				 && !Character.isWhitespace(line.charAt(j));
			     ++j)
			    ;
			typeMap.put(line.substring(i,j), type);
		    }
		}
	    } catch (IOException e) {
		Core.logger.log(MimeTypeUtils.class,
				"Error reading "+MIME_TYPES+": "+e,
				Logger.NORMAL);
	    }
	    try {
		in.close();
	    } catch (IOException e) {
		// closing a r/o file should not fail
		Core.logger.log(MimeTypeUtils.class,
				"Error closing "+MIME_TYPES+": "+e,
				Logger.ERROR);
	    }
	} catch (FileNotFoundException e) {
	    Core.logger.log(MimeTypeUtils.class, MIME_TYPES+" not found",
			    Logger.DEBUG);
	}
    }

    static public String getMimeType(String extension) {
	if (extension == null) {
	    return null;
	} else {
	    if (typeMap.get(extension) != null)
		return (String) typeMap.get(extension);
	    else
		return (String) typeMap.get(extension.toLowerCase());
	}
    }

    public static String getExtType(String name) {
        return getMimeType(name.substring(name.lastIndexOf('.') + 1));
    }

    // I moved this out of fproxy so that it can be used 
    // by other Servlets.
    //
    // attribution: amphibian, giannij, brandon, theo?
    public static String fullMimeType(String queryMime, Metadata md, String key) {
        // Find content type, in descending order of preference:
        // x 1. specified in query parameters
        // x 2. specified in metadata
        // ? 3. guessed from data a la file(1)
        // x 4. guessed from key
        String mimeType = null;

	    /* MIME type may include a charset= parameter
	     *
	     * If there is no charset= parameter, the default is ISO-8859-1
	     *
	     * If the file begins with FFFE or FEFF, and the charset
	     * parameter is not set to UTF16, then it may be interpreted as
	     * UTF16 by the browser, or it may not, so we throw in the filter
	     */
	    // User specified mime type
        if (queryMime != null) {
            mimeType = queryMime;
        }

        // Try to read the mime type out of the metadata
        if ((mimeType == null) && (md != null)) {
            mimeType = md.getMimeType(null);
        }

        // If that doesn't work guess it from the extension
        // on the key name.
        if (mimeType == null) {
            mimeType = MimeTypeUtils.getExtType(key);
        }

        // If all else fails, fall back to octet-stream
        // so the user can download the file.
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }
	
	String[] s = splitMimeType(mimeType);
	
	mimeType = s[0];
	String charset = s[1];
	
        // Determine real mime type again
        String fullMimeType = mimeType;
	if(charset.length()!=0)
	    fullMimeType += ";charset="+charset;
	
        return fullMimeType;
    }
    
    /**
     * Split the MIME type
     *
     * @return a String[] with [0] = the mime type, [1] = the charset
     * Text types have ISO-8859-1 imposed as a default
     */
    public static String[] splitMimeType(String mimeType) {
        // Now find the charset
        String charset;
	if(mimeType.startsWith("text/"))
	    charset = "ISO-8859-1"; // the default
	else
	    charset = "";
        int x = mimeType.indexOf(";charset=");
        if (x != -1 && (x+";charset=".length() < mimeType.length())) {
            charset = mimeType.substring(x+";charset=".length(), 
                                         mimeType.length());
            mimeType = mimeType.substring(0, x);
        }
	
	return new String[] { mimeType, charset };
    }
    
}
