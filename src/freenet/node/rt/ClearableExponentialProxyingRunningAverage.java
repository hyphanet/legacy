package freenet.node.rt;

public class ClearableExponentialProxyingRunningAverage extends
        ExponentialProxyingRunningAverage implements ClearableRunningAverage {

    public Object clone() {
        return new ClearableExponentialProxyingRunningAverage(this);
    }
    
    public ClearableExponentialProxyingRunningAverage(RunningAverage ra) {
        super(ra);
        if(!(ra instanceof ClearableRunningAverage))
            throw new ClassCastException();
    }

    public void clear() {
        ((ClearableRunningAverage)ra).clear();
    }

}
