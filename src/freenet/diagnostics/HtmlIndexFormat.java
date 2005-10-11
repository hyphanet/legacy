package freenet.diagnostics;

/**
 * Returns an HTML line describing a field (but not the actual data),
 * and displays for which periods data is available and with links to
 * ./<varname>/(occurrences|<periodname>|raw|fieldset)
 */

public class HtmlIndexFormat implements DiagnosticsFormat {

    public HtmlIndexFormat() {

    }

    public String formatStart(DiagnosticsCategory dc) {
        StringBuffer sb = new StringBuffer();
        sb.append("<div class=\"diagnostics\">\n");
        sb.append("<h").append(dc.level()).append('>').append(dc.name());
        sb.append("</h").append(dc.level()).append("><i>");
        sb.append(dc.comment()).append("</i><br /><br />\n");
        return sb.toString();
    }

    public String formatEnd(DiagnosticsCategory dc) {
        return "\n</div>\n";
    }

    public String format(RandomVar rv) {
        StringBuffer sb = new StringBuffer();
        sb.append("<b>").append(rv.getName()).append(" </b> Type ");
        sb.append(rv.getType());
        sb.append(": <i>").append(rv.getComment()).append("</i><br /> ");
        int i = rv.aggregationPeriod();
        int n = rv.aggregations();
        for (int j = 0 ; j <= n + 2; j++) {
            String pname = (j == 0 ? "occurrences" : 
                            j == n + 1 ? "raw" :
                            j == n + 2 ? "fieldset" :
                            StandardDiagnostics.getName(i + j - 1));
            sb.append("[<a href=\"").append(rv.getName()).append("/");
            sb.append(pname).append("\">").append(pname).append("</a>] ");
        }
        sb.append("[<a href=\"graphs?var=").append(rv.getName()).append("\">graph</a>] ");
        sb.append("<br /><br />");
        return sb.toString();
    }

}




