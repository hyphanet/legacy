package freenet.diagnostics;
import java.text.DateFormat;
import java.util.Date;
import java.util.Enumeration;

import freenet.diagnostics.EventDequeue.Tail;

public class HtmlDiagnosticsFormat implements DiagnosticsFormat {

    private int formatPeriod;
    private DateFormat df;

    /**
     * Make a new HtmlDiagnosticsFormat class.
     * @param period    One of the constant period types from Diagnostics,
     *                  or a negative number if individual occurrences should
     *                  returned. 
     */ 
    public HtmlDiagnosticsFormat(int period) {
        this.formatPeriod = period;
        df =  DateFormat.getDateTimeInstance(DateFormat.SHORT,
                                             DateFormat.LONG);
        
    }

    public String formatStart(DiagnosticsCategory dc) {
        StringBuffer sb = new StringBuffer();
        sb.append("<hr>\n");
        sb.append("<h").append(dc.level()).append('>').append(dc.name());
        sb.append("</h").append(dc.level()).append("><i>");
        sb.append(dc.comment()).append("</i><br /><br />\n");
        return sb.toString();
    }

    public String formatEnd(DiagnosticsCategory dc) {
        return "";
    }

    public String format(RandomVar rv) {
        StringBuffer sb = new StringBuffer();
        sb.append("<br />").append(rv.getType()).append(": <h3>");
        sb.append(rv.getName()).append("</h3>\n");
        sb.append("<i>").append(rv.getComment()).append("</i><br />\n");
        sb.append("Aggregated over every ");
        sb.append(StandardDiagnostics.getName(rv.aggregationPeriod())).append(" ");
        if (formatPeriod < 0)
            sb.append(". Recorded occurrences:");
        else {
            sb.append(". Recorded aggregates over ");
            sb.append(StandardDiagnostics.getName(formatPeriod)).append(':');
        }
        sb.append("\n<table border=no>\n<tr>");

        sb.append("<th>Time");
        String[] hs = rv.headers();
        for (int i = 0 ; i < hs.length ; i++)
            sb.append("</th><th>").append(hs[i]);
        sb.append("</th></tr>\n");
        EventDequeue el = rv.getEvents(formatPeriod);
        if (el == null) {
            sb.append("<tr><td>No data</td></tr>\n");
        } else {
            el.open(rv);
			Tail r = el.getTail();
            for (Enumeration e = r.elements() ; e.hasMoreElements();) {
                VarEvent ev = (VarEvent) e.nextElement();
                sb.append("<tr><td>").append(df.format(new Date(ev.time())));
                String[] fs = ev.fields();
                for (int i = 0 ; i < fs.length ; i++)
                    sb.append("</td><td>").append(fs[i]);

                sb.append("</td></tr>\n");
            }
            el.close();
        }
        sb.append("</table>\n");
        return sb.toString();
    }
}








