package freenet.support.graph;

import java.util.Vector;

/**
 * A surface that draws to a plane of palletized pixels.
 * currently has 8-bit pixels and 24-bit pallette entries
 *
 * @author <a href="mailto:coates@windmail.net">Benjamin Coates</a>
 */
public final class Bitmap implements Surface {
	private int pen;
	private Rectangle coord;
	private float x, y;

	private int width, height;

	private byte[][] pixels;

	private Vector pallette; // Vector of Color

	public Bitmap(int width, int height) {
		// clear will set these
		pixels = null;
		pallette = null;

		coord = new Rectangle(0, 0, width, height);

		x = 0;
		y = 0;
		pen = 0;
		this.width = width;
		this.height = height;
		clear(new Color(255, 255, 255));
	}
	
	public void setGreyscalePalette() {
		pallette = new Vector(256);
		for (int x = 255; x >= 0; x--) {
			pallette.addElement(new Color(x, x, x));
		}
	}

	// todo: not go insane when the 257th color is selected
	public Color setPenColor(Color c) {
		Color ret = pallette.isEmpty() ? c : (Color) pallette.elementAt(pen);

		pen = pallette.indexOf(c);
		if (pen > 255)
			throw new IllegalArgumentException("No more colors!");

		if (pen == -1) {
			pen = pallette.size();
			if (pen > 255)
				throw new IllegalArgumentException("No more colors!");
			pallette.addElement(c);
		}

		return ret;
	}

	public Color setPenColorByIndex(int x) {
		if (x < 0 || x >= pallette.size())
			throw new IllegalArgumentException("Out of range");
		pen = x;
		return (Color) pallette.elementAt(x);
	}

	/**
	 * slightly odd behavior: if the Bitmap is cleared to a color different than the current pen, the current pen will get index 1 even if no drawing is ever done with it. So set the pen first then clear if you care about optimal pallette usage.
	 */
	public void clear(Color c) {
		// I'm going to assume memory allocation is
		// faster than any array-setting loop i could write.
		pixels = new byte[width][height];
		pallette = new Vector();

		// use setPenColor to force the background into the pallette;
		// since the pallette was just erased it will get entry 0.
		setPenColor(setPenColor(c));
	}

	public void moveTo(float newx, float newy) {
		x = newx;
		y = newy;
	}

	/**
	 * Draws in pixel-array coordinates px,py with the current pen, does not clip.
	 */
	public void setPixel(int px, int py) {
		if (px >= 0 && px < width && py >= 0 && py < height)
			pixels[px][py] = (byte) pen;
		else
			throw new IndexOutOfBoundsException("("+px+","+py+") not inside image of width="+width+" and height="+height);
	}

	//Draws a five-pixel plus centered on the supplied coordinate
	//Clips the pixel properly and respects scaleView coordinates
	public void drawPlus(float px, float py) {
		int x = clipX(Math.round(xPix(px)));
		int y = clipY(Math.round(yPix(py)));
		setPixel(x, y);
		if(x-1 >= 0) //dont even try to draw outside the image bounds
			setPixel(x - 1, y);
		if(x+1<width) 
			setPixel(x + 1, y);
		if(y-1>=0)
			setPixel(x, y - 1);
		if(y+1<height)
			setPixel(x, y + 1);
	}

	public int getPixel(int px, int py) {
		if (px >= 0 && px < width && py >= 0 && py < height) {
			return ((pixels[px][py]) & 0xff);
		} else
			return -1;
	}

	public void drawTo(float newx, float newy) {
		int x1 = clipX(Math.round(xPix(x)));
		int y1 = clipY(Math.round(yPix(y)));
		int x2 = clipX(Math.round(xPix(newx)));
		int y2 = clipY(Math.round(yPix(newy)));

		int dx, dy, xdir, ydir;

		if (x2 >= x1) {
			dx = x2 - x1;
			xdir = 1;
		} else {
			dx = x1 - x2;
			xdir = -1;
		}

		if (y2 >= y1) {
			dy = y2 - y1;
			ydir = 1;
		} else {
			dy = y1 - y2;
			ydir = -1;
		}

		/* dx, dy, and e are inmeasure in half-pixels */
		dx *= 2;
		dy *= 2;

		// todo: initialize e based on rounding error
		int e = 0;

		if (dx > dy) {
			// horizontal, x-stepping version
			for (; x1 != x2; x1 += xdir) {
				setPixel(x1, y1);
				e += dy;
				if (e >= dx) {
					y1 += ydir;
					e -= dx;
				}
			}
		} else {
			// vertical, y-stepping version
			for (; y1 != y2; y1 += ydir) {
				setPixel(x1, y1);
				e += dx;
				if (e >= dy) {
					x1 += xdir;
					e -= dy;
				}
			}
		}

		moveTo(newx, newy);
	}

	public void scaleView(Rectangle r) {
		// scale x to between 0 and 1, then unscale it to the new coords
		x = ((x - coord.left) / (coord.left - coord.right)) * (r.left - r.right) + r.left;
		y = ((x - coord.top) / (coord.top - coord.bottom)) * (r.top - r.bottom) + r.top;

		coord = r;
	}
	//Resets the view to represent all of the bitmap
	public void resetScaleView(){
		scaleView(new Rectangle(0,0,getWidth(),getHeight()));
	}

	/**
	 * Convert from a point in user-coordinates to a point on the pixel array. use Math.round() on the result to get the actual offset into pixels. happily returns values outside the view box.
	 * 
	 * Be sure to clip based on the rounded result; -0.4 is inside the view box, -0.6 is not.
	 */
	private float xPix(float xu) {
		return ((xu - coord.left) / (coord.left - coord.right)) * (0.5f - width);
	}

	private float yPix(float yu) {
		return ((yu - coord.top) / (coord.top - coord.bottom)) * (0.5f - height);
	}

	private int clipX(int x) {
		if(x < 0) x = 0;
		if(x >= width) x = width-1;
		return x;
	}
	
	private int clipY(int y) {
		if(y < 0) y = 0;
		if(y >= height) y = height-1;
		return y;
	}
	
	byte[][] getPixels() {
		return pixels;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	Vector getPallette() {
		return pallette;
	}
	
	//Ugly and *very* ineffective conversion method.. better than nothing though
	public void copyTo(java.awt.Graphics g)
	{
		for(int x = 0;x<width-1;x++)
		{	
			for(int y = 0;y<height-1;y++)
			{	
				Color col = (Color)pallette.elementAt(getPixel(x,y));
				g.setColor(new java.awt.Color(col.getRedAsInt(),col.getGreenAsInt(),col.getBlueAsInt()));
				g.drawLine(x,y,x,y);
			}
		}
	}
}

