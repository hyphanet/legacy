package freenet.diagnostics;
import java.util.Enumeration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import freenet.diagnostics.EventDequeue.Tail;
import freenet.support.io.WriteOutputStream;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.FieldSet;
import freenet.Core;

/**
 * Writes each of the vars as a FieldSet to the provided stream. 
 * Each var is a FieldSet, preceded by the variable name, containg
 * the following fields:
 * type:        The type of variable.
 * AggPeriod:   The lowest period of aggregation, as one of the constant
 *              names above.
 * occurrences:  A list of the occurrences within the last aggregation
 *              period.
 * <type>:      For each type of period starting from AggPeriod above, the
 *              aggregated values over the times within the last such 
 *              period.
 *
 *              Each of these contain a subset with the occurrences 
 *              or aggregations denoted by the time, in milliseconds of 
 *              the epoch, that they were recorded. For each time, 
 *              the entry contains the data as a comma list.
 *              The data is:
 *              Binomial: <total number of tries>,<number successful>
 *              Continuous: <total sum>,<total sum of squares>,<number>
 *              CountingProcess: <number of occurrences>
 *              The fixed point numbers are hex, the floating point 
 *              (sum and square sum in Continuous) are decimal.
 */

public class FieldSetFormat implements DiagnosticsFormat {

    public FieldSetFormat() {
    }

    public String formatStart(DiagnosticsCategory dc) {
        return "";
    }

    public String formatEnd(DiagnosticsCategory dc) {
        return "";
    }

    public String format(RandomVar rv) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);        
            WriteOutputStream out = new WriteOutputStream(bytes);
            out.writeUTF(rv.getName().toString(),'\n');
            
            toFieldSet(rv).writeFields(out, "EndVar");
            out.flush();
            return bytes.toString("UTF8");
        } catch (IOException e) {
            Core.logger.log(this, "Error when writing to mem buffer.",e,
                            Logger.ERROR);
            return "";
        }
    }

    public static FieldSet toFieldSet(RandomVar rv) {
        synchronized (rv) {
            FieldSet fs = new FieldSet();
            fs.put("Type", rv.getType());
            int aggPeriod = rv.aggregationPeriod();
            fs.put("AggPeriod", Diagnostics.getName(aggPeriod));
            fs.put("ValueTypes",Fields.commaList(rv.headers()));
            int aggs = rv.aggregations();
            for (int i = -1 ; i < aggs ; i++) {
                EventDequeue el = rv.getEvents(i < 0 ? i : aggPeriod + i);
                el.open(rv);
                FieldSet stats = new FieldSet();
				Tail r = el.getTail();
                for (Enumeration e = r.elements();
                     e.hasMoreElements() ;) {
                    VarEvent ev = (VarEvent) e.nextElement();
                    stats.put(Long.toString(ev.time()),
                              Fields.commaList(ev.fields()));
                }
                if (i < 0) {
                    fs.put("occurrences", stats);
                } else {          
                    fs.put(StandardDiagnostics.getName(aggPeriod + i), 
                           stats);
                }
                el.close();
            }
            
            return fs;
            
        }


    }



}
