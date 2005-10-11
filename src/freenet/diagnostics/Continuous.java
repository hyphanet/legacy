package freenet.diagnostics;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Enumeration;

/**
 * Implementation of a the continuous random var.
 *
 * @author oskar
 */

/*
 * There is a well known mathematical equality that says that var[X] = E[X^2] -
 * E[X]^2 which we apply here to calculate the standard deviation, since the
 * mean and square-mean can easily be aggregated correctly over time.
 */
class Continuous extends RandomVar {

    public static long VERSION = 2;

    static {
        try {
            RandomVar.registerType("Continuous", RandomVar.class);
        } catch (NoSuchMethodException e) {
        }
    }
    
	public static abstract class ContinuousEventCallback implements RandomVar.EventCallback {

		public void reportArrived(VarEvent e){
			if(e instanceof ContinuousVarEvent)
				reportArrived((ContinuousVarEvent)e);
			else
				throw new IllegalArgumentException("e not a ContinuousVarEvent");
		}

		public abstract void reportArrived(ContinuousVarEvent e);
	}
    
    public Continuous(StandardDiagnostics sd, String name, int period, String comment) {
        super(sd, name, period, comment);
    }

    public String getType() {
        return "Continuous";
    }
    
    public void add(long time, double value) {
        super.add(new ContinuousVarEvent(time, value, value * value, value, value, 1));
    }
    
    public VarEvent aggregate(EventDequeue.Tail t, long time, long after) {
        double tsum = 0, tsqsum = 0;
        double tmin = Double.NaN, tmax = Double.NaN; 
        long tn = 0;
		synchronized (t) {
			for (Enumeration e = t.elements(); e.hasMoreElements();) {
            ContinuousVarEvent cve = (ContinuousVarEvent) e.nextElement(); 
            if (cve.time() > after) {
                    if (cve.time() > time) break;
                tsum += cve.sum;
                tsqsum += cve.sqsum;
                tn += cve.n;
                if (!(cve.min >= tmin)) { // the double negation is for NaN
                    tmin = cve.min;
                }
                if (!(cve.max <= tmax)) {
                    tmax = cve.max;
                }
            }
        }
            // t.purgeOldEvents(time);
		}
        return new ContinuousVarEvent(time, tsum, tsqsum, tmin, tmax, tn);
    }

    public VarEvent readEvent(DataInputStream in) throws IOException {
        long ver = in.readLong();
        // my initial implementation wrote time first) 
        long time = ver;
        if (ver > StandardDiagnostics.y2k) {
            ver = 1;
        }
        time = ver < 2 ? time : in.readLong();
        double sum = in.readDouble();
        double sqsum = in.readDouble();
        double min = ver < 2 ? 0 : in.readDouble();
        double max = ver < 2 ? 0 : in.readDouble();
        long n = in.readLong();
        if (ver < 2) {
            min = sum / n; // set them both to the mean...
            max = sum / n;
        }
        return new ContinuousVarEvent(time, sum, sqsum, min, max, n);
    }

    public String[] headers() {
        return new String[] { "Mean", "Std. Dev.", "Minimum", "Maximum", "Observations"};
    }
}
