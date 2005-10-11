package freenet.client.http.filter;
import java.util.*;

/**
 * Security filter results.
 *
 * @author devrandom@hyper.to
 */

public class FilterAnalysis
{
    boolean found_script = false;
    boolean found_embedded_absolute = false;
    boolean found_external_links = false;
    Vector disallowedElements = null;
    Vector warningElements = null;

    public void merge(FilterAnalysis a) {
	if(a.found_script) found_script = true;
	if(a.found_embedded_absolute) found_embedded_absolute = true;
	if(a.found_external_links) found_external_links = true;
	if(disallowedElements != null) {
	    if(a.disallowedElements != null)
		disallowedElements.addAll(a.disallowedElements);
	} else
	    disallowedElements = a.disallowedElements;
	if(warningElements != null) {
	    if(a.warningElements != null)
		warningElements.addAll(a.warningElements);
	} else
	    warningElements = a.warningElements;
    }
    
    private String encodeHTML(String s) {
	StringBuffer sb = new StringBuffer(s.length() + 100);
	int i = 0;
	char c = 0;
	while(i < s.length()) {
	    c = s.charAt(i);
	    if(c == '<') {
		sb.append("&lt;");
	    } else if(c == '>') {
		sb.append("&gt;");
	    } else if(c == '&') {
		sb.append("&amp;");
	    } else if(c == '"') {
		sb.append("&quot;");
	    } else {
		sb.append(c);
	    }
	    i++;
	}
	return sb.toString();
    }

    public void addDisallowedElement(String el) {
	if (disallowedElements == null) {
	    disallowedElements = new Vector();
	}
	disallowedElements.addElement(encodeHTML(el));
    }

    public Enumeration getDisallowedElements() {
	if (disallowedElements == null)
	    return null;
	return disallowedElements.elements();
    }

    public void addWarningElement(String el) {
	if (warningElements == null) {
	    warningElements = new Vector();
	}
	warningElements.addElement(encodeHTML(el));
    }

    public Enumeration getWarningElements() {
	if (warningElements == null)
	    return null;
	return warningElements.elements();
    }

    public boolean isScriptFound() {
	return found_script;
    }

    public boolean isEmbeddedAbsoluteFound() {
	return found_embedded_absolute;
    }

    public boolean isExternalLinksFound() {
	return found_external_links;
    }

    public boolean isSecurityErrors() {
	return found_script || found_embedded_absolute;
    }

    public boolean isSecurityWarnings() {
	return found_external_links || isSecurityErrors();
    }

    public String toString() {
	return "script found = " + isScriptFound() + ", embedded absolute = " + isEmbeddedAbsoluteFound() + ", external links = " + isExternalLinksFound();
    }
}
