package freenet.diagnostics;

import freenet.diagnostics.EventDequeue.Tail;
import freenet.support.graph.*;
import java.io.*;
import java.util.Enumeration;


public class GraphDiagnosticsFormat implements DiagnosticsFormat
{
    final private int formatPeriod;
    final private BitmapEncoder e;
    final private OutputStream out;
    final private int type;
    final private GraphRange gr;
    
    public GraphDiagnosticsFormat(int period, BitmapEncoder e, OutputStream out, int type, GraphRange gr)
    {
        formatPeriod = period;
        this.e = e;
        this.out = out;
        this.type = type;
        this.gr = gr;
    }
    
    public String formatStart(DiagnosticsCategory dc) {return "";}

    public String formatEnd(DiagnosticsCategory dc) {return "";}

    /**
     * Something of a hack--returns an empty string, causes output on the 
     * OutputString given in the constructor as a side-effect.
     */
    public String format(RandomVar rv)
    {
        Bitmap b = new Bitmap(528, 396);
        
        b.scaleView(gr.getCoords());
        
        // draw axes, in black
        b.setPenColor(new Color(0,0,0));
        
        b.moveTo(0.0f, gr.getHigh());
        b.drawTo(0.0f, gr.getLow());
        
        b.moveTo(0.0f, 0.0f);
        b.drawTo((gr.getLast() - gr.getFirst()), 0.0f);
        
        EventDequeue el = rv.getEvents(formatPeriod);
	if(el != null) {
	    el.open(rv);
	    
	    // draw our data
	    b.setPenColor(new Color(255, 0, 0));
	    
	    boolean prevVisible = false;
		Tail r = el.getTail();
	    for (Enumeration e = r.elements(); e.hasMoreElements(); ) {
		VarEvent ev = (VarEvent) e.nextElement();
		// todo: do something with our data
		float x = (ev.time() - gr.getFirst());
		float y = (float) ev.getValue(type);
		
		if (prevVisible)
		    b.drawTo(x, y);
		else
		    b.moveTo(x, y);
                
		// todo: detect discontinuities
		prevVisible = true;
	    }
	    
	    el.close();
	    
	    e.setBitmap(b);
	    
	    try {
		e.encode(out);
	    } catch (IOException e) {
		// oops
	    }
	}
        return "";
    }
}
