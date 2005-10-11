package freenet.support;

import freenet.Key;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.io.DataOutputStream;
import java.io.IOException;
import net.i2p.util.NativeBigInteger;
/**
 * 
 * @author thelema
 * 
 * This class is designed to be an efficient math library for dealing with
 * modular x-byte values (for values of x equal 23, that of keys)
 *  
 */

public class KeyMath implements DataObject {

    private static int NUM_OF_BITS = 23 * 8;
    
    private BigInteger limit = BigInteger.ONE.shiftLeft(NUM_OF_BITS);

    private BigInteger mask = limit.subtract(BigInteger.ONE);

    private BigInteger data;
    
    public static KeyMath ZERO = new KeyMath(BigInteger.ZERO);

    public static KeyMath NEG_ONE = new KeyMath(BigInteger.ZERO);
    static {NEG_ONE.inverse();}
    
    public KeyMath(NativeBigInteger b) {
        if (limit.compareTo(b) != 1) throw new NumberFormatException();
        this.data = b;
    }
    
    public KeyMath(BigInteger b) {
        if (limit.compareTo(b) != 1) throw new NumberFormatException();
        this.data = new NativeBigInteger(b);
    }

    public KeyMath(byte[] item) {
        if (item.length > 23)
            throw new NumberFormatException();
        data = new NativeBigInteger(item);
    }

    public KeyMath(Key item) {
        this(item.getVal());
    }

    public KeyMath(double item) {
        this(new BigDecimal(item).toBigInteger());
    }
    
    public void add(KeyMath item) {
        BigInteger temp = data.add(item.data);
//        return new KeyMath(temp.and(mask));
        data = limit.compareTo(temp) == 1 ? temp.subtract(limit) : temp;
    }

    public void inverse() {
        data = limit.subtract(data);
    }

    public void subtract(KeyMath item) {
        BigInteger temp = data.subtract(item.data);
        data = ZERO.compareTo(temp) == -1 ? temp.add(limit) : temp; 
    }

    public void divide(byte item) {
        divide(new BigInteger(new byte[] {item}));
    }

    private void divide(BigInteger item) {
        data = data.divide(item);
    }
    
    public void shiftRight(int count) {
        data = data.shiftRight(count);
    }

    public double toDouble() {
        return data.doubleValue();
    }

    public int compareTo(Object o) {
        return data.compareTo(((KeyMath)o).data);
    }

    public int getDataLength() {
        return 23;
    }
    
    public void writeDataTo(DataOutputStream out) throws IOException {
        byte[] send = new byte[23];
        byte[] databytes = data.toByteArray();
        System.arraycopy(databytes, 0, send, 23-databytes.length, databytes.length);
        out.write(send);
    }
}