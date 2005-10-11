/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.client.http;

import java.io.PrintWriter;


/**
 * (BaseContext has been removed from ServletWithContext, which is)
 * Helper base class for constructing Servlets that
 * keep session context information *without* using 
 * cookies.
 * <p>
 * Subclasses can subclass BaseContext to contain 
 * relevant per-session information and use
 * makeContextURL() to make session specific URLs
 * then use getContextFromURL() to retrieve the
 * session information on a later request.
 * <p>
 * 
 * @author     <a href="mailto:giannijohansson@attbi.com">Gianni Johansson</a>
 *
 **/

/*protected*/ public class BaseContext implements Reapable {
    private final static String START_TAG="__ID_";
    private final static String END_TAG="_ID__";

    public final static String DUMMY_TAG="__DUMMY__";
       
        long timeToDie = -1;
        long lifeTimeMs = -1;
        private String id;
       
        private static ContextManager single_cm;
        private static Reaper single_reaper;

    static {
        // Poll for reapable objects every 60 seconds
        /*single_reaper = new Reaper(60000);
        Thread reaperThread = new Thread(single_reaper, "Polling thread for single Reaper instance.");
        reaperThread.setDaemon(true);
        reaperThread.start();*/
        single_reaper = ServletWithContext.single_reaper;
        single_cm = new ContextManager();
    }
       
        BaseContext(/*pass reaper here, */ long lifeTimeMs) {
            BaseContext.this.lifeTimeMs = lifeTimeMs;
            touch();
            id =  single_cm.add(BaseContext.this);
            single_reaper.add(BaseContext.this);
        }

        void touch() {
            timeToDie = System.currentTimeMillis() + lifeTimeMs;
        }

        // Subclasses must call this version if they 
        // override reap().
        public boolean reap() {
            single_cm.remove(id);
            // Remove the reference from the Reaper's
            // table in the case where client code 
            // calls reap() explictly.
            single_reaper.remove(BaseContext.this);
            return true;
        }

        public boolean isExpired() { 
            return  System.currentTimeMillis() > timeToDie;
        }

        public final String makeContextURL(String urlWithDummyTag) {
            return makeContextURL_(urlWithDummyTag, id);
        } 

       
    protected final static String makeContextURL_(String urlWithDummyTag, String id) { 
        int pos = urlWithDummyTag.indexOf(DUMMY_TAG);
        if (pos == -1) {
            throw new IllegalArgumentException("DUMMY_TAG not found.");
        }

        StringBuffer ret = new StringBuffer(urlWithDummyTag);
        ret.replace(pos, pos + DUMMY_TAG.length(), START_TAG + id + END_TAG);
        return ret.toString();
    }

    public void writeHtml(PrintWriter pw) {
	}
}



