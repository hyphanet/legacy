package freenet.node.http;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.support.graph.Bitmap;
import freenet.support.graph.Color;
import freenet.support.graph.DibEncoder;

/**
 * Utility servlet. Renders a pixel (sort of, it actually is 2x2 to work around
 * with some Mozilla limitation re. 1x1 bmp:s) of the specified color. Useful
 * for displaying bars etc. in web pages (use width and height attributes in
 * the <IMG>tag to work around the fact that the pixel is 2x2 instead of 1x1)
 * <p>
 * The servlet accepts a single argument: <code>color=rrggbb</code>
 * </p>
 * 
 * @author Iakin
 */
public class ColoredPixelServlet extends HttpServlet {
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
		throws IOException {
		String sColor = req.getParameter("color");

		if (sColor == null) {
			resp.sendError(
				HttpServletResponse.SC_BAD_REQUEST,
				"Missing parameter 'color'");
			return;
		} else if (sColor.length() != 6) {
			resp.sendError(
				HttpServletResponse.SC_BAD_REQUEST,
				"Invalid color '" + sColor + "' specified");
			return;
		}

		int r, g, b;
		try {
			r = Integer.parseInt(sColor.substring(0, 2), 16);
			g = Integer.parseInt(sColor.substring(2, 4), 16);
			b = Integer.parseInt(sColor.substring(4, 6), 16);
		} catch (NumberFormatException nfe) {
			resp.sendError(
				HttpServletResponse.SC_BAD_REQUEST,
				"Color '" + sColor + "' contains a non-hexadecimal digit");
			return;
		}

		Bitmap bmp = new Bitmap(2, 2);
		bmp.clear(new Color(r, g, b));
		DibEncoder.drawBitmap(bmp, resp);
	}
}
