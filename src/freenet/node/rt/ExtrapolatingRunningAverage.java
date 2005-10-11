package freenet.node.rt;


/**
 * A RunningAverage with an extra method: minReportForValue.
 */
public interface ExtrapolatingRunningAverage extends RunningAverage {

    /**
     * How big will the next report need to be to achieve a
     * currentValue() of at least targetValue ?
     * FIXME: figure out a better name!
     * FIXME: document more readably :).
     * @param targetValue the minimum acceptable currentValue()
     * after a report.
     * @return the minimum value to report such that afterwards,
     * currentValue() will return a value >= targetValue.
     */
    double minReportForValue(double targetValue);

}
