package freenet.node;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URL;

import freenet.Core;
import freenet.config.Params;
import freenet.support.Checkpointed;
import freenet.support.Logger;

/**
 * Encapsulates "WatchMe" functionality for monitoring node behavior
 * 
 * @author <a href="mailto:ian@freenetproject.org">Ian Clarke</a>
 */
public class WatchMe implements Checkpointed {

	//Increment version number here and in params.txt
	//to kill off nodes running old code
	/** WatchMe code version number */
	public final static int version = 3;

	/** The log file */
	public PrintWriter logStream;
	/** Location of params.txt and curtime.php */
	public final static String baseUrl =
		"http://hawk.freenetproject.org/~watchme/";
	/** Name of log file */
	public final static String logFile = "watchme.log";
	/** Server to send logs to */
	public final static String logServer = "hawk.freenetproject.org";
	/** TCP port to connect to the server on */
	public final static int logPort = 9125;
	/** Difference between server and node time */
	public long timeDrift;
	/** Parameters read from the server */
	public Params wmParams = new Params();

	/**
	 * Initializes the WatchMe functionality
	 * 
	 * @param retries
	 *            Number of times to retry connecting to the server
	 */
	public void init(int retries) {
		if (!syncTime(retries)) {
			System.err.println("Couldn't synchronize time with server");
			System.exit(1);
		}

		try {
			// Make sure there are no old logfiles lying around to confuse us
			 (new File(logFile)).delete();
			(new File(logFile + ".tmp")).delete();
			logStream = new PrintWriter(new FileWriter(logFile));
		} catch (Exception e) {
			System.err.println("Couldn't access watchme.log");
			System.exit(1);
		}

		if (!getParams(retries)) {
			System.err.println("Couldn't read params from server");
			System.exit(1);
		}

		checkVersion();

	}

	/**
	 * Determines the difference between server and node time
	 * 
	 * @param retries
	 *            Number of times to retry connecting to the server
	 * @return Whether time can be synced to the server
	 */
	public boolean syncTime(int retries) {
		for (; retries > 0; --retries)
			if (syncTime())
				return true;

		return false;
	}

	/**
	 * Determines the difference between server and node time
	 * 
	 * @return Whether time can be synced to the server
	 */
	public boolean syncTime() {
		try {
			InputStreamReader timeIn =
				new InputStreamReader(
					(new URL(baseUrl + "curtime.php")).openStream());
			StringBuffer Stime = new StringBuffer(20);
			long time;
			int r;
			while ((r = timeIn.read()) != -1)
				Stime.append((char) r);

			time = Long.parseLong(Stime.toString());
			timeDrift = time - (System.currentTimeMillis() / 1000);
		} catch (Exception e) {
			return false;
		}
		return true;
	}

	/**
	 * Retrieves parameters for the WatchMe object from the server
	 * 
	 * @param retries
	 *            Number of times to retry connecting to the server
	 * @return Whether the params can be read from the server
	 */
	public boolean getParams(int retries) {
		for (; retries > 0; --retries)
			if (getParams())
				return true;

		return false;
	}

	/**
	 * Retrieves parameters for the WatchMe object from the server
	 * 
	 * @return Whether the params can be read from the server
	 */
	public boolean getParams() {
		try {
			wmParams.readParams((new URL(baseUrl + "params.txt")).openStream());
		} catch (Exception e) {
			return false;
		}
		return true;
	}

	/** Check the WatchMe code version against the minimum supported version */
	public void checkVersion() {
		if (wmParams.getInt("minVersion") > version) {
			System.err.println(
				"Watchme version " + version + " is no longer supported.");
			System.err.println(
				"Please upgrade to version "
					+ wmParams.getInt("minVersion")
					+ " or newer.");
			System.exit(1);
		}
	}

	/**
	 * Calculates the current time on the server and converts to a string
	 * 
	 * @return The current time on the server
	 */
	public String currentTime() {
		return new Long(System.currentTimeMillis() / 1000 + timeDrift)
			.toString();
	}

	/**
	 * Prepends the server's time to a message and logs it
	 * 
	 * @param log
	 *            Message to be logged
	 */
	public void log(String log) {
		synchronized (this) {
			logStream.println(currentTime() + " " + log);
			logStream.flush();
		}
	}

	/**
	 * If message logging is enabled, logs a sent message
	 * 
	 * @param messageType
	 *            Type of message to be logged
	 * @param messageId
	 *            ID of message to be logged
	 * @param destination
	 *            Node the message was originally sent to
	 * @param htl
	 *            Hops To Live sent in the message
	 */
	public void logSendMessage(
		String messageType,
		String messageId,
		String destination,
		long htl) {
		if (wmParams.getBoolean("logMessages"))
			log(
				"sent "
					+ messageType
					+ " "
					+ messageId
					+ " "
					+ destination
					+ " "
					+ htl);
	}

	/**
	 * If message logging is enabled, logs a received message
	 * 
	 * @param messageType
	 *            Type of message to be logged
	 * @param messageId
	 *            ID of message to be logged
	 * @param source
	 *            Node the message was received from
	 * @param htl
	 *            Hops To Live received in the message
	 */
	public void logReceiveMessage(
		String messageType,
		String messageId,
		String source,
		long htl) {
		if (wmParams.getBoolean("logMessages"))
			log(
				"received "
					+ messageType
					+ " "
					+ messageId
					+ " "
					+ source
					+ " "
					+ htl);
	}

	// Schedulable stuff
	/**
	 * Gets the checkpointName attribute of the WatchMe object
	 * 
	 * @return The checkpointName value
	 */
	public String getCheckpointName() {
		return "Watchme htleckpoint";
	}

	/**
	 * Determines when the next WatchMe checkpoint should run
	 * 
	 * @return Time the next WatchMe checkpoint should be run
	 */
	public long nextCheckpoint() {
		try {
			return System.currentTimeMillis()
				+ (wmParams.getLong("checkPointInterval") * 60 * 1000);
		} catch (ClassCastException e) {
			return System.currentTimeMillis() + 1800000;
			// default to 30 minutes
		}
	}

	/** Periodically sends logged messages to the server and rereads the params */
	public void checkpoint() {
		// Reread the parameters
		if (!getParams()) {
			Core.logger.log(
				Main.class,
				"Couldn't reread params, aborting checkpoint",
				Logger.ERROR);
			return;
		}

		checkVersion();

		// No point in doing this if nothing was logged
		if ((new File(logFile)).length() > 0) {
			// Move the logfile out of the way
			synchronized (this) {
				try {
					logStream.close();

					File source = new File(logFile);
					File dest = new File(logFile + ".tmp");
					if (!(source.renameTo(dest)
						|| (dest.delete() && source.renameTo(dest))))
						Core.logger.log(
							this,
							"Could not rename " + "watchme log",
							Logger.ERROR);
					logStream = new PrintWriter(new FileWriter(logFile));
				} catch (Exception e) {
					Core.logger.log(
						this,
						"Couldn't refresh watchme.log",
						e,
						Logger.ERROR);
					return;
				}
			}
			try {
				FileReader fr = new FileReader(logFile + ".tmp");
				BufferedReader logIn = new BufferedReader(fr);
				Socket servLog = new Socket(logServer, logPort);
				PrintWriter logOut = new PrintWriter(servLog.getOutputStream());
				int r = logIn.read();
				while (r != -1) {
					logOut.write(r);
					r = logIn.read();
				}
				logOut.close();
				logIn.close();
				servLog.close();
				(new File(logFile + ".tmp")).delete();
			} catch (Exception e) {
				Core.logger.log(
					this,
					"Couldn't report watchme.log to server",
					e,
					Logger.ERROR);
			}
		}
	}
}
