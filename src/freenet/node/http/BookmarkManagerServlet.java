package freenet.node.http;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.Core;
import freenet.config.Option;
import freenet.config.Params;
import freenet.crypt.RandomSource;
import freenet.node.ConfigUpdateListener;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.NodeConfigUpdater;
import freenet.support.Logger;
import freenet.support.servlet.HtmlTemplate;

/**
 * A servlet that lets people add, update, and remove bookmarks from their 
 * fproxy mainpage (as controlled by their config file) Node operators can use
 * this to, well, manage bookmarks. Freesite authors can create a link to this
 * to add a bookmark.
 * <p>
 * Format: &lt;a
 * href=&quot;/servlet/bookmarkmanager?op=add&amp;key=SSK@...//&amp;title=MySiteName&amp;description=The+site+descr&amp;activelinkFile=activelink.png&amp;back=SSK@...//&quot;&gt;
 * bookmark me&lt;/a&gt; op = add key = the freenet key (CHK@..., KSK@...,
 * SSK@..., etc) title = the title description = the description activelinkFile =
 * the file underneath the key (only relevent for KSK and SSK) back = link that
 * people will be able to click on to go back to the page All modifications are
 * disabled for public nodes. Remove and update ops only work if the link
 * specifies the old value (making it hard for malicious freesite authors to
 * say op=remove&amp;num=0 (as they also need to know the freenet key that is at
 * index 0)
 * </p>
 */
public class BookmarkManagerServlet extends HttpServlet implements ConfigUpdateListener {

	private final static String NL = System.getProperty("line.separator");
	private boolean allowUpdatingBookmarks;
	private String thisPath;
	private ArrayList bookmarks;
	private HtmlTemplate pageTemplate;
	/** bookmark submitted via add but not yet confirmed */
	private Bookmark pendingNewBookmark;
	/**
	 * random challenge the user must confirm with to get the pending bookmark
	 * added
	 */
	private long pendingNewBookmarkSecret;
	/** random source used to calculate said random challenge */
	private RandomSource randSource;
	/**
	 * random challenge built into the html form so that only update and remove
	 * requests
	 */
	private long lastRenderSecret;
	private boolean logDEBUG;

	/*
	 * All supported params
	 */
	public final static String PARAM_OP = "op";
	public final static String PARAM_BACK = "back";
	public final static String PARAM_NUM = "num";
	public final static String PARAM_KEY = "key";
	public final static String PARAM_OLDKEY = "oldKey";
	public final static String PARAM_TITLE = "title";
	public final static String PARAM_DESCRIPTION = "description";
	public final static String PARAM_ACTIVELINKFILE = "activelinkFile";
	public final static String PARAM_CONFIRMID = "confirmid";
	public final static String PARAM_CHALLENGE = "challenge";
	
	/*
	 * All supported ops
	 */
	public final static String PARAM_OP_ADD = "add";
	public final static String PARAM_OP_UPDATE = "update";
	public final static String PARAM_OP_REMOVE = "remove";
	public final static String PARAM_OP_CONFIRM = "confirm";
	public final static String PARAM_OP_MOVEUP = "move up";
	public final static String PARAM_OP_MOVEDOWN = "move down";
	
	/*
	 * These are used to render the HTML form.  yuck, I know.  so, when do we
	 * integrate jsps?
	 */
	private final static int SIZE_KEY = 60;
	private final static int SIZE_TITLE = 25;
	private final static int SIZE_ACTIVELINK = 15;
	private final static int SIZE_DESCRIPTION_WIDTH = 60;
	private final static int SIZE_DESCRIPTION_HEIGHT = 2;
	
	/**
	 * hardcoded path to the node info servlet config options for the
	 * bookmarks. what, the bookmarks should be under their own tree? or
	 * perhaps in their own file?
	 */
	private final static String BOOKMARK_PATH = "mainport.params.servlet.2.bookmarks";

	public final void init() {
		randSource = (RandomSource) getServletContext().getAttribute("freenet.crypt.RandomSource");
		bookmarks = loadDefaultBookmarks();
		thisPath = "/servlet/bookmarkmanager";
		pendingNewBookmark = null;
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		try {
			pageTemplate = HtmlTemplate.createTemplate("BookmarkManagerServletTmpl.html");
		} catch (IOException ioe) {
			Core.logger.log(this, "Error creating template", ioe, Logger.ERROR);
		}
		NodeConfigUpdater.addUpdateListener(BOOKMARK_PATH, this);
		allowUpdatingBookmarks = !Main.publicNode;
	}
	
	/**
	 * Read through the Node.config and create Bookmark objects based only on
	 * that (does not read the config file)
	 */
	private ArrayList loadDefaultBookmarks() {
		ArrayList bmks = new ArrayList();
		try {
			Params params = new Params(Node.getConfig().getOptions());
		
			if (params != null) {
				Option countOpt = params.getOption(BOOKMARK_PATH+".count");
				int maxBookmarks = -1;
				if (countOpt != null) 
					try {
						maxBookmarks = Integer.parseInt("" + countOpt.defaultValue());
					} catch (NumberFormatException nfe) {
						maxBookmarks = -1;
					}
				int i = 0;
				while (true) {
					if ((maxBookmarks >= 0) && (i >= maxBookmarks))
						break;
					
					Option keyOpt =	params.getOption(BOOKMARK_PATH + "." + i + ".key");
					if (keyOpt == null)
						break;
					
					Option titleOpt = params.getOption(BOOKMARK_PATH + "." + i + ".title");
					Option activelinkOpt = params.getOption(BOOKMARK_PATH + "." + i + ".activelinkFile");
					Option descOpt = params.getOption(BOOKMARK_PATH + "." + i + ".description");
				
					String key = (String) keyOpt.defaultValue();
					String title = (String) titleOpt.defaultValue();
					String al = (String) activelinkOpt.defaultValue();
					String desc = (String) descOpt.defaultValue();
			
					if(logDEBUG)
						Core.logger.log(BookmarkManagerServlet.class, "Load a new bookmark [" + key + "]", Logger.DEBUG);
					bmks.add(new Bookmark(key, title, al, desc));
					i++;
				}
			}
			if(logDEBUG)
				Core.logger.log(BookmarkManagerServlet.class, "Bookmarks found [" + bmks.size() + "] fieldset: " + params, Logger.DEBUG);
		} catch (Exception e) {
			Core.logger.log(this, "Error loading default bookmarks: " + e.getMessage(), e, Logger.ERROR);
		}
		return bmks;
	}

	private long createSecret() {
		return randSource.nextLong();
	}
	
	/**
	 * Put the bookmark in the confirm slot for further confirmation.
	 *
	 * @return success/fail message 
	 */
	private String addBookmark(String key, String title, String description, String activelinkFile) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if (!allowUpdatingBookmarks) {
			return "Bookmarks cannot be updated on public nodes";
		} 
		if(!safeKey(key))
			return "Unsafe key";
		if(!safeTitle(title))
			return "Unsafe title";
		if(!safeDesc(description))
			return "Unsafe description";
		if(!safeLink(activelinkFile))
			return "Unsafe activelink file";
		pendingNewBookmark = new Bookmark(key, title, activelinkFile, description);
		pendingNewBookmarkSecret = createSecret();
		
		return "Please confirm adding the bookmark";
	}
	/**
	 * Actually add a new bookmark if the confirm matches
	 * 
	 * @return success/fail message 
	 */
	private String confirmAddBookmark(long secret) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if (!allowUpdatingBookmarks) {
			return "Bookmarks cannot be updated on public nodes";
		} 
		
		if (secret == pendingNewBookmarkSecret) {
			bookmarks.add(pendingNewBookmark);
			pendingNewBookmark = null;
			pendingNewBookmarkSecret = 0;
			saveChanges();
			return "Bookmark added";
		} else {
			return "Incorrect confirmation code.  Bookmarks unchanged";
		}
	}
	
	/**
	 * Actually remove the bookmark at the index specified (only if the key is
	 * the same)
	 * 
	 * @return success/fail message
	 */
	private String removeBookmark(int num, String key) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if (!allowUpdatingBookmarks) {
			return "Bookmarks cannot be updated on public nodes";
		} 
		
		if ((num < 0) || (num >= bookmarks.size())) {
			return "Invalid bookmark index specified";
		}
		
		Bookmark bmk = (Bookmark) bookmarks.get(num);
		if ((bmk == null) || (bmk.getKey() == null)) {
			Core.logger.log(this, "on remove: bookmarks.get(" + num	+ ") didn't have a valid key or was null.  bookmarks = " + bookmarks, Logger.ERROR);
			return "Internal error removing the bookmark.  No bookmarks have been changed";
		}
		if (!bmk.getKey().equals(key)) {
			return "Incorrect key specified to remove... malicious link?";
		}
	   
		Object removed = bookmarks.remove(num);
		
		saveChanges();
		return "Bookmark removed";
	}

	/**
	 * Actually update the bookmark at the index specified, only if the key
	 * matches
	 *
	 * @return success/fail message
	 */
	private String updateBookmark(int num, String key, String newKey, String newTitle, String newDesc, String newActivelinkFile) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (!allowUpdatingBookmarks) {
			return "Bookmarks cannot be updated on public nodes";
		}
		
		if ((num < 0) || (num >= bookmarks.size())) {
			return "Invalid bookmark index specified";
		}
		
		if (!safeKey(newKey))
			return "Unsafe key";
		if (!safeTitle(newTitle))
			return "Unsafe title";
		if (!safeDesc(newDesc))
			return "Unsafe description";
		if (!safeLink(newActivelinkFile))
			return "Unsafe activelink file";
		Bookmark bmk = (Bookmark) bookmarks.get(num);
		if ((bmk == null) || (bmk.getKey() == null)) {
			Core.logger.log(this, "on update, bookmarks.get(" + num + ") didn't have a valid key or was null.  bookmarks = " + bookmarks, Logger.ERROR);
			return "Internal error updating the bookmark.  No bookmarks have been changed";
		}
		if (!bmk.getKey().equals(key)) {
			return "Incorrect key specified to update... malicious link?";
		}
	   
		bmk.setKey(newKey);
		bmk.setTitle(newTitle);
		bmk.setActivelinkFile(newActivelinkFile);
		bmk.setDescription(newDesc);
		
		saveChanges();
		return "Bookmark updated";
	}

	/**
	 * Actually move the bookmark to the index specified, only if the key matches.
	 *
	 * @return success/failure message
	 */
	private String moveBookmark(int from, int to, String key) {
		if (!allowUpdatingBookmarks)
			return "Bookmarks cannot be moved on public nodes.";
		if ((from == to) || (from < 0) || (to < 0) || (from >= bookmarks.size()) || (to >= bookmarks.size()))
			return "Invalid bookmark indices specified.";

		Bookmark fromB = (Bookmark) bookmarks.get(from);

		if ((fromB == null) || (fromB.getKey() == null)) {
			Core.logger.log(this, "on update, bookmarks.get(" + from + ") didn't have a valid key or was null.  bookmarks = " + bookmarks, Logger.ERROR);
			return "Internal error updating the bookmark.  No bookmarks have been changed";
		}
		if (!fromB.getKey().equals(key)) {
			return "Incorrect key specified to move... malicious link?";
		}

		Bookmark toB = (Bookmark) bookmarks.get(to);
		bookmarks.set(to, fromB);
		bookmarks.set(from, toB);

		saveChanges();
		return "Bookmark moved.";
	}
	
	/**
	 * Write the bookmarks to the first existing config file
	 */
	private void saveChanges() {
		synchronized(Main.getConfigUpdater().syncOb()) {
			// write the config file, and then notify the NodeConfigUpdater
			Core.logger.log(this, "Bookmarks being saved", Logger.NORMAL);
		
			try {
				File f = Main.paramFile;
				if (f.exists()) {
					boolean success = saveChanges(f);
					if(logDEBUG)
						Core.logger.log(this,"Saving to [" + f + "]: " + success, Logger.DEBUG);
				}
				Main.getConfigUpdater().checkpoint();
			} catch (Exception e) {
				Core.logger.log(this, "Error saving changes: " + e.getMessage(), e, Logger.ERROR);
			}
		}
	}
	
	/**
	 * Actually write out the config file, leaving the previous version of the
	 * bookmarks commented out, but with the new ones at the end of the file
	 */
	private boolean saveChanges(File f) {
		synchronized(Main.getConfigUpdater().syncOb()) {
			StringBuffer updated = new StringBuffer(65536); // at a minimum!
		
			try {
				BufferedReader reader = new BufferedReader(new FileReader(f));
				String line = null;
				int oldLines = 0;
				int updatedLines = 0;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("%"+BOOKMARK_PATH)) {
						// skip it
					} else if (!line.startsWith(BOOKMARK_PATH)) {
						updated.append(line).append(NL);
						oldLines++;
					} else {
						updated.append("%").append(line).append(NL);
						updatedLines++;
					}
				}
				reader.close();
		
				if(logDEBUG)
					Core.logger.log(this, "Saving to " + f.getName() + " left " + oldLines + " and commented out " + updatedLines, Logger.DEBUG);
		
		// now append the updated ones
				updated
					.append(BOOKMARK_PATH)
					.append(".count=")
					.append(bookmarks.size())
					.append(NL);
				for (int i = 0; i < bookmarks.size(); i++) {
					Bookmark bmk = (Bookmark)bookmarks.get(i);
					updated
						.append(BOOKMARK_PATH)
						.append(".")
						.append(i)
						.append(".");
					if (!safeKey(bmk.getKey()))
						throw new IllegalStateException("invalid key writing out");
					updated
						.append("key=")
						.append(bmk.getKey())
						.append(NL);
			
					updated
						.append(BOOKMARK_PATH)
						.append(".")
						.append(i)
						.append(".");
					if (!safeTitle(bmk.getTitle()))
						throw new IllegalStateException("invalid title writing out");
					updated
						.append("title=")
						.append(bmk.getTitle())
						.append(NL);
			
					updated
						.append(BOOKMARK_PATH)
						.append(".")
						.append(i)
						.append(".");
					if (!safeLink(bmk.getActivelinkFile()))
						throw new IllegalStateException("invalid activelink writing out");
					updated
						.append("activelinkFile=")
						.append(bmk.getActivelinkFile())
						.append(NL);

					updated
						.append(BOOKMARK_PATH)
						.append(".")
						.append(i)
						.append(".");
					if (!safeDesc(bmk.getDescription()))
						throw new IllegalStateException("invalid description writing out");
					updated
						.append("description=")
						.append(bmk.getDescription())
						.append(NL);
				}
		
				FileOutputStream fos = new FileOutputStream(f);
				fos.write(updated.toString().getBytes());
				fos.close();
				return true;
			} catch (IOException ioe) {
				Core.logger.log(this, "Saving to " + f.getName() + " failed: " + ioe.getMessage(), ioe, Logger.NORMAL);
				return false;
			}
		}
	}
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp)
		throws IOException {
		processRequest(req, resp);
	}

	/**
	 * Distribute the request to addBookmark/updateBookmark/removeBookmark, and
	 * then render the bookmarks page Synchronized, as this may write to files
	 * and updates a class level var (bookmarks)
	 */
	private synchronized void processRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		StringBuffer resultMessage = new StringBuffer();
	
		String op = req.getParameter(PARAM_OP);
		
		if(logDEBUG)
			Core.logger.log(this, "Processing a request w/ op: " + op, Logger.DEBUG);
		
		if (PARAM_OP_ADD.equalsIgnoreCase(op)) {
			String key = req.getParameter(PARAM_KEY);
			String title = req.getParameter(PARAM_TITLE);
			String desc = req.getParameter(PARAM_DESCRIPTION);
			String alFile = req.getParameter(PARAM_ACTIVELINKFILE);
			resultMessage.append(addBookmark(key, title, desc, alFile));
		} else if (PARAM_OP_CONFIRM.equalsIgnoreCase(op)) {
			String code = req.getParameter(PARAM_CONFIRMID);
			try {
				long id = Long.parseLong(code);
				resultMessage.append(confirmAddBookmark(id));
			} catch (Exception e) {
				resultMessage.append("Invalid confirm code specified");
			}
			pendingNewBookmark = null;
		} else if (PARAM_OP_UPDATE.equalsIgnoreCase(op)) {
			int num = 0;
			try {
				num = Integer.parseInt(req.getParameter(PARAM_NUM));
				if (num < 0)
					throw new Exception();
				
				if (!verifyChallenge(req)) {
					resultMessage.append("Invalid challenge - malicious link?");
				} else {
					String key = req.getParameter(PARAM_OLDKEY);
					String newKey = req.getParameter(PARAM_KEY);
					String newTitle = req.getParameter(PARAM_TITLE);
					String newDesc = req.getParameter(PARAM_DESCRIPTION);
					String newAlFile = req.getParameter(PARAM_ACTIVELINKFILE);
					resultMessage.append(updateBookmark(num, key, newKey, newTitle, newDesc, newAlFile));
				}
			} catch (Exception e) {
				Core.logger.log(this, "Error updating: " + e.getMessage(), e, Logger.NORMAL);
				resultMessage
					.append("Invalid update index (")
					.append(req.getParameter(PARAM_NUM))
					.append(")");
			}
			pendingNewBookmark = null;
		} else if (PARAM_OP_REMOVE.equalsIgnoreCase(op)) {
			int num = 0;
			try {
				num = Integer.parseInt(req.getParameter(PARAM_NUM));
				if (num < 0)
					throw new Exception();
				String key = req.getParameter(PARAM_KEY);
				
				if (!verifyChallenge(req)) {
					resultMessage.append("Invalid challenge - malicious link?");
				} else {
					resultMessage.append(removeBookmark(num, key));
				}
			} catch (Exception e) {
				Core.logger.log(this, "Error removing: " + e.getMessage(), e, Logger.NORMAL);
				resultMessage
					.append("Invalid remove index (")
					.append(req.getParameter(PARAM_NUM))
					.append(")");
			}
			pendingNewBookmark = null;
		} else if (PARAM_OP_MOVEUP.equalsIgnoreCase(op)) {
			try {
				int num = Integer.parseInt(req.getParameter(PARAM_NUM));
				String key = req.getParameter(PARAM_KEY);
				resultMessage.append(moveBookmark(num, num - 1, key));
			} catch (Exception e) {
				Core.logger.log(this, "Error moving: " + e.getMessage(), e, Logger.NORMAL);
				resultMessage
					.append("Invalid remove index (")
					.append(req.getParameter(PARAM_NUM));
			}
		} else if (PARAM_OP_MOVEDOWN.equalsIgnoreCase(op)) {
			try {
				int num = Integer.parseInt(req.getParameter(PARAM_NUM));
				String key = req.getParameter(PARAM_KEY);
				resultMessage.append(moveBookmark(num, num + 1, key));
			} catch (Exception e) {
				Core.logger.log(this, "Error moving: " + e.getMessage(), e, Logger.NORMAL);
				resultMessage
					.append("Invalid remove index (")
					.append(req.getParameter(PARAM_NUM));
			}
		} else if (op != null) {
			resultMessage
				.append("Invalid operation requested: ")
				.append(op)
				.append("<br />")
				.append("Valid options: ")
				.append(PARAM_OP_ADD).append(" ")
				.append(PARAM_OP_UPDATE).append(" ")
				.append(PARAM_OP_REMOVE).append(" ")
				.append(PARAM_OP_MOVEUP).append(" ")
				.append(PARAM_OP_MOVEDOWN).append(" ");
			pendingNewBookmark = null;
		} else {
			// no op, just display
			pendingNewBookmark = null;
		}
	  
		String backLink = req.getParameter(PARAM_BACK);
		
		PrintWriter pw = resp.getWriter();
		resp.setContentType("text/html");

		try {
			String body = renderBookmarkForm();
			pageTemplate.set("MESSAGE", resultMessage.toString());
			pageTemplate.set("BACK", renderBackLink(backLink));
			pageTemplate.set("CONFIRMHTML", renderConfirmHTML());
			pageTemplate.set("BODY", body);
			pageTemplate.toHtml(pw);
		} catch (Throwable t) {
			t.printStackTrace();
			Core.logger.log(this, "Error rendering the form: " + t.getMessage(), t, Logger.ERROR);
		}
		try {
			pw.flush();
			resp.flushBuffer();
		} catch (IOException ioe) {
			Core.logger.log(this, "I/O error writing the bookmark manager buffer... probably harmless", ioe, Logger.MINOR);
		}
	}
	
	private boolean verifyChallenge(HttpServletRequest req) {
		String offered = req.getParameter(PARAM_CHALLENGE);
		try {
			long challenge = Long.parseLong(offered);
			if (challenge == lastRenderSecret)
				return true;
		} catch (Exception e) {
		}
		return false;
	}
	
	private String renderBackLink(String link) {
		if (link != null)
			return "<a href=\"/"+ link + "\">Back</a><p />";
		else
			return "";
	}
	
	private String renderConfirmHTML() {
		if (pendingNewBookmark != null) {
			StringBuffer confirmHtml = new StringBuffer();
			confirmHtml
				.append("<b>Please ")
				.append("<a href=\"/servlet/bookmarkmanager?")
				.append(PARAM_OP)
				.append('=')
				.append(PARAM_OP_CONFIRM)
				.append("&amp;")
				.append(PARAM_CONFIRMID)
				.append('=')
				.append(pendingNewBookmarkSecret)
				.append("\">confirm</a> that you want to add the following bookmark:<p />")
				.append(NL)
				.append(renderLink(pendingNewBookmark))
				.append("<p />");
			return confirmHtml.toString();
		} else {
			return "&nbsp;";
		}
	}
	
	/**
	 * Render the html table for the bookmarks, including the forms to update 
	 * and add new ones
	 */
	private String renderBookmarkForm() {
		StringBuffer buf = new StringBuffer(512);
		buf.append("<table>").append(NL);
		
		lastRenderSecret = randSource.nextLong();
		
		if (bookmarks != null) {
			for (int i = 0; i < bookmarks.size(); i++) {
				Bookmark bookmark = (Bookmark) bookmarks.get(i);
				String key = bookmark.getKey();
				String title = bookmark.getTitle();
				String activelinkFile = bookmark.getActivelinkFile();
				String description = bookmark.getDescription();
			
				String activelink = null;
				if (activelinkFile != null) { 
					if (key.endsWith("/"))
						activelink = key + activelinkFile;
					else if (key.indexOf('/') > 0) 
						activelink = key.substring(0, key.lastIndexOf('/') + 1) + activelinkFile;
				}
		
				if(logDEBUG)
					Core.logger.log(this, "Found full bookmark [" + i + "]: " + key	+ "/" + title + "/" + activelink + "/" + description, Logger.DEBUG);
			
				buf
					.append("<tr><td colspan=\"2\">")
					.append(NL)
					.append(renderLink(bookmark))
					.append("</td></tr>")
					.append(NL)
					.append("<form action=\"")
					.append(thisPath)
					.append("\" method=\"GET\">")
					.append("<input type=\"hidden\" name=\"")
					.append(PARAM_NUM)
					.append("\" value=\"")
					.append(i)
					.append("\" />")
					.append(NL)
					.append("<input type=\"hidden\" name=\"")
					.append(PARAM_OLDKEY)
					.append("\" value=\"")
					.append(key)
					.append("\" />")
					.append(NL)
					.append("<input type=\"hidden\" name=\"")
					.append(PARAM_CHALLENGE)
					.append("\" value=\"")
					.append(lastRenderSecret)
					.append("\" />")
					.append(NL)
					.append("<tr><td valign=\"top\">")
					.append("Key</td><td valign=\"top\"><input type=\"text\" size=\"")
					.append(SIZE_KEY)
					.append("\" name=\"")
					.append(PARAM_KEY)
					.append("\" value=\"")
					.append(key)
					.append("\" />")
					.append("</td></tr>")
					.append(NL)
					.append("<tr><td valign=\"top\">Title</td><td valign=\"top\"><input type=\"text\" size=\"")
					.append(SIZE_TITLE)
					.append("\" name=\"")
					.append(PARAM_TITLE)
					.append("\" value=\"")
					.append(title)
					.append("\" /></td></tr>")
					.append(NL)
					.append("<tr><td valign=\"top\">ActiveLink file</td><td valign=\"top\"><input type=\"text\" size=\"")
					.append(SIZE_ACTIVELINK)
					.append("\" name=\"")
					.append(PARAM_ACTIVELINKFILE)
					.append("\" value=\"")
					.append(activelinkFile)
					.append("\" /></td></tr>")
					.append(NL)
					.append("<tr><td valign=\"top\">Description</td><td valign=\"top\"><textarea rows=\"")
					.append(SIZE_DESCRIPTION_HEIGHT)
					.append("\" cols=\"")
					.append(SIZE_DESCRIPTION_WIDTH)
					.append("\" name=\"")
					.append(PARAM_DESCRIPTION)
					.append("\">")
					.append(description)
					.append("</textarea></td></tr>")
					.append(NL)
					.append("<tr><td colspan=\"2\" valign=\"top\"><input type=\"submit\" name=\"")
					.append(PARAM_OP)
					.append("\" value=\"");
				if (i != 0) {
					buf
						.append(PARAM_OP_MOVEUP)
						.append("\" />")
						.append(NL)
						.append("<input type=\"submit\" name=\"")
						.append(PARAM_OP)
						.append("\" value=\"");
				}
				if (i != bookmarks.size() - 1) {
					buf
						.append(PARAM_OP_MOVEDOWN)
						.append("\" />")
						.append(NL)
						.append("<input type=\"submit\" name=\"")
						.append(PARAM_OP)
						.append("\" value=\"");
				}
				buf
					.append(PARAM_OP_UPDATE)
					.append("\" />")
					.append(NL)
					.append("<input type=\"submit\" name=\"")
					.append(PARAM_OP)
					.append("\" value=\"")
					.append(PARAM_OP_REMOVE)
					.append("\" />")
					.append(NL)
					.append("</td></tr>")
					.append(NL)
					.append("</form>")
					.append(NL);
			} // end looping over all bookmarks
		} // end bookmarks != null 
		 
		// now the add bookmark form
		buf
			.append("<tr><td colspan=\"3\"><b>New bookmark</b></td></tr>")
			.append(NL)
			.append("<form action=\"")
			.append(thisPath)
			.append("\" method=\"GET\">")
			.append("<tr><td valign=\"top\">Key</td><td valign=\"top\"><input type=\"text\" size=\"")
			.append(SIZE_KEY)
			.append("\" name=\"")
			.append(PARAM_KEY)
			.append("\" value=\"\" /></td></tr>")
			.append(NL)
			.append("<tr><td valign=\"top\">Title</td><td valign=\"top\"><input type=\"text\" size=\"")
			.append(SIZE_TITLE)
			.append("\" name=\"")
			.append(PARAM_TITLE)
			.append("\" value=\"\" /></td></tr>")
			.append(NL)
			.append("<tr><td valign=\"top\">ActiveLink file</td><td valign=\"top\"><input type=\"text\" size=\"")
			.append(SIZE_ACTIVELINK)
			.append("\" name=\"")
			.append(PARAM_ACTIVELINKFILE)
			.append("\" value=\"\" /></td></tr>")
			.append(NL)
			.append("<tr><td valign=\"top\">Description</td><td valign=\"top\"><textarea rows=\"")
			.append(SIZE_DESCRIPTION_HEIGHT)
			.append("\" cols=\"")
			.append(SIZE_DESCRIPTION_WIDTH)
			.append("\" name=\"")
			.append(PARAM_DESCRIPTION)
			.append("\"></textarea></td></tr>")
			.append(NL)
			.append("<tr><td colspan=\"2\"><input type=\"submit\" name=\"")
			.append(PARAM_OP)
			.append("\" value=\"")
			.append(PARAM_OP_ADD)
			.append("\" />")
			.append(NL)
			.append("</td></tr></form>")
			.append(NL)
			.append("</tr>")
			.append(NL)
			.append("</table>")
			.append(NL);
		return buf.toString();
	}
  
	/**
	 * Render an img src + href + title + description in html
	 */
	private String renderLink(Bookmark bookmark) {
		if (bookmark == null)
			return "";
		String key = bookmark.getKey();
		String title = bookmark.getTitle();
		String activelinkFile = bookmark.getActivelinkFile();
		String description = bookmark.getDescription();
			
		String activelink = null;
		if (activelinkFile != null) { 
			if (key.endsWith("/"))
				activelink = key + activelinkFile;
			else if (key.indexOf('/') > 0) 
				activelink = key.substring(0, key.lastIndexOf('/') + 1) + activelinkFile;
		}
		
		StringBuffer buf = new StringBuffer();
		buf
			.append("<a href=\"/")
			.append(key)
			.append("\">");
		if (activelink != null)
			buf
				.append("<img src=\"/")
				.append(activelink)
				.append("\" alt=\"")
				.append(title)
				.append("\" width=\"95\" height=\"32\" /> ");
		else
			buf.append("(active link missing)");
		buf
			.append(title)
			.append("</a>");
		if (description != null) 
			buf
				.append(" - ")
				.append(description);
		buf.append(NL);
		return buf.toString();
	}
	
	/**
	 *  Gets the servletInfo attribute of the NodeInfoServlet object
	 *
	 * @return    The servletInfo value
	 */
	public String getServletInfo() {
		return "Bookmark manager for fproxy";
	}

	/**
	 *  Gets the servletName attribute of the NodeInfoServlet object
	 *
	 * @return    The servletName value
	 */
	public String getServletName() {
		return "Bookmark Manager";
	}

	/**
	 * Notify the listener that the property specified by path has been changed
	 * to the value given.
	 *
	 * @param path
	 *            defines what configuration property was updated (allows a.b.c
	 *            notation)
	 * @param val
	 *            value the configuration system has for the property
	 */
	public void configPropertyUpdated(String path, String val) {
		// ignored
	}  

	/**
	 * Notify the listener that the property specified by path has been changed
	 * to the field set given.
	 *
	 * @param path
	 *            defines what configuration property was updated (allows a.b.c
	 *            notation)
	 * @param fs
	 *            value the configuration system has for the field set (if the
	 *            path is a.b.c and the configuration system has properties
	 *             a.b.c.d and a.b.c.e, fs will contain d and e)
	 */
	public void configPropertyUpdated(String path, Params fs) {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		if(logDEBUG)
			Core.logger.log(this, "configPropertyUpdated called w/ path [" + path + "]", Logger.DEBUG);
		
		int count = -1;
		
		// load the bookmarks from the Node.config values
		ArrayList newBookmarks = loadDefaultBookmarks();
		// now go through the config file to override the Node.config
		if (fs != null) {
			Params params = fs;
			
			Option countOpt = params.getOption(BOOKMARK_PATH+".count");
			Object defaultCount = null;
			if (countOpt != null)
				defaultCount = countOpt.defaultValue();
			try {
				int specCount = params.getInt("count");
				if(logDEBUG)
					Core.logger.log(this, "Bookmarks.specCount = " + specCount, Logger.DEBUG);
				count = specCount;
			} catch (Exception e) {
				try {
					count = Integer.parseInt(""+defaultCount);
				} catch (NumberFormatException nfe) {
					count = -1;
				}
			}
			
			if(logDEBUG)
				Core.logger.log(this, "Bookmarks.count = " + count + "countOpt = [" + countOpt + "]", Logger.DEBUG);
			
			int i = 0;
			while ((i < count) || (count < 0)) {

				fs = (Params) params.getSet(i + "");
				if (fs == null) {
					if (logDEBUG)
						Core.logger.log(this, "bookmarks.getSet(" + i + ") returned null", Logger.DEBUG);
					break;
				}
					
				Option keyOpt = params.getOption(BOOKMARK_PATH + "." + i + ".key");
				Option titleOpt = params.getOption(BOOKMARK_PATH + "." + i + ".title");
				Option activelinkOpt = params.getOption(BOOKMARK_PATH + "." + i + ".activelinkFile");
				Option descOpt = params.getOption(BOOKMARK_PATH + "." + i + ".description");

				String k = fs.getString("key");
				String t = fs.getString("title");
				String al = fs.getString("activelinkFile");
				String desc = fs.getString("description");
					
				if(logDEBUG)
					Core.logger.log(this, "bookmarks.isSet(" + i + "/" + count + ") is true: k = " + k + " keyOpt = " + keyOpt, Logger.DEBUG);
				if ((k == null) && (t == null) && (al == null) && (desc == null)) {
						i++;
						continue;
					}
					
				if (i >= newBookmarks.size()) {
					newBookmarks.add(new Bookmark(k, t, al, desc));
					if(logDEBUG)
						Core.logger.log(this, "Update had a new bookmark [" + k + "]", Logger.DEBUG);
				} else {
					Bookmark bmk = (Bookmark) newBookmarks.get(i);
					if (logDEBUG)
						Core.logger.log(this, "Update changed a bookmark [" + bmk.getKey() + "] into ["	+ k + "]...", Logger.DEBUG);
					if (k != null)
						bmk.setKey(k);
					if (t != null)
						bmk.setTitle(t);
					bmk.setActivelinkFile(al);
					if (desc != null)
						bmk.setDescription(desc);
				}
				i++;
			} // end looping over bookmarks
		} else {
			// (bookmarks NOT specified in update)
			if(logDEBUG)
				Core.logger.log(this, "bookmarks NOT specified in update", Logger.DEBUG);
		}
		
		if (count >= 0)
			while (count < newBookmarks.size())
				newBookmarks.remove(count);
		
		bookmarks = newBookmarks;
		if(logDEBUG)
			Core.logger.log(this, "Bookmarks now contains " + bookmarks.size() + "/" + count + " bookmarks", Logger.DEBUG);
		Core.logger.log(this, "Bookmarks updated on request",Logger.NORMAL);
	}
	
	// \n's would be DISASTROUS, allowing insertion of arbitrary data into
	// config file
	// :'s would also be disastrous, allowing linking to external sites
	// <'s would allow insertion of arbitrary HTML
	
	protected boolean safeKey(String s) {
		return (s.indexOf('\n') < 0) && (s.indexOf('\r') < 0) && (s.indexOf(':') < 0) && (s.indexOf('<') < 0);
	}
	
	protected boolean safeTitle(String s) {
		return (s.indexOf('\n') < 0) && (s.indexOf('\r') < 0) && (s.indexOf(':') < 0) && (s.indexOf('<') < 0);
	}
	
	protected boolean safeDesc(String s) {
		return (s.indexOf('\n') < 0) && (s.indexOf('\r') < 0) && (s.indexOf(':') < 0) && (s.indexOf('<') < 0);
	}
	
	protected boolean safeLink(String s) {
		return (s == null) || ((s.indexOf('\n') < 0) && (s.indexOf('\r') < 0) && (s.indexOf(':') < 0) && (s.indexOf('<') < 0));
	}

	/**
	 * Defines a bookmark
	 */
	public class Bookmark {

		private String key;
		private String title;
		private String activelink;
		private String description;

		public Bookmark(String ky, String ttle, String alFile, String desc) { 
			setKey(ky);
			setTitle(ttle);
			setActivelinkFile(alFile);
			setDescription(desc);
		}

		public String getKey() {
			return key;
		}

		// Empty strings in the configuration file are replaced with hard coded defaults. This appears to be the desired behavior. Therefore we must replace
		// empty strings with something meaningful

		public void setKey(String key) {
			this.key = clean(key);
			if (this.key.equals(""))
				this.key = "key missing";
		}

		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = clean(title);
			if (this.title.equals(""))
				this.title = "no title";
		}

		/**
		 * Obtain path to active link picture relative to site.
		 * When this method returns null, no link should be displayed.
		 */

		public String getActivelinkFile() {
			return activelink;
		}

		public void setActivelinkFile(String file) {
			activelink = clean(file);
			if (activelink.equals(""))
				activelink = null;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String desc) { 
			description = clean(desc); 
			if (description.equals(""))
				description = "no description";
		}

		private String clean(String string) {
			if (string != null) {
				string = string.replace('\n', ' ');
				string = string.replace('=', ' ');
			}
			return string;
		}

		public String toString() {
			return getKey();
		}
	}
}
