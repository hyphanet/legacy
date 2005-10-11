package freenet.diagnostics;
import freenet.support.Logger;
import freenet.support.Pair;
import java.util.Vector;
import java.util.Enumeration;
/**
 * Code to automatically poll diagnostics values. 
 *
 * The times between polls can be arbitrary, but I'm only going to run the
 * poll jobs together with the Diagnostics aggregation which runs every minute,
 * so don't expect it to be precise. This isn't RT anyways, as the jobs are
 * subject to the mood of the node and the threadmanager anyways - but
 * it should be good enough to collect the sort of values we need.
 *
 * I considered using reflection, but I find the interface heavy version 
 * is nicer to work with.
 *
 * @author oskar
 */

public class AutoPoll {

    private Vector jobs;
    private Logger logger;
    private Diagnostics diagnostics;

    /**
     * Creates a new AutoPoll object
     * @param diagnostic   The diagnostic to report results to
     * @param logger       The logger to log to.
     */
    public AutoPoll(Diagnostics diagnostic, Logger logger) {
        this.diagnostics = diagnostic;
        this.logger = logger;
        this.jobs = new Vector();
    }

    /**
     * An interface for classes to be autopolled for binomial stats.
     */
    public interface AutoBinomial {
        /**
         * The method polled for binomial stats.
         * @param s the name of the var being polled for.
         */
        public Pair binomialPoll(String s);
    }

    /**
     * Add a job to automatically poll a class for binomial stats.
     * @param   name     The name of the Var to register with Diagnostics.
     * @param   period   The period value to send to Diagnostics.
     * @param   interval The interval between polls.
     * @param   target   The class to poll from.
     * @see Diagnostics#registerBinomial(String, int, String, DiagnosticsCategory), 
     * Diagnostics#occurrenceBinomial()
     */
    public void addAutoBinomial(String name, int period, long interval, 
                                AutoBinomial target, String comment,
                                DiagnosticsCategory cat) {

        diagnostics.registerBinomial(name, period, comment, cat);
        BinomialJob b = new BinomialJob(name, target, interval);
        jobs.addElement(b);
    }

    /**
     * An interface for classes to be autopolled for continuous stats.
     */
    public interface AutoContinuous {
        /**
         * The method polled for continuous stats.
         * @param  name   the name of the var being polled for.
         */
        public double continuousPoll(String name);
    }

    /**
     * Add a job to automatically poll a class for continuous stats.
     * @param   name     The name of the Var to register with Diagnostics.
     * @param   period   The period value to send to Diagnostics.
     * @param   interval The interval between polls.
     * @param   target   The class to poll from.
     * @see Diagnostics#registerContinuous(String, int, String, DiagnosticsCategory),
     *      Diagnostics#occurrenceContinuous()
     */
    public void addAutoContinuous(String name, int period, long interval, 
                                  AutoContinuous target, String comment,
                                  DiagnosticsCategory cat) {

        diagnostics.registerContinuous(name, period, comment, cat);
        ContinuousJob b = new ContinuousJob(name, target, interval);
        jobs.addElement(b);
    }

    /**
     * An interface for classes to be autopolled for counting process stats.
     */
    public interface AutoCounting {
        /**
         * The method polled for stats.
         * @param  name   the name of the var being polled for.
         */
        public long countingPoll(String name);
    }

    /**
     * Add a job to automatically poll a class for counting process stats.
     * @param   name     The name of the Var to register with Diagnostics.
     * @param   period   The period value to send to Diagnostics.
     * @param   interval The interval between polls.
     * @param   target   The class to poll from.
     * @see Diagnostics#registerCounting(String, int, String, DiagnosticsCategory) 
     *      Diagnostics#occurrenceCounting()
     */
    public void addAutoCounting(String name, int period, long interval,
                                AutoCounting target, String comment,
                                DiagnosticsCategory cat) {
        diagnostics.registerCounting(name, period, comment, cat);
        CountingJob b = new CountingJob(name, target, interval);
        jobs.addElement(b);
    }

    /**
     * Performs all autopolls. Should be called at regular intervals.
     */
    public void doPolling() {
        long time = System.currentTimeMillis(); 
        for(Enumeration e = jobs.elements() ; e.hasMoreElements();) {
            ((Job) e.nextElement()).doJob(time);
        }
    }


    // Job implementations

    private abstract class Job {

        protected String name;

        private long interval;
        private long last;

        protected Job(String name, long interval) {
            this.name = name;
            this.interval = interval;
            this.last = 0;
        }

        public void doJob(long time) {
            for ( ; last + interval <= time ; last += interval) {
                occurrence();
            }
        }

        protected abstract void occurrence();
        
    }

    private class BinomialJob extends Job {

        private AutoBinomial target;

        public BinomialJob(String name, AutoBinomial job, 
                            long interval) {
            super(name, interval);
            this.target = job;
        }

        public void occurrence() {

            Pair p = target.binomialPoll(name);
            if (!(p.getKey() instanceof Integer && 
                  p.getValue() instanceof Integer)) {
                logger.log(this, "Binomial expects returned pair to contain"
                           + " Integers, but got " + p.getKey().getClass() 
                           + " and " + p.getValue().getClass(), Logger.NORMAL);
                return;
            }
            
            diagnostics.occurrenceBinomial(name, 
                                          ((Integer) p.getKey()).intValue(),
                                          ((Integer) p.getKey()).intValue());
            
        }

        public String toString() {
            return "Binomial autopoll for variable: " + name;
        }
    }


    private class ContinuousJob extends Job {

        private AutoContinuous target;

        public ContinuousJob(String name, AutoContinuous job, 
                            long interval) {
            super(name, interval);
            this.target = job;
        }

        public void occurrence() {

            double d = target.continuousPoll(name);
            diagnostics.occurrenceContinuous(name, d);
        }

        public String toString() {
            return "Continuous autopoll for variable: " + name;
        }
    }

    private class CountingJob extends Job {

        private AutoCounting target;

        public CountingJob(String name, AutoCounting job, 
                            long interval) {
            super(name, interval);
            this.target = job;
        }

        public void occurrence() {

            long n = target.countingPoll(name);
            diagnostics.occurrenceCounting(name, n);
        }

        public String toString() {
            return "Counting process autopoll for variable: " + name;
        }
    }
}
