package freenet.support.servlet;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.SocketException;
import java.util.Locale;
import java.util.StringTokenizer;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;

/**
 * An implementation of ServletResponse.
 * 
 * @author oskar
 */
public class ServletResponseImpl implements ServletResponse {

	protected static final String DEFAULT_CHAR_ENC = "ISO-8859-1";

	protected final ServletRequestImpl request;

	protected String charEncoding;
	protected String contentType;
	protected Locale locale;
	protected int contentLength;

	// this is so nuts..
	protected final MagicBufferOutputStream bufferOutput;
	protected ServletOutputStream binaryOutput;
	protected PrintWriter writerOutput;

//	FIXME Too many MagicBuffers?
							   
	/**
	 * @param request the request we are replying to
	 * @param bufferSize
	 *            if >0, response content will be buffered before being
	 *            "committed"
	 */
	public ServletResponseImpl(ServletRequestImpl request, int bufferSize)
		throws IOException {
		this.request = request;
		OutputStream os = request.conn.getOut();
		if (os == null)
			throw new SocketException("no output stream");
		bufferOutput = new MagicBufferOutputStream(os, bufferSize);
		// reset();
		// no can do - this calls HttpServletResponseImpl's reset()
		// before the subclass has been fully constructed; bad news.
		// instead, make sure everything is initially set up in the
		// clean state already

		// here's how we usually handle this sort of thing
		priv_reset();
	}

	/**
	 * The servlet APIs insist you can only return one or the other.
	 */
	public ServletOutputStream getOutputStream() {
		if (writerOutput != null)
			throw new IllegalStateException("already got PrintWriter");
		else if (binaryOutput != null)
			return binaryOutput;
		else
			return binaryOutput = new ServletOutputStreamImpl(bufferOutput);
	}

	/**
	 * The servlet APIs insist you can only return one or the other.
	 */
	public PrintWriter getWriter() throws IOException {
		if (binaryOutput != null)
			throw new IllegalStateException("already got ServletOutputStream");
		else if (writerOutput != null)
			return writerOutput;
		else
			return writerOutput =
				new PrintWriter(
					new OutputStreamWriter(bufferOutput, charEncoding));
	}

	/**
	 * Sets the content length of the response. This won't really do anything
	 * unless the subclass decides to care about it.
	 */
	public void setContentLength(int len) {
		this.contentLength = len;
	}

	/**
	 * @return the character encoding set by setContentType() (!) or ISO-8859-1
	 *         by default
	 */
	public String getCharacterEncoding() {
		return charEncoding;
	}

	/**
	 * @return Content type, set by setContentType - does not include char
	 *         encoding
	 */
	public String getContentType() {
		return contentType;
	}

	/**
	 * Takes a string like "text/html; charset=ISO-8859-4" No doubt we are
	 * secretly expected to determine compression method from this also.. The
	 * actual content type part won't really do anything unless the subclass
	 * decides to care.
	 */
	public void setContentType(String type) {
		// 	freenet.Core.logger.log(this, "setContentType("+type+")",
		// 				freenet.Logger.DEBUG);
		StringTokenizer st = new StringTokenizer(type, ";");
		if (st.hasMoreTokens()) {
			contentType = st.nextToken().trim();
			// 	    freenet.Core.logger.log(this, "contentType: "+contentType,
			// 				    freenet.Logger.DEBUG);
			if (st.hasMoreTokens()) {
				String enc =
					freenet.support.servlet.http.HttpSupport.getAttribute(
						type,
						"charset");
				// 		freenet.Core.logger.log(this, "more tokens",
				// 					freenet.Logger.DEBUG);
				if (enc != null) {
					charEncoding = enc;
					// 		    freenet.Core.logger.log(this, "Set encoding to " +
					// charEncoding,
					// 					    freenet.Logger.DEBUG);
				}
			}
		} else
			contentType = "";
	}

	/**
	 * @throws IllegalStateException
	 *             if the servlet has written any data to the
	 *             ServletOutputStream or PrintWriter (regardless of whether
	 *             it's been committed yet)
	 */
	final public void setBufferSize(int size) throws IllegalStateException {
		if (isStarted()) {
			throw new IllegalStateException("response content has already been written");
		}
		bufferOutput.setBufferSize(size);
	}

	/**
	 * @return the buffer size currently being used
	 */
	final public int getBufferSize() {
		return bufferOutput.getBufferSize();
	}

	/**
	 * This causes the response body to be written and the response is
	 * considered committed. Subclasses should implement
	 * writeResponseHeaders(), which will be called just before the body data
	 * is sent.
	 */
	final public void flushBuffer() throws IOException {
		// Flush the intermediate streams to bufferOutput so it will
		// have full control over everything that was written.
		//
		// However, bufferOutput will not write to the underlying
		// OutputStream until it is good and ready.
		//
		if (binaryOutput != null)
			binaryOutput.flush();
		if (writerOutput != null)
			writerOutput.flush();

		// ok, we're ready now
		//bufferOutput.reallyFlush();

		// theo, we mustn't break ordinary use of flush(),
		// and anyway there is no point to the reallyFlush() thing
		bufferOutput.flush();
	}

	/**
	 * Do NOT write to any stream except the supplied one from this method.
	 */
	protected void writeResponseHeaders(OutputStream out) throws IOException {
	    // Subclasses will throw. Silence warning.
	    if(false) throw new IOException();
	}

	/**
	 * This is not in the ServletResponse API.
	 */
	final public boolean isStarted() {
		return bufferOutput.isStarted();
	}

	/**
	 * Returns whether the message is committed (which happens either when the
	 * servlet flushes its output, or the buffer fills up).
	 */
	final public boolean isCommitted() {
		return bufferOutput.isCommitted();
	}

	/**
	 * @throws IllegalStateException
	 *             if the response has already been committed (e.g., buffer
	 *             filled up)
	 */
	public void reset() throws IllegalStateException {
		if (isCommitted())
			throw new IllegalStateException("response already committed");
		priv_reset();
	}

	private void priv_reset() {
		bufferOutput.reset();
		charEncoding = DEFAULT_CHAR_ENC;
		contentType = "";
		locale = Locale.getDefault();
		contentLength = -1;
	}

	/**
	 * Set a Locale other than the system default.
	 */
	public void setLocale(Locale loc) {
		this.locale = loc;
	}

	/**
	 * @return the current Locale setting
	 */
	public Locale getLocale() {
		return locale;
	}

	/**
	 * Gar.
	 */
	protected class MagicBufferOutputStream extends FilterOutputStream {

		private int bufferSize;

		private byte[] buffer;
		private int pos;

		private boolean committed = false, closed = false;

		private MagicBufferOutputStream(OutputStream out, int bufferSize) {
			super(out);
			reset();
			setBufferSize(bufferSize);
		}

		public void write(int b) throws IOException {
			if (closed)
				throw new IOException("already closed!");
			makeBuffer();
			if (bufferSize > 0) {
				buffer[pos++] = (byte) b;
				if (pos == bufferSize)
					flush(); //reallyFlush();
			} else { // no buffering
				commit();
				out.write(b);
				out.flush();
			}
		}

		public void write(byte[] buf, int off, int len) throws IOException {
			if (closed)
				throw new IOException("already closed!");
			makeBuffer();
			if (bufferSize > 0) {
				if (pos + len > bufferSize) {
					flush(); //reallyFlush();
					out.write(buf, off, len);
				} else {
					System.arraycopy(buf, off, buffer, pos, len);
					pos += len;
					if (pos == bufferSize)
						flush(); //reallyFlush();
				}
			} else { // no buffering
				commit();
				out.write(buf, off, len);
				out.flush();
			}
		}

		/**
		 * This function does nothing. It is here in case you call flush() on a
		 * wrapper of this stream before we are ready to commit.
		 */

		// This is not good. If they flush() their stream that is
		// clearly equivalent to them calling flushBuffer().
		//
		// Also, this nicely breaks ordinary use of flush()
		// after the commit.

		//public final void flush() throws IOException {
		//}

		/**
		 * Flush underlying OutputStream for real (committing response if
		 * necessary).
		 */
		public void flush() throws IOException {
			if (closed)
				throw new java.net.SocketException("already closed!");
			try {
				commit();
				if (pos > 0) {
					out.write(buffer, 0, pos);
					pos = 0;
				}
				out.flush();
			} catch (IOException e) {
				if ((!(e instanceof SocketException))
					&& e.getMessage() != null
					&& e.getMessage().indexOf("pipe") != -1)
					throw new SocketException();
				else
					throw e;
			}
		}

		public void close() throws IOException {
			if (!closed) {
				try {
					flush(); //reallyFlush();
				} finally {
					closed = true;
					reset();
					out.close();
				}
			}
		}

		private final void commit() throws IOException {
			if (!committed) {
				committed = true;
				OutputStream bout = new BufferedOutputStream(out, bufferSize);
				writeResponseHeaders(bout);
				bout.flush();
			}
		}

		private final void makeBuffer() {
			if (pos == -1) {
				if (bufferSize > 0)
					buffer = new byte[bufferSize];
				pos = 0;
			}
		}

		private final boolean isStarted() {
			return closed || pos != -1;
		}

		private final boolean isCommitted() {
			return committed;
		}

		private final void reset() throws IllegalStateException {
			buffer = null;
			pos = -1;
		}

		private final int getBufferSize() {
			return bufferSize;
		}

		private final void setBufferSize(int bufferSize)
			throws IllegalStateException {
			this.bufferSize = bufferSize;
		}
	}
}
