package freenet.diagnostics;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Vector;

import freenet.node.rt.ValueConsumer;
import freenet.support.Logger;

/**
 * The standard Diagnostics implementation.
 *
 * @author oskar
 */
public class StandardDiagnostics extends Diagnostics {

    static final long y2k;
    static {
        Calendar c = Calendar.getInstance();
        c.set(2000, 
              Calendar.JANUARY,
              1,
              0,
              0);
        y2k = c.getTime().getTime();
    }

    
    private static final void test(long period) {
        if (period < MINUTE || period > DECADE) {
            throw new IllegalArgumentException("Periods must be one of class "
                                               + "constants.");
        }
    }

    /**
     * An interface for arbitrary clocks.
     */
    public static interface Clock {
        public long currentTimeMillis();
    }

    /**
     * An implementation that takes the standard system time.
     */
    public static class SystemClock implements Clock {
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }

    public class StdExternalBinomial extends Binomial.BinomialEventCallback implements ExternalBinomial {
		private final Binomial backingDiag;
		private volatile ValueConsumer r;
		private int iType=-1;
		private StdExternalBinomial(Binomial backingDiag){
			this.backingDiag = backingDiag;
		}
		public void count(long n, long x){
			backingDiag.add(clock.currentTimeMillis(), n, x);
		}
		public double getValue(int period, int type){
			return backingDiag.getValue(period,type,clock.currentTimeMillis());
		}
		public void relayReportsTo(ValueConsumer r,int type){
			if(this.r != null) //For now...
				throw new IllegalStateException("More than one Runningaverage not supported");
			this.r = r;
			iType = type;
			backingDiag.registerEventListener(this);
		}
		public void reportArrived(BinomialVarEvent e) {
			ValueConsumer r = this.r;
			r.report(e.getValue(iType));
		}
    }

	public class StdExternalContinuous extends Continuous.ContinuousEventCallback implements ExternalContinuous {
		private final Continuous backingDiag;
		private volatile ValueConsumer r;
		private int iType=-1;
		private StdExternalContinuous(Continuous backingDiag){
			this.backingDiag = backingDiag;
		}
		public void count(double value){
			backingDiag.add(clock.currentTimeMillis(), value);
		}
		public double getValue(int period, int type){
			return backingDiag.getValue(period,type,clock.currentTimeMillis());
		}
		public void relayReportsTo(ValueConsumer r,int type){
			if(this.r != null) //For now...
				throw new IllegalStateException("More than one Runningaverage not supported");
			this.r = r;
			iType = type; 
			backingDiag.registerEventListener(this);
		}
		public void reportArrived(ContinuousVarEvent e) {
			ValueConsumer r = this.r;
			r.report(e.getValue(iType));
		}
	}
	
	public class StdExternalCounting extends CountingProcess.CountingEventCallback implements ExternalCounting {
		private final CountingProcess backingDiag;
		private volatile ValueConsumer r;
		private int iType=-1;
		private StdExternalCounting(CountingProcess backingDiag){
			this.backingDiag = backingDiag;
		}
		public void count(long n){
			backingDiag.add(clock.currentTimeMillis(), n);
		}
		public double getValue(int period, int type){
			return backingDiag.getValue(period,type,clock.currentTimeMillis());
		}
		public void relayReportsTo(ValueConsumer r,int type){
			if(this.r != null) //For now...
				throw new IllegalStateException("More than one Runningaverage not supported");
			this.r = r;
			iType = type; 
			backingDiag.registerEventListener(this);
		}
		public void reportArrived(CountingEvent e) {
			ValueConsumer r = this.r;
			r.report(e.getValue(iType));
		}
	}
    // fields

    private Clock clock;

    //Intentionally use HashMap's instead of HashTable's due to their lesser synchronization.
    //This allows multiple occurrenceXXX calls to resolve variables simultaneously.
    //This shouldn't pose a problem as far as I can tell since no variable unregistration can take place and
    //since it doesn't really matter if we try to resolve one or two variables when a variable registration
    //is taking place /Iakin 2004-01-26  
    private Map binomialVars = new HashMap();
	private Map continuousVars = new HashMap();
	private Map countingVars = new HashMap();
	private HashSet uniqueness = new HashSet();
	
	private final Object var_registration_lockObj = new Object();
    
    private Logger logger;
    private long lastTime;
    private String statsDir;

    
    private boolean changed = false;

    private StdDiagnosticsCategory top;

    // Constructors

    /**
     * Uses the standard system clock.
     */
    public StandardDiagnostics(Logger logger, String statsDir) 
        throws DiagnosticsException {
        this(logger, statsDir, new SystemClock());
    }
       
    /**
     */
    public StandardDiagnostics(Logger logger, String statsDir, Clock clock) 
        throws DiagnosticsException {
                               
        if (clock.currentTimeMillis() < y2k) {
            logger.log(this, "Sorry, my time traveling friend, " 
                       + "diagnostics cannot function before Jan 1st 2000. " 
                       + "Don't worry though, Freenet works fine without " 
                         + "diagnostics.", Logger.NORMAL);
            throw new DiagnosticsException("Diagnostics requires date > " 
                                           + (new Date(y2k)).toString());
        }
        this.clock = clock;
        this.logger = logger;
        this.lastTime = clock.currentTimeMillis();
        this.statsDir = statsDir;
        File f = new File(statsDir);
        if (!f.exists()) {
            f.mkdirs();
        }

        top = new StdDiagnosticsCategory("Diagnostics Variables",
                                         "Data collected from Fred.",
                                         null);
    }

	private synchronized void registerNameUniqueness(String name) {
		if(uniqueness.contains(name))
			throw new IllegalArgumentException("Variable '"+name+"' already registered");
		else
			uniqueness.add(name);
	}

    // public methods

    public synchronized DiagnosticsCategory addCategory(String name,
                                                        String comment, 
                                                  DiagnosticsCategory parent) {

        if (parent == null)
            parent = top;

        if (!(parent instanceof StdDiagnosticsCategory)) {
            throw new IllegalArgumentException("Not correct category");
        }

        return new StdDiagnosticsCategory(name, comment, 
                                          (StdDiagnosticsCategory) parent);
    }

    public synchronized void registerBinomial(String name, int aggPeriod,
                                              String comment,
                                              DiagnosticsCategory cat) {
		synchronized (var_registration_lockObj) {
        test(aggPeriod);
			registerNameUniqueness(name);
        RandomVar rv = new Binomial(this, name, aggPeriod, comment);
			binomialVars.put(name, rv);
        changed = true;

        addToCategory(rv, cat);
    }
	}

    public void occurrenceBinomial(String name, long n, long x) {
        Object o = binomialVars.get(name);
        if (o == null || !(o instanceof Binomial)) {
            logger.log(this, name + " is not a known binomial var.", Logger.ERROR);
        } else if (x > n) {
            logger.log(this, "Outcome cannot be greater than value in a " 
                       + "binomial dist,", Logger.ERROR);
        } else {
            ((Binomial) o).add(clock.currentTimeMillis(), n, x);
        }
    }

    public synchronized void registerContinuous(String name, int period,
                                                String comment,
                                                DiagnosticsCategory cat) {
		synchronized (var_registration_lockObj) {
        test(period);
		registerNameUniqueness(name);
        RandomVar rv = new Continuous(this, name, period, comment);
        continuousVars.put(name, rv);
        changed = true;

        addToCategory(rv, cat);
    }
    }


    public void occurrenceContinuous(String name, double value) {
        Object o = continuousVars.get(name);
        if (o == null || !(o instanceof Continuous)) {
            logger.log(this, name + " not a known continuous variable.",
                       Logger.ERROR);
        } else {
            ((Continuous) o).add(clock.currentTimeMillis(), value);
        }
    }

    public synchronized void registerCounting(String name, int period, String comment, DiagnosticsCategory cat) {
			synchronized (var_registration_lockObj) {
        test(period);
				registerNameUniqueness(name);
        CountingProcess cp = new CountingProcess(this, name, period, comment);
				countingVars.put(name, cp);
        changed = true;

        addToCategory(cp, cat);
    }        
		}        


    public void occurrenceCounting(String name, long n) {
        Object o = countingVars.get(name);
        if (o == null || !(o instanceof CountingProcess)) {
            logger.log(this, name + " is not a known counting process.",
                       Logger.ERROR);       
        } else {
            ((CountingProcess) o).add(clock.currentTimeMillis(), n);
        }
    }

    /**
     * Performs aggregation on periods that have passed since the last time
     * this method was called. 
     * @return  The next time this method should be called, as an absolute
     *          time in milliseconds of the epoch (clock.currentTimeMillis() 
     *          format).
     */
    public long aggregateVars() {
        long time = clock.currentTimeMillis();
        if (time < lastTime) {
            logger.log(this, 
                       "Great Scott, Marty! Time is going backwards - it was "
                       + (new Date(lastTime)).toString() + " but now it is " 
                       + (new Date(time)).toString() 
                       + "! I can probably handle this, but if you get " 
                       + "trouble consider turning off Diagnostics.",
                       Logger.NORMAL);
        }
        long[] tperiods;
        // iterate over minutes since minute following last run
        // Stinking stupid calender has getTimeInMillis protected, adding
        // an unecessary call
        Calendar c;
		synchronized (var_registration_lockObj) {
			for (c = minuteRoof(lastTime); c.getTime().getTime() <= time; c.add(Calendar.MINUTE, 1)) {

            tperiods = getPeriods(c);
            if (logger.shouldLog(Logger.DEBUG,this))
					logger.log(this, tperiods.length + " periods ending at time " + c.getTime() + " currently " + time, Logger.DEBUG);
				//synchronized (continuousVars) { //Not needed because of the synchronized (var_registration_lockObj) statement above
				for (Iterator it = continuousVars.values().iterator(); it.hasNext();) {
					RandomVar rv = (RandomVar) it.next();
                for (int i = 0 ; i < tperiods.length ; i++) {
						rv.endOf(i, tperiods[i], c.getTime().getTime());
                }
            }
				//}
				//synchronized (countingVars) { //Not needed because of the synchronized (var_registration_lockObj) statement above
				for (Iterator it = countingVars.values().iterator(); it.hasNext();) {
					RandomVar rv = (RandomVar) it.next();
					for (int i = 0; i < tperiods.length; i++) {
						rv.endOf(i, tperiods[i], c.getTime().getTime());
					}
				}
				//}
				//synchronized (binomialVars) { //Not needed because of the synchronized (var_registration_lockObj) statement above
				for (Iterator it = binomialVars.values().iterator(); it.hasNext();) {
					RandomVar rv = (RandomVar) it.next();
					for (int i = 0; i < tperiods.length; i++) {
						rv.endOf(i, tperiods[i], c.getTime().getTime());
					}
				}
				//}
			}
        }
        //        diskCache();
        long r = c.getTime().getTime(); // c should set to next minute
        lastTime = time;

        if (clock.currentTimeMillis() > r) {
            logger.log(this, "Aggregation of stats past the next minute."
                       + " This isn't great, but should not be a problem " 
                       + "unless it happens all the time. Reached time: " 
                       + c.getTime(), Logger.NORMAL);
            return aggregateVars(); //some JREs actually have tail recursion :)
        }

        return c.getTime().getTime();
    }

    public String writeVar(String name, DiagnosticsFormat format) {
		RandomVar rv = (RandomVar) continuousVars.get(name);
		if (rv == null)
			rv = (RandomVar) countingVars.get(name);
		if (rv == null)
			rv = (RandomVar) binomialVars.get(name);
        if (rv == null)
            throw new NoSuchElementException();
        else
            return format.format(rv);
    }

    /**
     * Writes each of the vars formatted to the provided stream.
     */
    public void writeVars(PrintWriter out,
                          DiagnosticsFormat format) {
        top.writeVars(out, format);
        out.flush();
    }

	public double getContinuousValue(String name, int period, int type) {
		RandomVar rv = (RandomVar) continuousVars.get(name);
		if (rv == null)
			throw new IllegalArgumentException("No such field name.");
		else
			return rv.getValue(period, type, clock.currentTimeMillis());
	}
	
	public double getCountingValue(String name, int period, int type) {
			RandomVar rv = (RandomVar) countingVars.get(name);
			if (rv == null)
				throw new IllegalArgumentException("No such field name.");
			else
				return rv.getValue(period, type, clock.currentTimeMillis());
	}
	
	public double getBinomialValue(String name, int period, int type) {
			RandomVar rv = (RandomVar) binomialVars.get(name);
			if (rv == null)
				throw new IllegalArgumentException("No such field name.");
			else
				return rv.getValue(period, type, clock.currentTimeMillis());
	}

	//Try to avoid this method if possible (somewhat ineffective). Use one of the countertype specific ones if possible
    public double getValue(String name, int period, int type) {
		RandomVar rv = (RandomVar) continuousVars.get(name);
		if(rv == null)
			rv = (RandomVar) countingVars.get(name);
		if(rv == null)
			rv = (RandomVar) binomialVars.get(name);

        if (rv == null)
            throw new IllegalArgumentException("No such field name.");
        else
            return rv.getValue(period, type, clock.currentTimeMillis());
    }

	public ExternalBinomial getExternalBinomialVariable(String name){
		RandomVar rv = (RandomVar)binomialVars.get(name);
		if(rv == null)
			throw new IllegalArgumentException("Variable '"+name+"' is not known");
		return new StdExternalBinomial((Binomial)rv);
	}
	public ExternalContinuous getExternalContinuousVariable(String name){
		RandomVar rv = (RandomVar)continuousVars.get(name);
		if(rv == null)
			throw new IllegalArgumentException("Variable '"+name+"' is not known");
		return new StdExternalContinuous((Continuous)rv);
	}
	public ExternalCounting getExternalCountingVariable(String name){
		RandomVar rv = (RandomVar)countingVars.get(name);
		if(rv == null)
			throw new IllegalArgumentException("Variable '"+name+"' is not known");
		return new StdExternalCounting((CountingProcess)rv);
	}



    /**
     * Returns an eventlist suitable for the given period type.
     */
    EventDequeue newList(String name, int type) {
        if (type <= MINUTE)
            return new LinkedEventDequeue();
        else 
            try {
                return new FileEventDequeue(statsDir, name, type);
            } catch (IOException e) {
                logger.log(this, 
                           "Failed to create stat file, reverting to memory", 
                           e, Logger.NORMAL);
                return new LinkedEventDequeue();
            }
    }

    /**
     * Returns an eventlist suitable for occurrences.
     */ 
    EventDequeue newList(String name) {
        return new LinkedEventDequeue();
    }

    /**
     * Returns any existing eventlist, otherwise null
     */
    EventDequeue getList(String name, int type) {
        if (type <= MINUTE) {
            return new LinkedEventDequeue();
        } else {
            try {
                if (FileEventDequeue.isCached(statsDir, name, type)) {
                    logger.log(this, "Found file for var " + name +
                               " type: " + type,
                               Logger.DEBUG);
                    return new FileEventDequeue(statsDir, name, type);
                } else {
                    logger.log(this, "Did NOT find file for var " + name +
                               " type: " + type,
                               Logger.DEBUG);
                    return null;
                }
            } catch (IOException e) {
                logger.log(this, "Failed to read cached stats - data lost?",
                           e, Logger.NORMAL);
                return null;
            }
        }

    }

    private void addToCategory(RandomVar rv, DiagnosticsCategory dc) {
        (dc == null ? top : ((StdDiagnosticsCategory) dc)).addField(rv);
    }

    /**
     * Returns an array of the length of the periods that end at time
     * it, ie if it is the end of an hour, it will return
     * an array = {60*1000 , 60*60*1000}. This is complicated by the fact
     * that months and years are not always the same length (I have a plan
     * for how to fix that involving h-bombs on the lunar surface shifting
     * its orbit to change the rotation speed of the earth so that it evens
     * out to an even number of days per year - but for now we'll have to
     * do with a workaround).
     */
    private long[] getPeriods(Calendar c) {

        if (c.get(Calendar.SECOND) != 0) {
            return new long[] {};
        } // else

        if (c.get(Calendar.MINUTE) != 0) {
            return new long[] {periods[0]};
        } // else

        if (c.get(Calendar.HOUR_OF_DAY) != 0) {
            return new long[] {periods[0], periods[1]};
        } // else

        if (c.get(Calendar.DAY_OF_MONTH) != 1) {
            return new long[] {periods[0], periods[1], periods[2]};
        } // else

        // at least this doesn't happen too often
        Vector v = new Vector(4);
        v.addElement(new Long(periods[0]));
        v.addElement(new Long(periods[1]));
        v.addElement(new Long(periods[2]));
        Calendar d = Calendar.getInstance();
        Date date = c.getTime();
        long time = date.getTime();
        d.setTime(date);
        d.add(Calendar.MONTH, -1);
        v.addElement(new Long(time - d.getTime().getTime()));
        
        if (c.get(Calendar.MONTH) != Calendar.JANUARY) {
            return longsOf(v);
        } // else

        int year = c.get(YEAR);
        // milliseconds in a long runs out some time in year 280 million 
        // something, so it will run out before int year... 
        for (int i = 1 ; year % i == 0 ; i = i*10) { 
            d.setTime(date);
            d.add(YEAR, i * -1);
            v.addElement(new Long(time - d.getTime().getTime()));
        }     
        return longsOf(v);
    }
    
    private long[] longsOf(Vector v) {
        long[] ls = new long[v.size()];
        int i = 0;
        for (Enumeration e = v.elements() ; e.hasMoreElements() ; i++) {
            ls[i] = ((Long) e.nextElement()).longValue();
        }
        return ls;
    }

    /*
     * Returns a Calendar set to the next even minute after time.
     * Note the misnomer here, this ALWAYS rolls the minute...
     */
    private Calendar minuteRoof(long time) {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(time));
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.SECOND, 0);
        c.add(Calendar.MINUTE, 1);
        return c;
    }
}





