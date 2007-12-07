package freenet;

import freenet.support.Fields;
import freenet.support.Logger;

import java.util.StringTokenizer;

/**
 * Central spot for stuff related to the versioning of the codebase.
 */
public abstract class Version {

	/** FReenet Reference Daemon */
	public static final String nodeName = "Fred";

	/** The current tree version */
	public static final String nodeVersion = "0.6";

	/** The protocol version supported */
	public static String protocolVersion = "1.51";
	public static String altProtocolVersion = "1.52";

	/** The build number of the current revision */
	public static final int buildNumber = 60278;

	/** Oldest build of Fred we will talk to */
	public static final int lastGoodBuild = 60235;
	// Rate limiting fixes

	/** The highest reported build of fred */
	public static int highestSeenBuild = buildNumber;

	/** The current stable tree version */
	public static final String stableNodeVersion = "0.5";

	/** The stable protocol version supported */
	public static String stableProtocolVersion = "STABLE-1.51";

	/** Oldest stable build of Fred we will talk to */
	public static final int lastGoodStableBuild = 5099;
	// 5077: bidi routing

	/** Revision number of Version.java as read from CVS */
	public static final String cvsRevision;
	
	private static boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,Version.class);
	static {
		StringTokenizer cvsId =
			new StringTokenizer("$Id: Version.java,v 1.970 2005/08/09 13:59:21 amphibian Exp $");
		cvsId.nextToken();
		cvsId.nextToken();
		cvsRevision = cvsId.nextToken();
	}

	/**
	 * @return the node's version designators as an array
	 */
	public static final String[] getVersion() {
		String[] ret =
			{ nodeName, nodeVersion, protocolVersion, "" + buildNumber };
		return ret;
	}

	/**
	 * @return the version string that should be presented in the NodeReference
	 */
	public static final String getVersionString() {
		return Fields.commaList(getVersion());
	}

	/**
	 * @return true if requests should be accepted from nodes brandishing this
	 *         protocol version string
	 */
	private static boolean goodProtocol(String prot) {
		if (prot.equals(protocolVersion) || prot.equals(altProtocolVersion)
			|| prot.equals(stableProtocolVersion)
			)
			return true;
		return false;
	}

	/**
	 * @return true if requests should be accepted from nodes brandishing this
	 *         version string
	 */
	public static final boolean checkGoodVersion(
		String version) {
	    if(version == null) {
	        Core.logger.log(Version.class, "version == null!",
	                new Exception("error"), Logger.ERROR);
	        return false;
	    }
		String[] v = Fields.commaList(version);

		if (v.length < 3 || !goodProtocol(v[2])) {
			return false;
		}
		if (sameVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				if (build < lastGoodBuild) {
					if(logDEBUG) Core.logger.log(
						Version.class,
						"Not accepting unstable from version: "
							+ version
							+ "(lastGoodBuild="
							+ lastGoodBuild
							+ ")",
						Logger.DEBUG);
					return false;
				}
			} catch (NumberFormatException e) {
				Core.logger.log(
					Version.class,
					"Not accepting (" + e + ") from " + version,
					Logger.MINOR);
				return false;
			}
		}
		if (stableVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				if(build < lastGoodStableBuild) {
					if(logDEBUG) Core.logger.log(
						Version.class,
						"Not accepting stable from version"
							+ version
							+ "(lastGoodStableBuild="
							+ lastGoodStableBuild
							+ ")",
						Logger.DEBUG);
					return false;
				}
			} catch (NumberFormatException e) {
				Core.logger.log(
					Version.class,
					"Not accepting (" + e + ") from " + version,
					Logger.MINOR);
				return false;
			}
		}
		if(logDEBUG)
			Core.logger.log(Version.class, "Accepting: " + version, Logger.DEBUG);
		return true;
	}

	/**
	 * @return string explaining why a version string is rejected
	 */
	public static final String explainBadVersion(String version) {
		String[] v = Fields.commaList(version);
		
		if (v.length < 3 || !goodProtocol(v[2])) {
			return "Required protocol version is "
						+ protocolVersion
						+ " or " + stableProtocolVersion
						;
		}
		if (sameVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				if (build < lastGoodBuild)
					return "Build older than last good build " + lastGoodBuild;
			} catch (NumberFormatException e) {
				return "Build number not numeric.";
			}
		}
		if (stableVersion(v)) {
			try {
				int build = Integer.parseInt(v[3]);
				if (build < lastGoodStableBuild)
					return "Build older than last good stable build " + lastGoodStableBuild;
			} catch (NumberFormatException e) {
				return "Build number not numeric.";
			}
		}
		return null;
	}

	/**
	 * Update static variable highestSeenBuild anytime we encounter
	 * a new node with a higher version than we've seen before
	 */
	public static final void seenVersion(String version) {
		String[] v = Fields.commaList(version);

		if (v.length < 3)
			return; // bad, but that will be discovered elsewhere

		if (sameVersion(v)) {

			int buildNo;
			try {
				buildNo = Integer.parseInt(v[3]);
			} catch (NumberFormatException e) {
				return;
			}
			if (buildNo > highestSeenBuild) {
				if (Core.logger.shouldLog(Logger.MINOR, Version.class)) {
					Core.logger.log(
						Version.class,
						"New highest seen build: " + buildNo,
						Logger.MINOR);
				}
				highestSeenBuild = buildNo;
			}
		}
	}

	/**
	 * @return true if the string describes the same node version as ours.
	 * Note that the build number may be different, and is ignored.
	 */
	public static boolean sameVersion(String[] v) {
		return v[0].equals(nodeName)
			&& v[1].equals(nodeVersion)
			&& v.length >= 4;
	}

	/**
	 * @return true if the string describes a stable node version
	 */
	private static boolean stableVersion(String[] v) {
		return v[0].equals(nodeName)
			&& v[1].equals(stableNodeVersion)
			&& v.length >= 4;
	}

	public static void main(String[] args) throws Throwable {
		System.out.println(
			"Freenet: "
				+ nodeName
				+ " "
				+ nodeVersion
				+ " (protocol "
				+ protocolVersion
				+ ") build "
				+ buildNumber
				+ " (last good build: "
				+ lastGoodBuild
				+ ")");
	}
}
