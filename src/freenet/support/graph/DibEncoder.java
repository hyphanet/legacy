package freenet.support.graph;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

import javax.servlet.http.HttpServletResponse;

/**
 * DibEncoder encodes a Bitmap to a palletized, 8-bit Microsoft .bmp file
 * format. It doesn't employ compression or any strange options.
 */
public final class DibEncoder extends BitmapEncoder {
	private Bitmap b = null;

	static {
		register(new DibEncoder());
	}

	public String getExt() {
		return "bmp";
	}

	public String getMimeType() {
		// The "image/bmp" MIME type works in both Mozilla Thunderbird and
		// Internet Explorer where the alternative "image/x-ms-bmp" does not.
		return "image/bmp";
	}

	private static void writeIntLE(OutputStream o, int i) throws IOException {
		o.write(i);
		o.write(i >> 8);
		o.write(i >> 16);
		o.write(i >> 24);
	}

	private static void writeShortLE(OutputStream o, int s)
		throws IOException {
		o.write(s);
		o.write(s >> 8);
	}

	public void setBitmap(Bitmap b) {
		this.b = b;
	}

	/**
	 * For reasonable performance the output stream be buffered if at all
	 * possible.
	 */
	public int encode(OutputStream out) throws IOException {
		Vector pal = b.getPallette();
		byte[][] pixels = b.getPixels();
		int w = b.getWidth();
		int h = b.getHeight();

		out.write('B'); // magic
		out.write('M');

		writeIntLE(out, size()); // byte size of file
		writeShortLE(out, 0); // reserved
		writeShortLE(out, 0); // reserved
		writeIntLE(out, 54 + 4 * pal.size()); // byte offset to bitmap bits
		writeIntLE(out, 40); // size of this header structure
		writeIntLE(out, w); // width in pixels
		writeIntLE(out, h); // height in pixels
		writeShortLE(out, 1); // planes
		writeShortLE(out, 8); // bits per pixel
		writeIntLE(out, 0); // compression
		writeIntLE(out, 0); // size (ignored if uncompressed)
		writeIntLE(out, 0); // horizontal resolution (optional)
		writeIntLE(out, 0); // vertical resolution (optional)
		writeIntLE(out, pal.size()); // number of colors
		writeIntLE(out, pal.size()); // number of "important" colors

		for (int i = 0; i != pal.size(); ++i) {
			Color c = (Color) pal.elementAt(i);
			out.write(c.getBlue());
			out.write(c.getGreen());
			out.write(c.getRed());
			out.write(0);
		}

		// dibs are bottom-up.
		for (int i = h - 1; i != -1; --i) {
			for (int j = 0; j != w; ++j)
				out.write(pixels[j][i]);

			// pad lines to a 4-byte offset
			for (int j = 0; j != w % 4; ++j)
				out.write(0);
		}

		return size();
	}

	public int size() {
		return 54
			+ 4
				* (b.getPallette().size()
					+ ((b.getWidth() + 3) / 4) * b.getHeight());
	}

	/**
	 * Draws the supplied Bitmap to the supplied HttpServletResponse.
	 * <p>
	 * Note! Prevents further writing to the HttpServletResponse when done.
	 * </p>
	 */
	public static void drawBitmap(Bitmap bmp, HttpServletResponse resp)
		throws IOException {
		BitmapEncoder enc = new DibEncoder();
		enc.setBitmap(bmp);
		resp.setContentType(enc.getMimeType());
		OutputStream os = resp.getOutputStream();
		enc.encode(os);
		os.close();
	}

}