package freenet.support.graph;

/**
 * A Surface recieves drawing commands which describe an image.
 */
public interface Surface
{
   /**
    * Clear the entire surface.
    *
    * @param c new color for the entire surface
    */
    public void clear(Color c);
    
   /**
    * Set the pen color.  New drawing commands will be in this color.
    *
    * The pen's initial state is undefined, so always call this method
    * before drawing.
    * 
    * @param c the new pen color
    * @return the previous pen color, so that a method can leave the 
    *         surface's pen in the same state it found it
    */
    public Color setPenColor(Color c);
    
   /**
    * move current position to x, y without drawing anything.
    */
    public void moveTo(float x, float y);
    
   /**
    * draw from the current position to x, y using the current pen.
    * x, y becomes the new current position.
    */
    public void drawTo(float x, float y);
    
    /**
     * Adjust the coordinate system so that left, right, top, and bottom 
     * corespond with those edges of the surface.
     * the current position does not move on the surface, so its x,y value 
     * will change.
     */
    public void scaleView(Rectangle r);
}
