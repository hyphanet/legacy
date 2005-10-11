package freenet.support.graph;

import java.io.*;

/**
 * Encodes a bitmap as a monochrome bitmap in the bizarre xbm c sourcecode
 * bitmap format, apparently used by X Window and viewable by most (all?)
 * browsers.
 *
 * currently does color->monochrome translation by calling index 0 
 * (background) white and all other colors black.
 */
public final class XBitmapEncoder extends BitmapEncoder
{
    static {
        register(new XBitmapEncoder());
    }
    
    private Bitmap b = null;
    
    public String getExt()
    {
        return "xbm";
    }
    
    public String getMimeType()
    {
        return "image/x-xbitmap";
    }
    
    public void setBitmap(Bitmap b)
    {
        this.b = b;
    }
    
    private class BitmapIterator
    {
        private final byte[][] bits;
        private boolean done;
        private int x, y, h, w;
        
        BitmapIterator()
        {
            bits = b.getPixels();
            done = false;
            x = 0;
            y = 0;
            h = b.getHeight();
            w = b.getWidth();
        }
        
        int nextbit()
        {
            int ret;
            if (isdone())
                ret = 0;
            else 
            {
                if (bits[x][y] != 0)
                    ret = 1;
                else 
                    ret = 0;
                    
                if (++x == w)
                {
                    x = 0;
                    if (++y == h)
                        done = true;
                }
            }
            
            return ret;
        }
        
        boolean isdone()
        {
            return done;
        }
    }
    
    final static String wh = "#define graph_width ";
    final static String hh = "#define graph_height ";
    final static String bh = "static unsigned char graph_bits[] = {";
    final static String ind = "  ";
    final static String hex = "0x";
    final static String eob = "};";
    final static String sep = ", ";
    
    public int encode(OutputStream out) {
        PrintStream ps = new PrintStream(out);
        
        ps.print(wh + b.getWidth() + "\n");
        ps.print(hh + b.getHeight() + "\n");
        ps.print(bh + "\n");
        
        BitmapIterator bi = new BitmapIterator();
        
        while (!bi.isdone()) {
            ps.print(ind);
            for (int by = 0; (by != 12) && !bi.isdone(); ++by)
            {
                ps.print(hex);
                
                int val = 0;
                for (int bit = 0; bit != 8; ++bit)
                {
                    val >>= 1;
                    val |= bi.nextbit() << 7;
                }
                
                ps.print(Integer.toHexString(val));
                
                if (bi.isdone())
                    ps.print(eob);
                else
                    ps.print(sep);
            }
            ps.print("\n");
        }
        
        ps.flush();
        
        return size();
    }
    
    /**
     * I really don't feel like computing the size.
     * @return -1
     */
    public int size()
    {
        return -1;
    }
}








