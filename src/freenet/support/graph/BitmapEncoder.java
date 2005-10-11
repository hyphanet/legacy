package freenet.support.graph;

import java.io.*;
import java.util.Hashtable;
import java.util.Enumeration;

/**
 * a BitmapEncoder takes a bitmap and writes it into an OutputStream.
 * the byte format written depends on the BitmapEncoder implementation.
 */
public abstract class BitmapEncoder
{
    private static Hashtable h;
    
    /**
     * Todo: something less bizarre
     */
    static {
        h = new Hashtable();
        DibEncoder.class.toString();
        XBitmapEncoder.class.toString();
    }
    
    public BitmapEncoder() {}
    
    /**
     * Set the bitmap to be used.
     */
    public abstract void setBitmap(Bitmap b);
    
    /**
     * Encode the bitmap into a stream of bytes.
     * @return the same value as @see size
     */
    public abstract int encode(OutputStream out) throws IOException;
    
    /**
     * @return the number of bytes that will be written 
     * by the encode() function, or -1 if unavailable.
     */
    public abstract int size();
    
    /**
     * get the customary file name extension for this format,
     * such as "gif"
     * @return the extension, excluding the leading dot.
     */
    public abstract String getExt();
    
    /**
     * get the MIME type for this format, such as "image/jpeg"
     */
    public abstract String getMimeType();
    
    public static BitmapEncoder createByMimeType(String mime)
    {
        if (mime == null)
            return null;
            
        BitmapEncoder be = (BitmapEncoder) h.get(mime);
        
        if (be == null)
            return null;
            
        /* slight weirdness: we never return the stored BitmapEncoder derived object,
           just a newly created object of the same type. */
        BitmapEncoder ret = null;
        
        try {
            ret = (BitmapEncoder) be.getClass().newInstance();
        } catch (InstantiationException e) {
        } catch (IllegalAccessException e) {
        }
            
        return ret;
    }
    
    /**
     * Enumerate across all the BitmapEncoders available.  
     * don't call "stateful" methods on these (encode, setBitmap)
     * (use getClass().newInstance() on them or createByMimeType(getMimeType())
     * to get an object you can use those methods on)
     * but this can be used to find all available mime types for example
     */
    public static Enumeration getEncoders()
    {
        return h.elements();
    }
    
    protected static void register(BitmapEncoder be)
    {
        h.put(be.getMimeType(), be);
    }
}