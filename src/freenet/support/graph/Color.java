package freenet.support.graph;

/**
 *  represents a 24-bit color
 */
public final class Color
{
    private byte r;
    private byte g;
    private byte b;
    
    /**
     * Create a new color.  red, green, and blue are in the range 0-255.
     */
    public Color(int red, int green, int blue)
    {
        r = (byte) red;
        g = (byte) green;
        b = (byte) blue;
    }
    
    public static Color subtract(Color nr1,Color nr2){
    	return new Color(Math.max(0,nr1.getRedAsInt()-nr2.getRedAsInt()),Math.max(0,nr1.getGreenAsInt()-nr2.getGreenAsInt()),Math.max(0,nr1.getBlueAsInt()-nr2.getBlueAsInt()));
    }
	public static Color add(Color nr1,Color nr2){
		return new Color(Math.min(255,nr1.getRedAsInt()+nr2.getRedAsInt()),Math.min(255,nr1.getGreenAsInt()+nr2.getGreenAsInt()),Math.min(255,nr1.getBlueAsInt()+nr2.getBlueAsInt()));
	}
    
    /**
     * get the color value as a an int, in the blue-green-red-reserved format
     * used by windows DIB pallettes
     *
     * bits (msb) 31..24 blue, 23..16 green, 15..8 red, 7..0 (lsb) zero
     */
    int asBGRX()
    {
        int ret = 0;
        
        ret |= (b & 0xFF) << 24;
        ret |= (g & 0xFF) << 16;
        ret |= (r & 0xFF) << 8;
        
        return ret;
    }
    
    byte getRed()
    {
        return r;
    }
    
    byte getBlue()
    {
        return b;
    }
    
    byte getGreen()
    {
        return g;
    }
    
    public boolean equals(Object o) {
		if (o instanceof Color) {
			Color c = (Color) o;
			return r == c.r && g == c.g && b == c.b;
		} else
			return false;
	}
    public int getRedAsInt(){
    	return (r & 0xFF);
    }
	public int getGreenAsInt(){
    	return (g & 0xFF);
	}
	public int getBlueAsInt(){
		return (b & 0xFF);
	}
    public String toHexString(){
    	String sr = Integer.toHexString(getRedAsInt());
    	if(sr.length() == 1)
    		sr = "0"+sr;
		String sg = Integer.toHexString(getGreenAsInt());
		if(sg.length() == 1)
			sg = "0"+sg;
		String sb = Integer.toHexString(getBlueAsInt());
		if(sb.length() == 1)
			sb = "0"+sb;
    	return sr+sg+sb;
    }
}
