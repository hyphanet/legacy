package freenet.support.servlet.http;

import java.util.*;


/**
 * Class to keep HttpSessionImpl's.  Note: This interface is specific to
 * HttpSessionImpl's and will not hold HttpSession's.  This is because
 * we seem to be using some methods which are not in the HttpSession interface.
 * This class also manages session expiration
 * @author oskar
 */
public class SessionHolderImpl implements SessionHolder,Runnable {
	private Hashtable sessionStorage = new Hashtable();
	private Thread sessionReaperThread;
    public synchronized HttpSessionImpl getSession(String id) {
	return ((HttpSessionImpl) sessionStorage.get(id));
    }

    public synchronized void putSession(HttpSessionImpl s) {
    	if(sessionReaperThread == null) {
			sessionReaperThread = new Thread(this);
			sessionReaperThread.setName("HTTP Servletsession reaper thread");
			sessionReaperThread.setDaemon(true);
			sessionReaperThread.start();
    	}
		
		sessionStorage.put(s.getId(), s);
		
    }
    
	public synchronized void killSession(HttpSessionImpl s) {
		sessionStorage.remove(s.getId());
		s.invalidate();
	}
    
    public synchronized void purgeIdle()
    {
		for (Enumeration e = sessionStorage.elements() ; e.hasMoreElements();){
			HttpSessionImpl s = (HttpSessionImpl)e.nextElement();
			if(s.getMaxInactiveInterval()<s.getIdleTime())
				killSession(s);
		}
    }
    
	public void run() {
		while(true)
		{
			purgeIdle();
			try{
				Thread.sleep(10000); //TODO: hardcoded
			}catch(InterruptedException e){}
		}
	}
    

}
