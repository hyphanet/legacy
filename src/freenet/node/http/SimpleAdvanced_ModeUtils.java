/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
/*
 * Created on Jun 29, 2003
 *  
 */
package freenet.node.http;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import freenet.node.Main;
import freenet.node.Node;

/**
 * @author Iakin
 *  
 */
public class SimpleAdvanced_ModeUtils {

    private static boolean initted = false;

    private static boolean isSimpleMode = false;

    public synchronized static boolean isAdvancedMode(HttpServletRequest req) {
        if (!initted) {
            isSimpleMode = Node.defaultToSimpleUIMode;
            initted = true;
        }

        //First, check if the user has choosen to 'save' his simple/advanced
        // setting some time in the past...
        String s = (String) req.getAttribute("PersistantModeCookieState");
        if (s == null) { //No special care need to be taken since we haven't
                         // modified the cookie
            Cookie c = getStoredSimpleAdvancedModeCookie(req);
            if (c != null) return c.getValue().equalsIgnoreCase("Advanced");
        } else {
            if (s.equalsIgnoreCase("Advanced"))
                return true;
            else if (s.equalsIgnoreCase("Simple")) return false;

        }
        //Second, check if the user has made a per-session choice
        if (req.getSession(false) != null) { //Do not create a session..
            Object oAttrib = req.getSession().getAttribute("SimpleAvancedMode");
            if (oAttrib != null) {
                try {
                    String sAttrib = (String) oAttrib;
                    if (sAttrib.equalsIgnoreCase("Advanced"))
                        return true;
                    else if (sAttrib.equalsIgnoreCase("Simple")) return false;
                    //else Hmmm... what to do here.. log an error? nah.. I'll
                    // let the user fall through to the servers default mode
                } catch (ClassCastException e) {
                }
            }
        }

        //Last chance, return the server-static setting.
        return !isSimpleMode;
    }

    private static Cookie getStoredSimpleAdvancedModeCookie(HttpServletRequest req) {
        Cookie cookies[] = req.getCookies();
        if (cookies != null) for (int i = 0; i < cookies.length; i++)
            if (cookies[i].getName().compareTo("SimpleAvancedModeSetting") == 0) return cookies[i];
        return null;
    }

    public static void handleRequestedParams(HttpServletRequest req, HttpServletResponse resp) {

        String sMode = req.getParameter("setSimpleAdvancedMode");
        if (sMode != null) {
            boolean bSimpleModeRequested = true; //default setting here
                                                 // doesn't matter, just keep
                                                 // the compiler from
                                                 // complaining
            boolean bFound = true;
            //Only two modes supported right now. Do some sanity checking
            if (sMode.equalsIgnoreCase("Advanced"))
                bSimpleModeRequested = false;
            else if (sMode.equalsIgnoreCase("Simple"))
                bSimpleModeRequested = true;
            else
                bFound = false;
            if (bFound) {
                Cookie c = getStoredSimpleAdvancedModeCookie(req);
                if (c != null) { //If the user has a locally stored state we
                                 // will update his stored state instead of
                                 // switching the mode on the server or
                                 // starting a user session
                    setPersistantMode(resp, sMode, false);
                    req.setAttribute("PersistantModeCookieState", sMode);
                } else {
                    if (!Main.publicNode) //publicNodes shouldn't allow users
                                          // to modify the servers state,
                                          // unconditionally use a session if
                                          // the user wants to deviate from the
                                          // servers default.
                        isSimpleMode = bSimpleModeRequested;
                    else
                        req.getSession().setAttribute("SimpleAvancedMode", sMode);
                }
            }
        }

        //Hmm, I assume that it would be best if this is done *after* the
        // previous stuff or else it might confuse some poor sod managing to
        // ask for a save and switching at the same time..
        String sStoreSetting = req.getParameter("storeSimpleAdvancedMode");
        if (sStoreSetting != null) if (sStoreSetting.equalsIgnoreCase("true"))
            setPersistantMode(resp, req, false);
        else
            setPersistantMode(resp, req, true);

    }

    public static void setPersistantMode(HttpServletResponse resp, HttpServletRequest req, boolean erase) {
        setPersistantMode(resp, isAdvancedMode(req) ? "Advanced" : "Simple", erase);

        //This is an ugly hack to make sure that any subsequent readings from
        // this 'req' picks up the in-progress persist/unpersistchange)
        if (erase)
            req.setAttribute("PersistantModeCookieState", "deleted");
        else
            req.setAttribute("PersistantModeCookieState", isAdvancedMode(req) ? "Advanced" : "Simple");
    }

    private static void setPersistantMode(HttpServletResponse resp, String mode, boolean erase) {
        Cookie c = new Cookie("SimpleAvancedModeSetting", mode);
        c.setComment("Store simple/advanced mode preference"); //Sadly this is
                                                               // not used by
                                                               // all browsers
        c.setPath("/"); //All of this fred
        if (erase) {
            c.setMaxAge(0); //Should cause the browser to remove the cookie
            c.setValue(""); //But since IE wont accept that we'll set it to
                            // somthing invalid
        } else
            c.setMaxAge(3600 * 24 * 365); //A year should be enough for
                                          // everyone
        resp.addCookie(c);
    }

    public static String renderModeSwitchLink(String uri, HttpServletRequest req) {

        Cookie c = getStoredSimpleAdvancedModeCookie(req);
        String storeLink;
        String s = (String) req.getAttribute("PersistantModeCookieState");
        if (((c == null) || (s == null ? false : s.equals("deleted")))
                && !(s == null ? false : (s.equalsIgnoreCase("Advanced") || s.equalsIgnoreCase("Simple")))) //Chech
                                                                                                            // for
                                                                                                            // an
                                                                                                            // in-progress
                                                                                                            // cookiedeletion/creation
            storeLink = "<small><a href='" + uri + "?storeSimpleAdvancedMode=true'>" + "[Save current mode]".replaceAll(" ", "&nbsp;")
                    + "</a></small>";
        else
            storeLink = "<small><a href='" + uri + "?storeSimpleAdvancedMode=false'>" + "[Unsave current mode]".replaceAll(" ", "&nbsp;")
                    + "</a></small>";

        if (isAdvancedMode(req))
            return "<small><a href='" + uri + "?setSimpleAdvancedMode=Simple'>" + "[Switch to simple mode]".replaceAll(" ", "&nbsp;")
                    + "</a></small><br />" + storeLink;
        else
            return "<small><a href='" + uri + "?setSimpleAdvancedMode=Advanced'>" + "[Switch to advanced mode]".replaceAll(" ", "&nbsp;")
                    + "</a></small><br />" + storeLink;
    }
}
