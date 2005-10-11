package freenet.support;

public class Pair {

	private Object key;
	private Object value;

	public Pair(Object key, Object value) {
		this.key = key;
		this.value = value;
	}

	/**
	 * Returns the key corresponding to this entry
	 * 
	 * @return the key corresponding to this entry
	 */
	public Object getKey() {
		return key;
	}

	/**
	 * Returns the value corresponding to this entry
	 * 
	 * @return the value corresponding to this entry
	 */
	public Object getValue() {
		return value;
	}

	/**
	 * Sets the value corresponding to this entry
	 * 
	 * @return the previous value
	 */
	public Object setValue(Object newval) {
		Object oldval = value;
		value = newval;
		return oldval;
	}
}
