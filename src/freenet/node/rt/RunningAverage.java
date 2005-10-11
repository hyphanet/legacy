package freenet.node.rt;

import freenet.FieldSet;
import freenet.support.DataObject;

public interface RunningAverage extends DataObject,ValueConsumer {
    
    public Object clone();
    
    public double currentValue();
    public void report(double d);
    public void report(long d);
	/**
	 * @return a FieldSet for transfer over FNP or in seednodes.
	 */
	public FieldSet toFieldSet();
    /**
     * Get what currentValue() would be if we reported some given value
     * @param r the value to mimic reporting
     * @return the output of currentValue() if we were to report r
     */
    public double valueIfReported(double r);
    /**
     * @return the total number of reports on this RunningAverage so far.
     * Used for weighted averages, confidence/newbieness estimation etc.
     */
    public long countReports();
}
