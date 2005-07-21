package freenet.node.simulator.whackysim;

import java.io.Serializable;

import freenet.node.rt.BootstrappingDecayingRunningAverage;

/**
 * Class to store average times for DNF and success.
 */
public class SuccessFailureStats implements Serializable {
    
    SuccessFailureStats() {
        tDNF = new BootstrappingDecayingRunningAverage(0.0, 0.0, Double.MAX_VALUE, 100);
        tSuccess = new BootstrappingDecayingRunningAverage(0.0, 0.0, Double.MAX_VALUE, 100);
    }
    
    final BootstrappingDecayingRunningAverage tDNF;
    final BootstrappingDecayingRunningAverage tSuccess;
    
    public double fullRequestTDNF() {
        return tDNF.currentValue();
    }
    
    public double fullRequestTSuccess() {
        return tSuccess.currentValue();
    }

    public void reportDNF(double time) {
        tDNF.report(time);
    }
    
    public void reportSuccess(double time) {
        tSuccess.report(time);
    }
}
