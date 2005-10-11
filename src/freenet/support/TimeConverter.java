package freenet.support;

/**
 * functions for handling times
 *
 * @author thelema
 */

public class TimeConverter {

    /*
     * Converts a time duration (in millis) to a string
     * @param age 
     *            the duration
     */

    public static String durationToString(long age) {
	StringBuffer ageString = new StringBuffer();
	if (age < 0)
	    age = 0;
	if (age >= System.currentTimeMillis() - 2000) {
	    return "never";
	}
	age /= 1000; // Throw away milliseconds
	ageString.insert(0, Long.toString(age % 60) + " seconds");
	age /= 60;
	if (age != 0) {
	    ageString.insert(0, Long.toString(age % 60) + " minutes ");
	    age /= 60;
	}
	if (age != 0) {
	    ageString.insert(0, Long.toString(age % 24) + " hours ");
	    age /= 24;
	}
	if (age != 0) {
	    ageString.insert(0, Long.toString(age % 365) + " days ");
	    age /= 365;
	}
	if (age != 0)
	    ageString.insert(0, Long.toString(age) + " years ");
	return ageString.toString();
    }
    
}
