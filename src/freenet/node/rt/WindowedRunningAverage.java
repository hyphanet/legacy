package freenet.node.rt;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.FieldSet;

/**
 * @author Ian Clarke
 *  This class wraps a RunningAverage and filters all data reported to it by
 * storing up to N recent reports and reporting the average of these to the
 * wrapped RunningAverage object.  The effect of this should be to filter
 * out extreme reports.
 * TODO: Use SimpleRunningAverage for internal average value storage instead
 * of a homegrown version of the same functionallity
 **/
public class WindowedRunningAverage implements RunningAverage {
    RunningAverage ra;
    double[] window;
    double total = 0;
    int ptr=0, storedReports=0;
    
    public Object clone() {
        return new WindowedRunningAverage(this);
    }
    
    public WindowedRunningAverage(RunningAverage wrapped, int windowSize) {
        ra = wrapped;
        window = new double[windowSize];
    }
    
    public WindowedRunningAverage(WindowedRunningAverage a) {
        this.ptr = a.ptr;
        this.ra = (RunningAverage) a.ra.clone();
        this.storedReports = a.storedReports;
        this.total = a.total;
        this.window = (double[]) a.window.clone();
    }

    public double currentValue() {
        return ra.currentValue();
    }

    public double valueIfReported(double r) {
        return ra.valueIfReported(total/storedReports);
    }
    
    public void report(double d) {
        if (storedReports < window.length) {
            storedReports++;
        } else {
            total -= window[ptr];
        }
        total += d;
        window[ptr] = d;
        ra.report(total/storedReports);
        ptr++;
        if (ptr == window.length) {
            ptr = 0;
        }
    }

    public void report(long d) {
        report((double) d);
    }

    public FieldSet toFieldSet() {
        return ra.toFieldSet();
    }

    public void writeDataTo(DataOutputStream out) throws IOException {
         ra.writeDataTo(out);
    }

    public int getDataLength() {
        return ra.getDataLength();
    }

    public long countReports() {
        return ra.countReports();
    }
}
