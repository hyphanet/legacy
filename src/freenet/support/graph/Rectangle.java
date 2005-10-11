package freenet.support.graph;

import java.util.StringTokenizer;
import java.util.NoSuchElementException;

public class Rectangle
{
    public final float left, top, right, bottom;
    
    /* from a string in the format returned by toString() */
    public Rectangle(String s) throws IllegalArgumentException
    {
        if (s == null)
            throw new IllegalArgumentException();
        
        try {
            StringTokenizer st = new StringTokenizer(s, "_", false);
            
            left = Float.intBitsToFloat(Integer.parseInt(st.nextToken()));
            top = Float.intBitsToFloat(Integer.parseInt(st.nextToken()));
            right = Float.intBitsToFloat(Integer.parseInt(st.nextToken()));
            bottom = Float.intBitsToFloat(Integer.parseInt(st.nextToken()));
        } catch ( NoSuchElementException e ) {
            throw new IllegalArgumentException();
        }
    }
    
    public String toString()
    {
        return "" + Float.floatToIntBits(left) + "_" +
                    Float.floatToIntBits(top) + "_" +
                    Float.floatToIntBits(right) + "_" +
                    Float.floatToIntBits(bottom);
    }

    public Rectangle(float left, float top, float right, float bottom)
    {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }
}