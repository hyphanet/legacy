package freenet.node.simulator.whackysim;

import java.io.PrintWriter;

import freenet.Key;
import freenet.node.rt.BucketDistribution;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;

/**
 * RouteEstimator using 3 KeyspaceEstimators
 */
public class StandardRouteEstimator implements RouteEstimator {

    final KeyspaceEstimator epDNF;
    final KeyspaceEstimator etDNF;
    final KeyspaceEstimator etSuccess;
    
    public StandardRouteEstimator(KeyspaceEstimatorFactory kef, int initTimeVal) {
        epDNF = kef.createProbability(1.0, null);
        etDNF = kef.createTime(null, initTimeVal, initTimeVal, null);
        etSuccess = kef.createTime(null, initTimeVal, initTimeVal, null);
    }

    public double estimate(Key k) {
        double pDNF = epDNF.guessProbability(k);
        double tDNF = etDNF.guessTime(k);
        double tSuccess = etSuccess.guessTime(k);
        /**
         * Algorithm:
         * pSuccess * tSuccess + tDNF / pDNF
         */
        double d = ((1.0-pDNF) * tSuccess) + tDNF / (1.0-pDNF);
        //System.out.println("pDNF: "+pDNF+", tDNF: "+tDNF+", tSuccess: "+tSuccess+" -> "+d);
        return d;
    }

    public void succeeded(Key k, long time) {
        epDNF.reportProbability(k, 0.0);
        etSuccess.reportTime(k, time);
    }

    public void failed(Key k, long time) {
        epDNF.reportProbability(k, 1.0);
        etDNF.reportTime(k, time);
    }

    public void dump(PrintWriter pw, String dumpFilename) {
        pw.println(getClass().getName());
        pw.println("pDNF:");
        BucketDistribution bd = new BucketDistribution();
        epDNF.getBucketDistribution(bd);
        pw.println(bd.toString());
        pw.println("tDNF:");
        etDNF.getBucketDistribution(bd);
        pw.println(bd.toString());
        pw.println("tSuccess:");
        etSuccess.getBucketDistribution(bd);
        pw.println(bd.toString());
    }

}
