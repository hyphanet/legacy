package freenet.diagnostics;

import freenet.node.rt.ValueConsumer;

/**
 * Interface allowing direct access to a counting type of diagnostics variable
 */
public interface ExternalCounting{
    public void count(long n);
    public double getValue(int period, int type);

    //Will cause relaying of the indicated type of reports to the supplied value consumer
    public void relayReportsTo(ValueConsumer r,int type);
}
