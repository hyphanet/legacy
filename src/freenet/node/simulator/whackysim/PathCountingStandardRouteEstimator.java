package freenet.node.simulator.whackysim;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimator;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.node.rt.RunningAverage;

/**
 * RouteEstimator using 3 KeyspaceEstimators, plus an external object to 
 * indicate what the average full-path tSuccess and tFailure are. We add 
 * these to our estimates in order to route on the basis of the whole 
 * path rather than just our bit of it. I theorize that this will improve
 * load distribution and success ratios by:
 * - Giving more importance to psuccess in a non-alchemical way
 * - Preventing all requests from going (first) to fast nodes with dubious 
 *   psuccess
 * - Making it harder to build ubernodes
 */
public class PathCountingStandardRouteEstimator implements RouteEstimator {

    final KeyspaceEstimator epDNF;
    final KeyspaceEstimator etDNF;
    final KeyspaceEstimator etSuccess;
    final SuccessFailureStats sfStats;
    final boolean halfValues;
    final boolean probRA;
    final RunningAverage rapDNF;
    final boolean noTDNF;
    final boolean productOfTSuccessAndPFailure;
    final boolean doNoPathCounting;
    final boolean productTSuccessIncludeBoth;
    
    public PathCountingStandardRouteEstimator(KeyspaceEstimatorFactory kef, int initTimeVal, SuccessFailureStats st, boolean halfValues, boolean probRA, boolean noTDNF, boolean tSuccessTimesPFailure, boolean doNoPathCounting, boolean productTSuccessIncludeBoth) {
        epDNF = kef.createProbability(1.0, null);
        etDNF = kef.createTime(null, initTimeVal, initTimeVal, null);
        etSuccess = kef.createTime(null, initTimeVal, initTimeVal, null);
        sfStats = st;
        this.halfValues = halfValues;
        this.probRA = probRA;
        rapDNF = Main.rafb.create(1.0);
        this.noTDNF = noTDNF;
        productOfTSuccessAndPFailure = tSuccessTimesPFailure;
        this.doNoPathCounting = doNoPathCounting;
        this.productTSuccessIncludeBoth = productTSuccessIncludeBoth;
    }

    public double estimate(Key k) {
        double pDNF;
        if(probRA)
            pDNF = rapDNF.currentValue();
        else
            pDNF = epDNF.guessProbability(k);
        double tDNF = 0;
        if(!productOfTSuccessAndPFailure) {
            if(!noTDNF)
                tDNF = etDNF.guessTime(k);
            if(halfValues) tDNF += (sfStats.fullRequestTDNF()/2);
            else if(!doNoPathCounting) tDNF += sfStats.fullRequestTDNF();
        }
        double tSuccess = etSuccess.guessTime(k);
        if(halfValues) tSuccess += (sfStats.fullRequestTSuccess()/2);
        else if(!doNoPathCounting) tSuccess += sfStats.fullRequestTSuccess();
        /**
         * Algorithm:
         * pSuccess * tSuccess + tDNF / pDNF
         */
        if(productOfTSuccessAndPFailure) {
            if(productTSuccessIncludeBoth)
                return (tSuccess + sfStats.fullRequestTDNF() + sfStats.fullRequestTSuccess()) * pDNF;
            return tSuccess * pDNF;
        } else {
            double d = ((1.0-pDNF) * tSuccess) + tDNF / (1.0-pDNF);
            //System.out.println("pDNF: "+pDNF+", tDNF: "+tDNF+", tSuccess: "+tSuccess+" -> "+d);
            return d;
        }
    }

    public void succeeded(Key k, long time) {
        epDNF.reportProbability(k, 0.0);
        etSuccess.reportTime(k, time);
        rapDNF.report(0.0);
    }

    public void failed(Key k, long time) {
        epDNF.reportProbability(k, 1.0);
        etDNF.reportTime(k, time);
        rapDNF.report(1.0);
    }
}
