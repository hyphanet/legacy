package freenet.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.StringTokenizer;
import java.util.Vector;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.Logger;
import freenet.support.io.CommentedBufferedReader;

/**
 * Reads and stores Freenet parameters
 */
public class Params extends FieldSet implements PropertySet {

	//=== support classes

	private static class TrimFilter implements Filter {
		public String filter(String s) {
			return s.trim();
		}
	}

	//=== main code

	private final static TrimFilter trimFilter = new TrimFilter();

	private Hashtable opts = new Hashtable();
	private Vector argVector = new Vector();

    private static final String LONG_STRING = 
        "Long (whole number, up to 9,223,372,036,854,775,808, kKmMgGtTpPeE accepted - example 2.1m = 2,100,000)";

    private static final String INT_STRING = 
        "Integer (whole number, up to 2,147,483,648, kKmMgG accepted - example 2.1m = 2,100,000)";

    private static final String SHORT_STRING = 
        "Short Integer (whole number, up to 32,768, kK accepted - example 1.44K = 1475, 1.44k = 1440)";

    private static final String FLOAT_STRING = 
        "Floating point number e.g. 2.1, or 5.8E7";

    private static final String DOUBLE_STRING = 
        "High-precision Floating point number e.g. 2.1, or 5.8E7";

	public Params() {
		super();
	}

	public Params(Params fs) {
		super(fs);
		opts = new Hashtable(fs.opts);
	}

	public Params(FieldSet fs) {
		super(fs);
	}

	public Params(Option[] options) {
		super();
		addOptions(options);
	}

	public Params(Option[] options, Params fs) {
		super(fs);
		addOptions(options);
		opts.putAll(fs.opts);
	}

	public Params(Option[] options, FieldSet fs) {
		super(fs);
		addOptions(options);
	}

	public void addOptions(Option[] options) {
		for (int i = 0; i < options.length; ++i) {
			addOption(options[i].name(), options[i]);
			addOption(Character.toString(options[i].abbrev()), options[i]);
		}
		searchOptions();
	}

	protected void addOption(String name, Option option) {
		int x = name.indexOf('.');
		if (x >= 0)
			((Params) makeSet(name.substring(0, x))).addOption(
				name.substring(x + 1),
				option);
		else
			opts.put(name, option);
	}

	public void readArgs(String[] args) {
		for (int i = 0; i < args.length; i++) {
			argVector.addElement(args[i]);
		}
		searchOptions();
	}

	/**
	 * Iterates over the argVector, taking out any recognized options and
	 * placing them in the fieldset.
	 */
	protected void searchOptions() {
		boolean moreOptions = true;
		Vector newArgs = new Vector(argVector.size());

		for (Enumeration e = argVector.elements(); e.hasMoreElements();) {
			
			String arg = (String) e.nextElement();
			
			if (arg == null) {
				continue;
			}

			Vector opt = new Vector();
			String rarg = null; // residual arg
			if (moreOptions && arg.startsWith("--")) { // word arg
				Option o = getOption(arg.substring(2));
				if (o != null)
					opt.addElement(o);
				else
					rarg = arg;
			} else if (moreOptions && arg.startsWith("-")) {
				int n = arg.length();
				StringBuffer rab = new StringBuffer("-");
				for (int i = 1; i < n; i++) {
					Option o =
						(Option) opts.get(Character.toString(arg.charAt(i)));
					// one char so don't recurse
					if (o != null)
						opt.addElement(o);
					else
						rab.append(arg.charAt(i));
				}
				if (rab.length() > 1)
					rarg = rab.toString();
			} else {
				rarg = arg;
			}

			if (rarg != null) {
				newArgs.addElement(rarg);
			}

			for (Enumeration f = opt.elements(); f.hasMoreElements();) {
				Option o = (Option) f.nextElement();
				if (o.numArgs == 0) {
					put(o.name(), "true");
				} else {
					StringBuffer sb = new StringBuffer();
					if (e.hasMoreElements()) {
						sb.append((String) e.nextElement());
					}
					for (int j = o.numArgs - 1;
						e.hasMoreElements() && j > 0;
						j--) {
						sb.append('\t').append((String) e.nextElement());
						// i changed this to use a tab, not a comma -tc
					}
					put(o.name(), sb.toString());
				}
			}
		}
		argVector = newArgs;
	}

	public void readParams(String[] files)
		throws FileNotFoundException, IOException {

		if (files.length == 0)
			throw new IllegalArgumentException("no files");

		boolean foundOne = false;

		for (int i = 0; i < files.length; ++i) {
			try {
				readParams(files[i]);
				foundOne = true;
			} catch (FileNotFoundException e) {
			}
		}

		if (!foundOne) {
			StringBuffer sb = new StringBuffer();
			if (files.length > 1)
				sb.append("any of ");
			sb.append(files[0]);
			for (int i = 1; i < files.length; ++i) {
				sb.append(", ").append(files[i]);
			}
			throw new FileNotFoundException("Could not find " + sb);
		}
	}

	public void readParams(String filename)
		throws FileNotFoundException, IOException {
		readParams(new FileInputStream(filename));
	}

	public void readParams(File file)
		throws FileNotFoundException, IOException {
		readParams(new FileInputStream(file));
	}

	public void readParams(InputStream is) throws IOException {
		BufferedReader bs =
			new CommentedBufferedReader(new InputStreamReader(is), "[#%");

		parseParams(bs, '=', '.', trimFilter, trimFilter);

		bs.close();
	}

	//=== accessors

	/**
	 * Returns true if the param is set or there is a registered default.
	 */
	public boolean containsKey(String name) {
		return (getParam(name) != null || getOption(name) != null);
	}

	/** @return number of command-line arguments not eaten by a switch */
	public int getNumArgs() {
		return argVector.size();
	}

	/** @return command-line argument at the given position */
	public String getArg(int pos) {
		return pos < argVector.size()
			? (String) argVector.elementAt(pos)
			: null;
	}

	/** @return all command-line arguments as one vector */
	public String[] getArgs() {
		String[] r = new String[argVector.size()];
		argVector.copyInto(r);
		return r;
	}

	/**
	 * This is for breaking down a single-arg switch where the arg is a
	 * comma-separated String. Hence the default value, if applicable, should
	 * also be a comma-separated String.
	 * 
	 * @throws ClassCastException
	 *             if the default is not a String
	 */
	public String[] getList(String name) throws ClassCastException {
		Option o = getOption(name);
		String s = getParam(name);
		if (s == null || s.trim().length()==0)
			s = (o == null ? null : (String) o.defaultValue());
		if (s == null || s.trim().length()==0)
			return new String[0];
		StringTokenizer st = new StringTokenizer(s, ",");
		String[] list = new String[st.countTokens()];
		for (int i = 0; st.hasMoreTokens(); ++i)
			list[i] = st.nextToken().trim();
		return list;
	}

	/**
	 * This is for getting back the args to a switch that took more than one
	 * arg. The intermediate form is a tab-separated String. Hence the default
	 * value, if applicable, should also be a tab-separated String.
	 * 
	 * @throws ClassCastException
	 *             if the default is not a String
	 */
	public String[] getMultipleArgs(String name) {
		Option o = getOption(name);
		String s = getParam(name);
		if (s == null || "".equals(s))
			s =
				(o == null
					|| o.defaultValue() == null ? "" : (String) o.defaultValue());
		StringTokenizer st = new StringTokenizer(s, "\t");
		String[] list = new String[st.countTokens()];
		for (int i = 0; st.hasMoreTokens(); ++i)
			list[i] = st.nextToken().trim();
		return list;
	}

	/**
	 * @return the parameter if found, or the default
	 * @throws ClassCastException
	 *             if the default is not a long, or the parameter is not
	 *             parseable as a long
	 */
	public long getLong(String name) {
		long def = 0;
		Option o = getOption(name);
		if (o != null) {
		    def = ((Long) o.defaultValue).longValue();
		}
		String param = getParam(name);
		if(param == null) {
		    return def;
		} else {
		    try {
		        return parseLong(param, name);
		    } catch (NumberFormatException e) {
		        logERROR("Value could not be parsed - format error perhaps?", param, LONG_STRING, name, e);
		        return def;
		    }
		}
	}

	/**
	 * Log an error resulting from a misparse.
     */
    private void logERROR(String message, String value, String expected, String paramName, Throwable e) {
        String s = "Config error: "+paramName+"="+value+" - "+message+
        	" - expected "+expected+" - detail: "+e;
        Core.logger.log(this, s, e, Logger.ERROR);
        System.err.println(s);
        e.printStackTrace(System.err);
    }

    /**
	 * @return the parameter
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	long parseLong(String s, String argName) throws NumberFormatException {
		long res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			long[] l =
				{
					1000,
					1 << 10,
					1000 * 1000,
					1 << 20,
					1000 * 1000 * 1000,
					1 << 30,
					1000 * 1000 * 1000 * 1000,
					1 << 40,
					1000 * 1000 * 1000 * 1000 * 1000,
					1 << 50,
					1000 * 1000 * 1000 * 1000 * 1000 * 1000,
					1 << 60 };
			while (x >= 0
				&& ((idx = "kKmMgGtTpPeE".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= l[idx];
			}
			res *= Double.parseDouble(s.substring(0, x + 1));
		} catch (ArithmeticException e) {
			logERROR("Too big! Truncating to "+res, s, LONG_STRING, argName, e);
			res = Long.MAX_VALUE;
		}
		return res;
	}

	/**
	 * @return the parameter if found, or the default
	 * @throws ClassCastException
	 *             if the default is not an int
	 */
	public int getInt(String name) {
		int def = 0;
		Option o = getOption(name);
		if (o != null)
			def = ((Integer) o.defaultValue()).intValue();
		String param = getParam(name);
		if(param == null) return def;
		try {
		    return parseInt(getParam(name), name);
		} catch (NumberFormatException e) {
		    logERROR("Value could not be parsed - format error perhaps?", param, INT_STRING, name, e);
		    return def;
		}
	}

    /**
	 * @return the parameter
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	int parseInt(String s, String argName) throws NumberFormatException {
		int res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			long[] l =
				{
					1000,
					1 << 10,
					1000 * 1000,
					1 << 20,
					1000 * 1000 * 1000,
					1 << 30 };
			while (x >= 0
				&& ((idx = "kKmMgG".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= l[idx];
			}
			res *= Double.parseDouble(s.substring(0, x + 1));
		} catch (ArithmeticException e) {
			res = Integer.MAX_VALUE;
			logERROR("Too big! Truncating to "+res, s, INT_STRING, argName, e);
		}
		return res;
	}

    /**
	 * @return the parameter if found, or the default
	 * @throws ClassCastException
	 *             if the default is not a short
	 */
	public short getShort(String name) {
		short def = 0;
		Option o = getOption(name);
		if (o != null)
			def = ((Short) o.defaultValue()).shortValue();
		String param = getParam(name);
		if(param == null)
		    return def;
		else {
		    try {
		        return parseShort(param, name);
		    } catch (NumberFormatException e) {
			    logERROR("Value could not be parsed - format error perhaps?", param, SHORT_STRING, name, e);
			    return def;
		    }
		}
	}

    /**
	 * @return the parameter
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	short parseShort(String s, String argName) throws NumberFormatException {
		short res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			long[] l =
				{
					1000,
					1 << 10 };
			while (x >= 0
				&& ((idx = "kK".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= l[idx];
			}
			res *= Double.parseDouble(s.substring(0, x + 1));
		} catch (ArithmeticException e) {
			res = Short.MAX_VALUE;
			logERROR("Too big! Truncating to "+res, s, SHORT_STRING, argName, e);
		}
		return res;
	}

    public float getFloat(String name) {
		float def = 0;
		Option o = getOption(name);
		if (o != null)
			def = ((Float) o.defaultValue()).floatValue();
		String s = getParam(name);
		if(s == null)
		    return def;
		else {
		    try {
		        return Float.valueOf(s).floatValue();
		    } catch (NumberFormatException e) {
		        logERROR("Value could not be parsed - format error perhaps?", s, FLOAT_STRING, name, e);
		        return def;
		    }
		}
	}

	public double getDouble(String name) {
		double def = 0;
		Option o = getOption(name);
		if (o != null)
			def = ((Double) o.defaultValue()).doubleValue();
		String param = getParam(name);
		if(param == null)
		    return def;
		else {
		    try {
		        return Double.valueOf(param).doubleValue();
		    } catch (NumberFormatException e) {
		        logERROR("Value could not be parsed - format error perhaps?", param, DOUBLE_STRING, name, e);
		        return def;
		    }
		}
	}

	/**
	 * @return the parameter if found, or the default
	 * @throws ClassCastException
	 *             if the default is not a Boolean
	 */
	public boolean getBoolean(String name) {
		boolean def = false;
		Option o = getOption(name);
		if (o != null)
			def = ((Boolean) o.defaultValue()).booleanValue();
		String temp = getParam(name);
		if (temp == null)
			return def;
		return def
			? !(temp.equalsIgnoreCase("no") || temp.equalsIgnoreCase("false"))
			: (temp.equalsIgnoreCase("yes") || temp.equalsIgnoreCase("true"));
	}

	/**
	 * @return the parameter if found, or the default
	 * @throws ClassCastException
	 *             if the default is not a String
	 */
	public String getString(String name) {
		return getString(name, false);
	}

	/**
	 * @return the parameter if found, or the default
	 * @param name
	 *            the parameter name
	 * @param translate
	 *            whether to convert to a string rather than throwing
	 * @throws ClassCastException
	 *             if the default is not a String
	 */
	public String getString(String name, boolean translate) {
		String ret = getParam(name);
		if (ret == null || ret.length()==0) {
			Option o = getOption(name);
			if (o != null)
				ret =
					translate
						? (o.defaultValue().toString())
						: ((String) o.defaultValue());
		}
		return ret;
	}

	/**
	 * Returns the raw string value of the parameter, if it was found during
	 * readArgs() or readParams().
	 */
	public String getParam(String name) {
		FieldSet fieldSet = this;
		StringTokenizer st = new StringTokenizer(name, ".");
		String fname;
		while (true) {
			fname = st.nextToken();
			if (st.hasMoreElements()) {
				fieldSet = getSet(fname);
				if (fieldSet == null)
					return null;
			} else {
				Object val = fieldSet.get(fname);
				if (val != null && val instanceof String) {
					return (String) val;
				}
				return null;
			}
		}
	}

	/**
	 * @return the option associated with the parameter, if it is available.
	 */
	public Option getOption(String name) {
		int x = name.indexOf('.');
		if (x >= 0) {
			Params p = (Params) getSet(name.substring(0, x));
			if (p == null)
				return null;
			return p.getOption(name.substring(x + 1));
		}
		return (Option) opts.get(name);
	}

	/**
	 * Add all options to this Vector
	 */
	public void getOptions(Vector v) {
		Enumeration e = opts.elements();
		for (; e.hasMoreElements();) {
			v.add(e.nextElement());
		}
		for (Iterator i = values().iterator(); i.hasNext();) {
			Object o = i.next();
			if (o instanceof Params)
				 ((Params) o).getOptions(v);
		}
	}

	/**
	 * @return all Options added to this Params
	 */
	public Option[] getOptions() {
		Vector v = new Vector(opts.size());
		getOptions(v);
		Option[] optArray = new Option[v.size()];
		for (int x = 0; x < v.size(); x++)
			optArray[x] = (Option) v.elementAt(x);
		return optArray;
	}

	/**
	 * Like parseFields, but using a system encoding. But unlike
	 * FieldSet.parseFields, we are not expecting trailing garbage. We ignore
	 * lines that don't include the separator, rather than stopping parsing at
	 * that point. This is to try to be a bit fault tolerant with bad config
	 * files.
	 */
	protected String parseParams(
		BufferedReader br,
		char equals,
		char sub,
		Filter nameF,
		Filter valueF)
		throws IOException {

		String s = br.readLine();
		int i;
		while (s != null) {
			if ((i = s.indexOf(equals)) != -1) {
				String fullName = s.substring(0, i);
				String value = valueF.filter(s.substring(i + 1));
				readField(fullName, value, sub, nameF);
				s = br.readLine();
			}
		}
		return s;
	}

	protected FieldSet newFieldSet() {
		return new Params();
	}

	public String toString() {
		StringBuffer buf = new StringBuffer(512);
		buf.append("Params: ").append(opts.size()).append(" Options: \n");

		int x = 0;
		for (Enumeration e = opts.keys(); e.hasMoreElements();) {
			Object key = e.nextElement();
			Object value = opts.get(key);
			buf.append(x++).append(": ").append(key).append(": ").append(
				value).append(
				"\n");
		}
		buf.append("End of Local Options.\n");

		String superString = super.toString();

		x = 0;
		for (Iterator i = keySet().iterator(); i.hasNext();) {
			Object key = i.next();
			Object value = get(key);
			buf.append(x++).append(": ").append(key).append(": ");

			// Grow the string buffer large enough to hold the value plus a
			// small amount of excess. The values can be occasionally large.
			String strValue = null;
			if (value != null) {
				strValue = value.toString();
				buf.ensureCapacity(
					buf.length()
						+ strValue.length()
						+ superString.length()
						+ 25);
			}
			buf.append(strValue).append("\n");
		}
		buf.append(superString).append("End.\n");
		return buf.toString();
	}

}
