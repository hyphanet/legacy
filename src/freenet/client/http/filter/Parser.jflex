package freenet.client.http.filter;
import java.io.*;
import java.util.*;
/**
 * This class is designed to catch the following dangerous constructs in
 * Freenet HTML documents:
 * 
 * Any javascript code
 * Any embedded objects
 * Any embedded links to pages outside of Freenet or URLs that may interact
 * with the behavior of the proxy.
 * Any non-embedded links (these are considered "warnings")
 *
 * @author devrandom@hyper.to
 */
%%

%{
  private boolean debug = false;
  private StringBuffer buffer = new StringBuffer();
  private FilterAnalysis analysis = new FilterAnalysis();

  public FilterAnalysis getAnalysis() {
      return analysis;
  }

  private String getResult() {
      return buffer.toString();
  }

  public void setDebug(boolean debug) {
	this.debug = debug;
  }
  
  public String parse () throws IOException {
      while (yylex() != null);
      return getResult();
  }

  public static void dumpElements(Enumeration enu) {
      if (enu != null) {
	  while (enu.hasMoreElements()) {
	      System.out.println(" - " + enu.nextElement());
	  }
      }
  }


  public static void main(String argv[]) {
      for (int i = 0; i < argv.length; i++) {
	  try {
	      Reader reader = new FileReader(argv[i]);
	      Parser finder = new Parser(reader);
	      String result = finder.parse();
	      FilterAnalysis analysis = finder.getAnalysis();
	      System.out.println("Disallowed:");
	      dumpElements(analysis.getDisallowedElements());
	      System.out.println("Warning:");
	      dumpElements(analysis.getWarningElements());
	      //System.out.println(result);
	      System.out.println(analysis);
	  }
	  catch (Exception e) {
	      e.printStackTrace(System.out);
	      System.exit(1);
	  }
      }
  }
%} 

%class Parser
%unicode
%ignorecase

/* Whitespace */
WS=[\n\r\ \t\b\012]*

/* Non whitespace and not close of tag (right angle bracket).  I.e. chars that
 * would not cause an unquoted attribute to end */
NONSEP=[^>\n\r\ \t\b\012]
NONSEP_NOQUOTE=[^>\n\r\ \t\b\012\"]

/* Alpha */
ALPHA_STRING=[a-z]+

/* Alphanumeric */
ALPHANUM_STRING=[a-z0-9]+

/* Attributes that cause the client to run JavaScript */
SCRIPT_KEYWORDS=data|datasrc|codebase|object|onblur|onchange|onclick|ondblclick|onkeydown|onkeypress|onkeyup|onload|onmousedown|onmousemove|onmouseout|onmouseover|onmouseup|onsubmit|onreset|onselect|onunload|onafterupdate|onbeforeupdate|onerrorupdate|onrowenter|onrowexit|onbeforeunload|ondatasetchanged|ondataavailable|ondatasetcomplete|http-equiv
SCRIPT_INTRO={SCRIPT_KEYWORDS}{WS}=
DISALLOWED_TAGS=<(script|applet|base|servlet|embed|param|bgsound)

/* Attributes that cause the client to automatically retrieve a page */
EMB_ATTRS=background|longdesc|lowsrc|profile|src

/* Attributes that cause the client to retrieve a page when activated by the
 * user */
LINK_ATTRS=action|cite|classid|href
ALLOWED_SCHEMES=mailto
CHECKED_HTTP="/__CHECKED_HTTP__"

SIMPLE_DATE=[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]
COMPLEX_DATE={SIMPLE_DATE}-[0-9][0-9][:][0-9][0-9][:][0-9][0-9]
DATE={SIMPLE_DATE}|{COMPLEX_DATE}

ALLOWED_LINK_PATTERNS1={LINK_ATTRS}{WS}={WS}[\"]{ALLOWED_SCHEMES}:[^\"]*[\"]
ALLOWED_LINK_PATTERNS2={LINK_ATTRS}{WS}={WS}{ALLOWED_SCHEMES}:{NONSEP}*
ALLOWED_LINK_PATTERNS3={LINK_ATTRS}{WS}={WS}[\"][#][^\"]*[\"]
ALLOWED_LINK_PATTERNS4={LINK_ATTRS}{WS}={WS}[#]({NONSEP_NOQUOTE}{NONSEP}*)
ALLOWED_LINK_PATTERNS5={LINK_ATTRS}{WS}={WS}[\"]{CHECKED_HTTP}[^\"]*[\"]
ALLOWED_LINK_PATTERNS6={LINK_ATTRS}{WS}={WS}{CHECKED_HTTP}{NONSEP}*
ALLOWED_LINK_PATTERNS7=({LINK_ATTRS}|{EMB_ATTRS}){WS}={WS}[\"][^\"?:]*"?date="{DATE}[\"]
ALLOWED_LINK_PATTERNS8=({LINK_ATTRS}|{EMB_ATTRS}){WS}={WS}[^>\n\r\ \t\b\012\"?:]*"?date="{DATE}[>\n\r\ ]
ALLOWED_LINK_PATTERNS={ALLOWED_LINK_PATTERNS1}|{ALLOWED_LINK_PATTERNS2}|{ALLOWED_LINK_PATTERNS3}|{ALLOWED_LINK_PATTERNS4}|{ALLOWED_LINK_PATTERNS5}|{ALLOWED_LINK_PATTERNS6}|{ALLOWED_LINK_PATTERNS7}|{ALLOWED_LINK_PATTERNS8}

/* Catch any colon or question mark within the URL */
EMB_PATTERNS1={EMB_ATTRS}{WS}={WS}[\"][^\"?:]*[?:][^\"]*
EMB_PATTERNS2={EMB_ATTRS}{WS}={WS}({NONSEP_NOQUOTE}{NONSEP}*)?[?:]{NONSEP}*
EMB_PATTERNS={EMB_PATTERNS1}|{EMB_PATTERNS2}

/* Catch any colon or question mark within the URL */
LINK_PATTERNS1={LINK_ATTRS}{WS}={WS}[\"][^\"?:]*[?:][^\"]*
LINK_PATTERNS2={LINK_ATTRS}{WS}={WS}({NONSEP_NOQUOTE}{NONSEP}*)?[?:]{NONSEP}*
/* Block type changing by LINK - stylesheets or not, type reassignment is a MAJJOR PROBLEM */
LINK_PATTERNS3=[<]{WS}link{WS}[^>]*{WS}type{WS}=
/* Same thing for charset... uh oh... */
LINK_PATTERNS4=[<]{WS}link{WS}[^>]*{WS}charset{WS}=
/* Block external stylesheets, for now. Deferring for the filter rewrite. */
LINK_PATTERNS5=[<]{WS}link{WS}[^>]*{WS}rel{WS}={WS}stylesheet
LINK_PATTERNS6=[<]{WS}link{WS}[^>]*{WS}rel{WS}={WS}[\"](alternate[ ])?stylesheet
LINK_PATTERNS7=url[\(]{WS}[^\):?]*[:?][^\)]*{WS}[\)]
LINK_PATTERNS8=[@]import{WS}[\"][^\"?:]*[?:][^\"]*[\"]
LINK_PATTERNS9=[@]import{WS}({NONSEP_NOQUOTE}{NONSEP}*)?[?:]{NONSEP}*
/* allow stylesheets within freenet */
LINK_PATTERNS={LINK_PATTERNS1}|{LINK_PATTERNS2}|{LINK_PATTERNS3}|{LINK_PATTERNS4}|{LINK_PATTERNS5}|{LINK_PATTERNS6}|{LINK_PATTERNS7}|{LINK_PATTERNS8}|{LINK_PATTERNS9}

%% 
{SCRIPT_INTRO} {
    buffer.append( yy_buffer, yy_startRead, yy_markedPos-yy_startRead );
    analysis.addDisallowedElement("Scripts or other executable content: " + yytext());
    if (debug)
	System.err.println("found script " + yytext());
    analysis.found_script=true;
}

{DISALLOWED_TAGS} {
    buffer.append( yy_buffer, yy_startRead, yy_markedPos-yy_startRead );
    analysis.addDisallowedElement("Scripts or other executable content: " + yytext());
    if (debug)
	System.err.println("found dangerous tags " + yytext());
    analysis.found_script=true;
}

{EMB_PATTERNS} {
    buffer.append( yy_buffer, yy_startRead, yy_markedPos-yy_startRead );
    analysis.addDisallowedElement("Absolute URLs that are fetched automatically: " + yytext());
    if (debug)
	System.err.println("found absolute embedded link attributes " + yytext());
    analysis.found_embedded_absolute=true;
}

{ALLOWED_LINK_PATTERNS} {
    buffer.append( yy_buffer, yy_startRead, yy_markedPos-yy_startRead );
    String str = new String(yy_buffer, yy_startRead, yy_markedPos-yy_startRead);
    if (debug)
	    System.err.println("found allowed linking: " + yytext());
}
{LINK_PATTERNS} {
    buffer.append( yy_buffer, yy_startRead, yy_markedPos-yy_startRead );
    analysis.addWarningElement(yytext());
    String str = new String(yy_buffer, yy_startRead, yy_markedPos-yy_startRead);
    if (debug)
	    System.err.println("found absolute linking: " + yytext());
    analysis.found_external_links=true;
}

{ALPHA_STRING} {/*Reduce backtracking*/
    buffer.append( yy_buffer, yy_startRead, yy_markedPos-yy_startRead );
}

.|\n {  
    buffer.append( yy_buffer, yy_startRead, yy_markedPos-yy_startRead );
}

