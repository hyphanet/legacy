package freenet.diagnostics;
import java.util.Enumeration;
import java.util.Stack;

import freenet.diagnostics.EventDequeue.Tail;
/**
 * Dumps all the data for a field in easy to handle tabbed rows.
 */

public class RowDiagnosticsFormat implements DiagnosticsFormat {

    private int period;
    private boolean all;

    public RowDiagnosticsFormat(int period) {
        this.period = period;
        all = false;
    }

    public RowDiagnosticsFormat() {
        all = true;
    }

    public String formatStart(DiagnosticsCategory dc) {
        StringBuffer sb = new StringBuffer("# ");
        Stack st = new Stack();
        DiagnosticsCategory p = dc;
        while (p != null) {
            st.push(p);
            p = p.getParent();
        }

        p = st.empty() ? null : (DiagnosticsCategory) st.pop();

        while (p != null) {
            sb.append(p.name());
            p = st.empty() ? null : (DiagnosticsCategory) st.pop();
            if (p != null)
                sb.append("->");
        }
        sb.append("\n# ").append(dc.comment()).append("\n");
        return sb.toString();
    }

    public String formatEnd(DiagnosticsCategory dc) {
        return "";
    }


    public String format(RandomVar rv) {
        synchronized (rv) {
            StringBuffer sb = new StringBuffer();
            sb.append("#").append(rv.getType()).append("   ");
            sb.append(rv.getName()).append("\n");
            sb.append("#").append(rv.getComment()).append("\n");
            sb.append("#Aggregated over every ");
            int aggPeriod = rv.aggregationPeriod();
            sb.append(StandardDiagnostics.getName(aggPeriod));
            sb.append('\n');
            
            sb.append("#Type\tTime");
            String[] hs = rv.headers();
            for (int i = 0 ; i < hs.length ; i++)
                sb.append('\t').append(hs[i]);
            sb.append('\n');
            
            int aggs = rv.aggregations();
            if (all) {
                for (int i = -1 ; i < aggs ; i++) {
                    addPeriod(sb, rv,
                              i < 0 ? i : aggPeriod + i);
                }
            } else {
                addPeriod(sb, rv, period);
            }
            return sb.toString();
        }
    }

    private void addPeriod(StringBuffer sb, RandomVar rv,
                           int i) {
        EventDequeue el = rv.getEvents(i);
        el.open(rv);
        String pname = (i < 0 ? 
                        pname = "occurrence" :
                        StandardDiagnostics.getName(i));
		Tail r = el.getTail();
        for (Enumeration e = r.elements() ; e.hasMoreElements();) {
            VarEvent ev = (VarEvent) e.nextElement();
            sb.append(pname).append('\t').append(ev.time() / 1000);
            String[] fs = ev.fields();
            for (int j = 0 ; j < fs.length ; j++)
                sb.append('\t').append(fs[j]);
            
            sb.append("\n");
        }
        el.close();
    }

}
