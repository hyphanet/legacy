package freenet.thread;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import freenet.node.Node;

/**
 * Takes a snapshot of the current thread hiearchy and allow the user to access
 * various status information as well as dumping some stats to HTML.
 * 
 * @author Iakin
 */
public class ThreadStatusSnapshot {

	private Hashtable consumers = new Hashtable();

	private PoolThreadCount tc = new PoolThreadCount();

	private group root;

	public ThreadStatusSnapshot() {
		ThreadGroup tg = Thread.currentThread().getThreadGroup().getParent();
		ThreadGroup topMost = null;
		while (tg != null) {
			topMost = tg;
			tg = tg.getParent();
		}
		root = new group(topMost);
	}

	public Hashtable getPoolConsumers() {
		return consumers;
	}

	/**
	 * Returns the total number of/the number of available threads in the
	 * threadPool
	 */
	public PoolThreadCount getPoolThreadCounts() {
		return tc;
	}

	private void countPooledThread(PooledThread t) {
		//TODO: Wouldn't it be better to just ask the threadpool for the
		// information counted here?
		tc.totalPooled++;
		Runnable job = t.job();
		if (job != null)
			countConsumer(job);
		else
			tc.pooledAvailable++;
	}

	private void countConsumer(Runnable job) {
		String type = job.toString();
		type = type.substring(0, type.indexOf('@'));
		Integer con = (Integer) consumers.get(type);
		if (con == null) {
			con = new Integer(1);
		} else {
			con = new Integer(con.intValue() + 1);
		}
		consumers.put(type, con);
	}

	public static class PoolThreadCount {

		int totalPooled = 0;

		int pooledAvailable = 0;
	}

	class group {

		String groupName;

		List lThreads = new LinkedList();

		List lSubGRoups = new LinkedList();

		group(ThreadGroup group) {
			groupName = group.getName();
			Thread[] aThreads = new Thread[group.activeCount()];
			int threads = group.enumerate(aThreads, false);
			for (int j = 0; j < threads; j++) {
				Thread t = aThreads[j];
				if (t != null) //Yes.. sometimes this can happen /Iakin
					lThreads.add(new ThreadInfo(t));
			}
			ThreadGroup[] groups = new ThreadGroup[group.activeGroupCount()];
			int groupcount = group.enumerate(groups, false);
			for (int i = 0; i < groupcount; i++)
				lSubGRoups.add(new group(groups[i]));
		}

		void toHTML(StringBuffer buffer) {
			buffer.ensureCapacity(buffer.length() + lThreads.size() * 25);
			buffer.append("\n<li><b>" + groupName + "</b><ul>");
			Iterator it = lThreads.iterator();
			while (it.hasNext()) {
				ThreadInfo t = (ThreadInfo) it.next();
				buffer.append("\n<li>" + t.name);
                String job = t.jobString;
                if (job != null) {
                    long start;
                    try {
                        start = new Long(job.substring(job.lastIndexOf(" ")+1)).longValue();
                    } catch (Throwable x) {
                        start = 0;
                    }
                    if (start > Node.startupTimeMs && start < System.currentTimeMillis() - 30 * 60 * 1000)
                        buffer.append("<FONT COLOR=Red>");
                    buffer.append(": " + t.jobString);
                    if (start > Node.startupTimeMs && start < System.currentTimeMillis() - 30 * 60 * 1000)
                        buffer.append("</FONT>");
                }
                buffer.append("</li>");
			}
			it = lSubGRoups.iterator();
			while (it.hasNext())
				 ((group) it.next()).toHTML(buffer);
			buffer.append("</ul>");
		}

		class ThreadInfo {

			String name;

			String jobString;

			ThreadInfo(Thread t) {
				name = t.getName();
				if (t instanceof PooledThread) {
					Runnable r = ((PooledThread) t).job();
                    if (r != null) jobString = r.toString();
					countPooledThread((PooledThread) t);
				}
			}
		}
	}

	public String threadTreeToHTML() {
		StringBuffer buffer = new StringBuffer();
		root.toHTML(buffer);
		return buffer.toString();
	}

	public String threadStatusToHTML() {
		StringWriter ssw = new StringWriter(200);
		PrintWriter sw = new PrintWriter(ssw);
		sw.println("<table width=\"100%\">");
        sw.println("<tr><td>Total pooled threads</td><td align=right>" + tc.totalPooled + "</td></tr>");
        sw.println("<tr><td>Available pooled threads</td><td align=right>" + tc.pooledAvailable + "</td></tr>");
        sw.println("<tr><td>Pooled threads in use</td><td align=right>" + (tc.totalPooled - tc.pooledAvailable) + "</td></tr>");
		sw.println("</table>");
		return ssw.toString();
	}

	public String poolConsumersToHTML() {
		StringWriter ssw = new StringWriter(200);
		PrintWriter sw = new PrintWriter(ssw);
		sw.println("<table width=\"100%\">");
        sw.println("<tr><th align=\"left\">Class</th><th align=\"right\">Threads used</th>");
		Object[] types = getPoolConsumers().keySet().toArray();
		java.util.Arrays.sort(types);
		for (int x = 0; x < types.length; x++) {
            sw.println("<tr><td>" + types[x] + "</td><td align=\"right\">" + ((Integer) getPoolConsumers().get(types[x])).intValue() + "</td></tr>");
		}
		sw.println("</table>");
		return ssw.toString();
	}
}
