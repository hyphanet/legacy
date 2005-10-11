package freenet.diagnostics;

import java.text.DateFormat;
import java.util.Date;
import java.util.Enumeration;

import freenet.diagnostics.EventDequeue.Tail;
import freenet.support.graph.BitmapEncoder;

public class GraphHtmlDiagnosticsFormat implements DiagnosticsFormat
{
    private final int period;
    private final int type;
    private final GraphRange range;
    private final DateFormat df;
    private final String itype;
    
    public GraphHtmlDiagnosticsFormat(int period, int type, GraphRange gr, String itype)
    {
        this.period = period;
        this.type = type;
        this.range = gr;
        this.itype = itype;
        df = DateFormat.getDateTimeInstance(DateFormat.SHORT,
                                            DateFormat.LONG);
    }
    
    public String formatStart(DiagnosticsCategory dc) {return "";}

    public String formatEnd(DiagnosticsCategory dc) {return "";}
    
    public String format(final RandomVar rv)
    {
        class Formatter {
            private String query(
                GraphRange newrange, // null: unspecified (recompute)
                String contenttype,  // null: unspecified (text/html)
                String imagetype,    // null: current
                int period,
                int type,
                String var,          // null: current var (rv.getName())
                String name          // null: unspecified
                )
            {
                return "\"graphs?" + (newrange == null ? "" : "range=" + newrange + "&amp;" )
                                   + (contenttype == null ? "" : "content-type=" + contenttype + "&amp;" )
                                   + "image-type=" + (imagetype == null ? itype : imagetype)  + "&amp;"
                                   + "period=" + (period < 0 ? "occurrences" : StandardDiagnostics.getName(period)) + "&amp;"
                                   + "type=" + type + "&amp;"
                                   + "var=" + (var == null ? rv.getName() : var)
                                   + (name == null ? "" : "&amp;name=" + name)
                                   + "\"";
            }
            
            String doFormat() {
                final StringBuffer out = new StringBuffer();
                out.append("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.0 Transitional//EN\">\n");
                out.append("<HTML><HEAD><TITLE>" + rv.getName() + "</TITLE></HEAD><BODY>\n");
                out.append("<H3>" + rv.getName() + "</H3>\n");
                out.append("<I>" + rv.getComment() + "</I><BR>\n");
                // offer alternate image formats
				for (Enumeration e = BitmapEncoder.getEncoders(); e.hasMoreElements();) {
                    BitmapEncoder be = (BitmapEncoder) e.nextElement();
                    if (be.getMimeType().equalsIgnoreCase(itype))
                        out.append("[<b>" + be.getExt() + "</b>] ");
                    else
						out.append("[<a href=" + query(null, null, be.getMimeType(), period, type, null, null) + ">"
								+ be.getExt() + "</a>] ");
                }
                out.append("<BR>\n");
                // todo: get the right extension on and make a link to view the image alone
                //out.append("<A HREF=" + query(null, itype, null, period, type, null, "graph.xbm") + ">");
                out.append("<IMG SRC=" + query(range, itype, null, period, type, null, "graph") + " ALT=\"\"><BR>\n");
                //out.append("</A>");
                if (period < 0)
                    out.append("Recorded occurrences ");
                else 
                    out.append("Recorded aggregates over " + StandardDiagnostics.getName(period));
				out.append(" from " + df.format(new Date(range.getFirst())) + " to "
						+ df.format(new Date(range.getLast())) + "<br>\n");
                out.append("max: " + range.getHigh() + "<br>\n");
                out.append("min: " + range.getLow() + "<br>\n");
                // offer alternate periods
                {
                    final int i = rv.aggregationPeriod();
                    final int n = rv.aggregations();
                    for (int j = 0 ; j <= n; j++) {
                        final int p = (j == 0 ? -1 : i + j - 1);
                        String pname = (p < 0 ? "occurrences" : StandardDiagnostics.getName(p));
                        if (p == period)
                            out.append("[<b>" + pname + "</b>] ");
                        else 
							out.append("[<a href=" + query(null, null, null, p, type, null, null) + ">" + pname
									+ "</a>] ");
                    }
                    out.append("<BR>\n");
                }
                // offer alternate types--big hack.
                {
                    EventDequeue el = rv.getEvents(period);
		    if(el != null) {
			el.open(rv);
			Tail r = el.getTail();
			Enumeration e = r.elements();
			if (e.hasMoreElements()) {
			    // get a sample event to probe for types
			    VarEvent ev = (VarEvent) e.nextElement();
			    // The convoluted java.lang.reflect code to get this out of Diagnostics 
			    // is an excercise for the reader.
							String types[] = new String[]{"Number of Events", "Mean Value", "Standard Deviation",
									"Min Value", "Max Value", "Success Probability", "Count Change",
									"Mean Time Between Events", "Mean Runtime Count"};
			    for (int i = 0; i != types.length; ++i) {
				try {
				    ev.getValue(i + 1);
				    if (type == (i + 1))
					out.append("[<b>" + types[i] + "</b>] ");
				    else
										out.append("[<a href=" + query(null, null, null, period, i + 1, null, null)
												+ ">" + types[i] + "</a>] ");
								} catch (IllegalArgumentException iae) {
								}
			    }
			    out.append("<BR>\n");
			}
						el.close();
		    }
		}
                out.append("</BODY></HTML>");
                return out.toString();
            }
        }
        return new Formatter().doFormat();
    }
}
