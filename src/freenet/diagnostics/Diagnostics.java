package freenet.diagnostics;
import java.io.PrintWriter;
import java.lang.reflect.Field;

/**
 * This is a diagnostics module for Freenet components. It registers 
 * an arbitrary number of statistics and random variables and exports
 * to FieldSets.
 *
 * It works like this: when registering a setting you set a period of
 * a MINUTE, an HOUR, a DAY, a MONTH, a YEAR, or a DECADE which to remember 
 * all entries. At the end of every such period the the remembered specific
 * entries are aggregated over that period, and aggregates for shorter 
 * periods are aggregated together.
 *
 * For example: Say variable foo is set to aggregate over an HOUR, and 
 * bar is set to aggregate over a month. Then all values returned for 
 * foo and bar will be remembered for the first hour, after which time
 * foo will aggregated to an hourly representation, and specific entries
 * older than an hour will start falling out, while bar will continue to 
 * remember all values. After two hours a second hourly aggregate of foo 
 * will be made an so on. After one day, these hourly aggregates will be
 * be aggregated into a daily aggregate, and hourly aggregates older than
 * one day will start falling out. After one month, bar will finally 
 * aggregate into a monthly value, and entries older than one month will 
 * begin to be forgotten.
 *
 * A warning: It has been a while since I studied statistics. I think the 
 * math is correct, but the terminology could be both right and left.
 * Corrections are appreciated. 
 *
 * @author oskar
 */

public abstract class Diagnostics {

    // Static part

    // Period types
    /** A one minute period */
    public static final int MINUTE = 0;
    /** A one hour period */
    public static final int HOUR   = 1;
    /** A one day period */
    public static final int DAY    = 2;
    /** A one month period */
    public static final int MONTH  = 3;
    /** A one year period. Period values greater than this represent
     *  exponents of 10 years. */
    public static final int YEAR   = 4;
    /** A one decade period.  */
    public static final int DECADE = 5;

    // Value types
    /** The number of events over the period, recorded for all types. */
    public static final int NUMBER_OF_EVENTS = 1;

    // Continuous specific
    /** The mean value over the period. Only works with "Continuous" types. */
    public static final int MEAN_VALUE = 2;
    /** The standard deviation over the period. Only works with "Continuous"
        types. */
    public static final int STANDARD_DEVIATION = 3;
    /** The minimum value observed over the period. Only works with 
        "Continuous" types. */
    public static final int MIN_VALUE = 4;
    /** The maximym value observed over the period. Only works with 
        "Continuous" types. */
    public static final int MAX_VALUE = 5;

    // Binomial specific.
    /** The estimate chance of success for a "Binomial" type. */
    public static final int SUCCESS_PROBABILITY = 6;

    // Counting process specific
    /** The change in the count over the period for a CoutingProcess. */
    public static final int COUNT_CHANGE = 7;
    /** The mean time between events in a CountingProcess. */
    public static final int MEAN_TIME_BETWEEN_EVENTS = 8;
    /** The time weighted mean of the total number of events during this 
        runtime. */
    public static final int MEAN_RUNTIME_COUNT = 9;

    static final long[] periods;
    static { 
        periods = new long[DECADE];
        periods[0] = 60 * 1000;
        periods[1] = 60 * periods[0];
        periods[2] = 24 * periods[1];
        periods[3] = 31 * periods[2];
        periods[4] = 366 * periods[2];
    }

    /**
     * Returns the length of a period in milliseconds.
     */
    public static final long getPeriod(int n) {
        if (n <= YEAR) {
            return periods[n];
        } else {
            return periods[YEAR] * (long) Math.pow(10,(n - YEAR));
        }
    }

   /**
    * Returns the name of the period denoted by the Integer value
    * above.
    */
    public static String getName(int period) {
        switch(period) {
        case MINUTE : 
            return "minute";
        case HOUR :
            return "hour";
        case DAY :
            return "day";
        case MONTH :
            return "month";
        case YEAR :
            return "year";
        case DECADE :
            return "decade";
        }
        return Double.toString(Math.pow(10,period - YEAR)) + "years";
    }

    /**
     * Returns the integer value for a period denoted by the name.
     */
    public static int getPeriod(String name) {
        name = name.toUpperCase();
        try {
            Field f = Diagnostics.class.getField(name);
            return ((Integer) f.get(null)).intValue();
        } catch (NoSuchFieldException e) {
        } catch (SecurityException e) {
        } catch (IllegalAccessException e) {
        }
        if (name.endsWith("YEARS")) {
            int n = Integer.parseInt(name.substring(0, name.length() - 2));
            int y = (int) Math.round(Math.log(n) / Math.log(10));
            return YEAR + y;
        } else
            throw new IllegalArgumentException("No such period type: " + name);
    }

    // public methods

    /**
     * Registers a new Diagnostics category for output sorting.
     * @param name     The name of the category to add.
     * @param comment  A comment regarding the category.
     * @param parent   The parent category (which must already be added)
     *                 null for top level.
     */
    public abstract DiagnosticsCategory addCategory(String name, 
                                                    String comment,
                                                    DiagnosticsCategory parent);
    
    /**
     * Registers a binomial variable, that is one that measures the success
     * rate over a number tries. Binomial occurrences are registered as a 
     * number n for the number tries, and x for the number that were 
     * successful. Aggregation occurs by adding each of the two for the 
     * entire period.
     * @param name       The name to give the new variable.
     * @param aggPeriod  One of MINUTE, HOUR, etc above to be the smallest
     *                   period of aggregation.
     * @param comment    A comment describing the data.
     * @param cat        The category to use (or null for top).
     */
    public abstract void registerBinomial(String name, int aggPeriod, 
                                          String comment, 
                                          DiagnosticsCategory cat);

    /**
     * Records the occurrence for a binomial variable. If the name is 
     * wrong or x > n, a error will be logged but no exception thrown.
     * Also see the getExternalBinomialVariable method.
     * @param name  Must be name of previously registered binomial var.
     * @param n     The number of tries in this occurrence.
     * @param x     The number that were successful.
     */
    public abstract void occurrenceBinomial(String name, long n, long x);

    /**
     * Registers a continuous variable, that is one that returns arbitrary
     * continuous results, and thus has an average that approaches a normal
     * distribution. When results are aggregated, mean and stddeviation values
     * are kept.
     * @param name    The name to give to the new variable
     * @param period  One of MINUTE, HOUR, etc above to use a minimun 
     *                aggregation.
     * @param comment    A comment describing the data.
     * @param cat        The category to use (or null for top).
     */
    public abstract void registerContinuous(String name, int period, 
                                            String comment,
                                            DiagnosticsCategory cat);

    /**
     * Records a value for a continuous random variable.
     * Also see the getExternalContinousVariable method.
     * @param name  The name of the variable to update, if the name is
     *              not registered or not a continuous var, and error will
     *              be logged but no exception thrown.
     * @param value  The value to record.
     */
    public abstract void occurrenceContinuous(String name, double value);

    /**
     * Registers a Counting Process variables. No magic here, these simply
     * count the number of occurrences.
     * @param name    The name to give to the new variable
     * @param period  One of MINUTE, HOUR, etc above to use a minimun 
     *                aggregation.
     * @param comment    A comment describing the data.
     * @param cat        The category to use (or null for top).
     */
    public abstract void registerCounting(String name, int period, 
                                          String comment, 
                                          DiagnosticsCategory cat);

    /**
     * Records a number of occurrence to a counting process.
     * Also see the getExternalCountingVariable method.
     * @param name  The name of the variable to update, if the name is
     *              not registered or not a counting process, and error will
     *              be logged but no exception thrown.
     * @param  n   The number of occurrences to record.
     */
    public abstract void occurrenceCounting(String name, long n);

    /**
     * Performs aggregation on periods that have passed since the last time
     * this method was called. 
     * @return  The next time this method should be called, as an absolute
     *          time in milliseconds of the epoch (System.currentTimeMillis() 
     *          format).
     */
    public abstract long aggregateVars();

    /**
     * Return the data for a variable as a string.
     * @param name    The name of the variable. 
     * @param format   The format to use for the output.
     */
    public abstract String writeVar(String name, DiagnosticsFormat format);
                           

    /**
     * Write the data for all variables to a stream.
     * @param out      The stream to write to.
     * @param format   The format to use for the output.
     */
    public abstract void writeVars(PrintWriter out, 
                          DiagnosticsFormat format);


    /**
     * Extract a diagnostics value.
     * @param name     The name of the field (variable) to extract data from.
     * @param period   Use data recorded over the last period of this type.
     * @param type     The type of data to extract, from above.
     */
    public abstract double getValue(String name, int period,
                                    int type);
	/**
	 * Extract a continuous type of diagnostics value.
	 * @param name     The name of the field (variable) to extract data from.
	 * @param period   Use data recorded over the last period of this type.
	 * @param type     The type of data to extract, from above.
	 */
	public abstract double getContinuousValue(String name, int period,
										int type);
	/**
	 * Extract a counting type of diagnostics value.
	 * @param name     The name of the field (variable) to extract data from.
	 * @param period   Use data recorded over the last period of this type.
	 * @param type     The type of data to extract, from above.
	 */
	public abstract double getCountingValue(String name, int period,
										int type);
	/**
	 * Extract a binomial type of diagnostics value.
	 * @param name     The name of the field (variable) to extract data from.
	 * @param period   Use data recorded over the last period of this type.
	 * @param type     The type of data to extract, from above.
	 */
	public abstract double getBinomialValue(String name, int period,
										int type);
	
	/**
	* Extract a binomial type of diagnostics variable for external reporting.
	* When a large number of reports will be supplied to a diagnostics variable it
	* is generally preffered to use this method to fetch its external representation
	* once and an for all and then supply reports directly to it (more easily readable
	* code as well as performance benefits)
	* @param name     The name of the field (variable) to extract data from.
	*/
	public abstract ExternalBinomial getExternalBinomialVariable(String name);
	
	/**
	* Extract a continuous type of diagnostics variable for external reporting.
	* When a large number of reports will be supplied to a diagnostics variable it
	* is generally preffered to use this method to fetch its external representation
	* once and an for all and then supply reports directly to it (more easily readable
	* code as well as performance benefits)
	* @param name     The name of the field (variable) to extract data from.
	*/
	public abstract ExternalContinuous getExternalContinuousVariable(String name);
	
	/**
	* Extract a counting type of diagnostics variable for external reporting.
	* When a large number of reports will be supplied to a diagnostics variable it
	* is generally preffered to use this method to fetch its external representation
	* once and an for all and then supply reports directly to it (more easily readable
	* code as well as performance benefits)
	* @param name     The name of the field (variable) to extract data from.
	* */
	public abstract ExternalCounting getExternalCountingVariable(String name);
	
}

