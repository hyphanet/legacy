package freenet.diagnostics;

import java.io.IOException;
import java.io.PrintWriter;

import freenet.FieldSet;
import freenet.node.rt.ValueConsumer;
import freenet.support.io.ReadInputStream;

/**
 * A diagnostics implementation that eats all values and does nothing.
 * Optionally, it can be created with a resource that returns default values.
 */
public class VoidDiagnostics extends Diagnostics {

	DiagnosticsCategory voidcat = new VoidDiagnosticsCategory();

	FieldSet values;

	public VoidDiagnostics(String resource) {
		this();
		try {
			ReadInputStream in =
				new ReadInputStream(getClass().getResourceAsStream(resource));
			values.parseFields(in);
			in.close();
		} catch (IOException e) {
		}
	}

	public VoidDiagnostics() {
		values = null;
	}

	public DiagnosticsCategory addCategory(
		String name,
		String comment,
		DiagnosticsCategory parent) {
		return voidcat;
	}

	/**
	 * Does nothing.
	 */
	public void registerBinomial(
		String name,
		int aggPeriod,
		String comment,
		DiagnosticsCategory dc) {
	}
	
	/**
	 * Does nothing.
	 */
	public void occurrenceBinomial(String name, long n, long x) {
	}
	
	/**
	 * Does nothing.
	 */
	public void registerContinuous(
		String name,
		int period,
		String comment,
		DiagnosticsCategory dc) {
	}
	
	/**
	 * Does nothing.
	 */
	public void occurrenceContinuous(String name, double value) {
	}
	
	/**
	 * Does nothing.
	 */
	public void registerCounting(
		String name,
		int period,
		String comment,
		DiagnosticsCategory dc) {
	}
	/**
	 * Does nothing.
	 */
	public void occurrenceCounting(String name, long n) {
	}

	/**
	 * Does nothing.
	 * 
	 * @return 1000*60*60*24, or one day.
	 */
	public long aggregateVars() {
		return System.currentTimeMillis() + 1000 * 60 * 60 * 24;
	}

	public String writeVar(String name, DiagnosticsFormat df) {
		return "";
	}

	/**
	 * Writes nothing.
	 */
	public void writeVars(PrintWriter out, DiagnosticsFormat df) {
	}

	public double getValue(String name, int period, int value) {
		if(values == null)
			return Double.NaN;
		FieldSet fs = values.getSet(name);
		return (
			fs == null
				? Double.NaN
				: Double.valueOf(fs.getString(name)).doubleValue());

	}
	public double getContinuousValue(String name, int period,int type){
		return getValue(name,period,type);
	}
	public double getCountingValue(String name, int period,int type){
			return getValue(name,period,type);
		}
	public double getBinomialValue(String name, int period,int type){
		return getValue(name,period,type);
	}
	
	public ExternalBinomial getExternalBinomialVariable(String name){
		return new VoidExternalBinomial();
	}
	public ExternalContinuous getExternalContinuousVariable(String name){
		return new VoidExternalContinuous();
	}
	public ExternalCounting getExternalCountingVariable(String name){
		return new VoidExternalCounting();
	}

	private class VoidDiagnosticsCategory implements DiagnosticsCategory {
		public String name() {
			return "";
		}
		public String comment() {
			return "";
		}
		public int level() {
			return 0;
		}
		public DiagnosticsCategory getParent() {
			return null;
		}
	}
	public class VoidExternalBinomial implements ExternalBinomial {
		public void count(long n, long x){}
		public double getValue(int period, int type){return 0;}
		public void relayReportsTo(ValueConsumer r,int type){}
	}

	public class VoidExternalContinuous implements ExternalContinuous {
		public void count(double value){}
		public double getValue(int period, int type){return 0;}
		public void relayReportsTo(ValueConsumer r,int type){}
	}
	
	public class VoidExternalCounting implements ExternalCounting {
		public void count(long n){}
		public double getValue(int period, int type){return 0;}
		public void relayReportsTo(ValueConsumer r,int type){}
	}
}
