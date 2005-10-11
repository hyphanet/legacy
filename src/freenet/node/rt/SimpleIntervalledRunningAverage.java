package freenet.node.rt;

import java.io.DataOutputStream;

import freenet.FieldSet;
import freenet.support.IntervalledSum;

/**
 * Simple intervalled running average: linear mean of the last N millisconds of reports.
 * Completely non-threadsafe. See the 'SynchronizedRunningAverage' class
 * @author Iakin
 */
public class SimpleIntervalledRunningAverage extends IntervalledSum implements RunningAverage {

    double initValue = 0.0;
    long reports;
    
    public Object clone() {
        return new SimpleIntervalledRunningAverage(this);
    }
    
    public SimpleIntervalledRunningAverage(long maxAgeMilliseconds, double initVal, boolean reportInitValue) {
		super(maxAgeMilliseconds);
		if(reportInitValue)
		    report(initVal); //Start out with the initial value...
		else
		    initValue = initVal;
    }
    
    public SimpleIntervalledRunningAverage(SimpleIntervalledRunningAverage a) {
        super(a);
        this.initValue = a.initValue;
        this.reports = a.reports;
    }

    public double currentValue() {
		double d = super.currentSum();
		long total = currentReportCount();
		if(total == 0) {
		    return initValue;
		}
        return d/total;
    }

	public final void report(double d) {
	    reports++;
	    super.report(d);
	}
	
	public final void report(long d) {
	    reports++;
	    super.report(d);
	}
	
	public double valueIfReported(double r) {
		throw new UnsupportedOperationException();
    }

    public String toString() {
        return super.toString() + ": total="+
        	currentSum()+", average="+currentValue()+", size="+currentReportCount();
    }

    public FieldSet toFieldSet() {
        throw new UnsupportedOperationException();
    }

    public void writeDataTo(DataOutputStream out) {
        throw new UnsupportedOperationException();
    }

    public int getDataLength() {
		throw new UnsupportedOperationException();
    }

    public long countReports() {
        return reports;
    }
}
