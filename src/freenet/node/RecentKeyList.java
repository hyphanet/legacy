package freenet.node;

import freenet.Key;
import freenet.crypt.RandomSource;

/**
 * @author amphibian
 * A class to store a list of recently requested keys
 */
public class RecentKeyList {
	Key[] keys;
	int length;
	final int maxLength;
	RandomSource rand;
	
	public RecentKeyList(int maxLength, RandomSource rand) {
		length = 0;
		this.maxLength = maxLength;
		keys = new Key[maxLength];
		this.rand = rand;
	}
	
	public synchronized Key random() {
		if(length == 0) return null;
		return keys[rand.nextInt(length)];
	}
	
	public synchronized void add(Key k) {
		if(length < maxLength) {
			keys[length] = k;
			length++;
		} else {
			keys[rand.nextInt(maxLength)] = k;
		}
	}
}
