package freenet.client.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Hashtable;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.servlet.HtmlTemplate;

/**
 * The ImageServlet serves up images stored in the freenet.jar file for other
 * servlets. e.g. NodeInfoServlet.
 * <p>
 * Images are loaded from the path set by
 * {@link freenet.support.servlet.HTMLTemplate#TEMPLATEDIR. HTMLTemplate.TEMPLATEDIR}
 * </p>
 */
public class ImageServlet extends HttpServlet {

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String path = req.getPathInfo();
		returnImage(resp, path);
		resp.flushBuffer();
	}

	/**
	 * @param resp
	 * @param resourceName
	 */
    public void returnImage(HttpServletResponse resp, String resourceName) throws IOException {

		// Determine and set content-type
		String lower = resourceName.toLowerCase();
		if (lower.endsWith(".png")) {
			resp.setContentType("image/png");
		} else if (lower.endsWith(".gif")) {
			resp.setContentType("image/gif");
		} else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			resp.setContentType("image/jpeg");
        } else if (lower.endsWith(".css")) {
            resp.setContentType("text/css");
		}

        InputStream imagenbIS = getClass().getResourceAsStream(HtmlTemplate.TEMPLATEDIR + "images/" + resourceName);
		if (imagenbIS == null) {
            Node.logger.log(this, "Can't find image " + resourceName, Logger.ERROR);
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
        resp.addDateHeader("Expires", System.currentTimeMillis() + 24 * 3600 * 1000);

		// Write the image stream to the response.
        ByteBuffer buffer = ByteBuffer.allocateDirect(Core.blockSize);
		ReadableByteChannel readChannel = Channels.newChannel(imagenbIS);
        WritableByteChannel writeChannel = Channels.newChannel(resp.getOutputStream());
		while (readChannel.read(buffer) != -1 && writeChannel.isOpen()) {
			buffer.flip();
			writeChannel.write(buffer);
			buffer.clear();
		}

		writeChannel.close();
		readChannel.close();
		imagenbIS.close();
	}

	private final static long loadTime = System.currentTimeMillis();

	protected long getLastModified(HttpServletRequest req) {
		return loadTime;
	}

    /**
     * cache image sizes here. it uses up some precious memory, but should
     * speed things up
     */
    private static final Hashtable imageSizes = new Hashtable();

    /**
     * gets the size of a template image
     * 
     * @param resourceName
     *            path to the image, relative to
     *            HtmlTemplate.TEMPLATEDIR/images/
     * @return size of the image, or null if the image doesn't exist or the
     *         image format is unsupported
     */
    public static Dimension getSize(String resourceName) {
        // if the image's size is already in the hashtable, get it from there
        if (imageSizes.containsKey(resourceName)) {
            return (Dimension) imageSizes.get(resourceName);
        // otherwise look it up
        } else {
            InputStream imageIS = ImageServlet.class.getResourceAsStream(HtmlTemplate.TEMPLATEDIR + "images/" + resourceName);
            if (imageIS == null) {
                Node.logger.log(ImageServlet.class, "Can't find image " + resourceName, Logger.ERROR);
                return null;
            }
            ImageInfo ii = new ImageInfo();
            ii.setInput(imageIS); // in can be InputStream or RandomAccessFile
            ii.setDetermineImageNumber(true); // default is false
            ii.setCollectComments(true); // default is false
            if (!ii.check()) {
                Core.logger.log(ImageServlet.class, "Unsupported image: " + resourceName, Logger.ERROR);
                return null;
            }
            Dimension size = new Dimension(ii.getWidth(), ii.getHeight());
            imageSizes.put(resourceName, size);
            return size;
        }
    }

    /**
     * oversimplified Dimension class. We don't use java.awt.Dimension because
     * we don't want to create artificial dependencies that require users to
     * install a GUI to use Freenet
     */
    public static class Dimension {

        int height, width;

        public Dimension(int width, int height) {
            setWidth(width);
            setHeight(height);
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }

    }

}
