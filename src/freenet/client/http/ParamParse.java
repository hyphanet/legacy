package freenet.client.http;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

import freenet.support.Logger;

/*
 *  This code is part of fproxy, an HTTP proxy server for Freenet.
 *  It is distributed under the GNU Public Licence (GPL) version 2.  See
 *  http://www.gnu.org/ for further details of the GPL.
 */
/**
 * Parameter parsing helper functions
 */
public class ParamParse {

  /**
   *  Read integer parameter from query
   *
   * @param  req         HttpServletRequest
   * @param  lg          Logger
   * @param  name        Parameter name
   * @param  defaultVal  Default value
   * @param  min         Minimum acceptable value
   * @param  max         Maximum acceptable value
   * @return             Read value (limit enforced)
   */
  public final static int readInt(HttpServletRequest req, Logger lg, String name,
      int defaultVal, int min, int max) {
    String valueAsString = req.getParameter(name);
    int ret = defaultVal;
    if (valueAsString != null) {
      try {
        ret = Integer.parseInt(freenet.support.URLDecoder.decode(valueAsString));
        lg.log(ParamParse.class, "Read " + name + " from query: " + ret, Logger.DEBUG);
      } catch (Exception e) {
        lg.log(ParamParse.class, "Couldn't parse " + name + " from query, using: " + ret,
            Logger.ERROR);
      }
    }
    return enforceLimits(lg, name, min, max, ret);
  }


  /**
   *  Read boolean parameter from query
   *
   * @param  req         HttpServletRequest
   * @param  lg          Logger
   * @param  name        Parameter name
   * @param  defaultVal  Default value
   * @return             Read value
   */
  public final static boolean readBoolean(HttpServletRequest req, Logger lg, String name, boolean defaultVal) {
      String valueAsString = "";
//       try {
    valueAsString = req.getParameter(name);
//       } catch (Exception e) {
// 	  lg.log(ParamParse.class, "Exception in getInitParameter", e, Logger.DEBUG);
//       }
    boolean ret = defaultVal;
    if (valueAsString != null) {
      try {
        final String cleanValue = freenet.support.URLDecoder.decode(valueAsString).toLowerCase();
        ret = cleanValue.equals("true");
        lg.log(ParamParse.class, "Read " + name + " from query: " + ret, Logger.DEBUG);
      } catch (Exception e) {
        lg.log(ParamParse.class, "Couldn't parse " + name + " from query, using: " + ret,
            Logger.ERROR);
      }
    }
    return ret;
  }


  /**
   *  Enforce limits of parameter values
   *
   * @param  lg     Logger
   * @param  name   Parameter name
   * @param  min    Minimum acceptable value
   * @param  max    Maximum acceptable value
   * @param  value  Actual value
   * @return        Limited value
   */
  private final static int enforceLimits(Logger lg, String name, int min,
      int max, int value) {
    if (value > max) {
      int oldValue = value;
      value = max;
      lg.log(ParamParse.class, name + "=" + oldValue + " too big, using:  " + value,
          Logger.NORMAL);
    }
    if (value < min) {
      int oldValue = value;
      value = min;
      lg.log(ParamParse.class, name + "=" + oldValue + " too small, using:  " + value,
          Logger.NORMAL);
    }
    return value;
  }


  /**
   *  Read integer parameter from initParameters
   *
   * @param  servlet     HttpServlet
   * @param  lg          Logger
   * @param  name        Parameter name
   * @param  defaultVal  Default value
   * @param  min         Minimum acceptable value
   * @param  max         Maximum acceptable value
   * @return             Read value (limit enforced)
   */
  public final static int readInt(HttpServlet servlet, Logger lg, String name,
      int defaultVal, int min, int max) {
    String valueAsString = servlet.getInitParameter(name);
    int ret = defaultVal;
    if (valueAsString != null) {
      try {
        ret = Integer.parseInt(valueAsString);
        lg.log(ParamParse.class, "Read " + name + " from initParameters: " + ret,
            Logger.DEBUG);
      } catch (Exception e) {
        lg.log(ParamParse.class, "Couldn't parse " + name + " from initParameters, using: "
             + ret+" - reason: "+e + " - string was "+valueAsString, e, Logger.ERROR);
      }
    }
    else {
        lg.log(ParamParse.class, "Couldn't ret " + name + " from initParameters.",
            Logger.DEBUG);
    }

    return enforceLimits(lg, name, min, max, ret);
  }


  /**
   *  Read boolean parameter from initParameters
   *
   * @param  servlet     HttpServlet
   * @param  lg          Logger
   * @param  name        Parameter name
   * @param  defaultVal  Default value
   * @return             Read value
   */
  public final static boolean readBoolean(HttpServlet servlet,
      Logger lg, String name, boolean defaultVal) {
      String valueAsString = "";
//       try {
    valueAsString = servlet.getInitParameter(name);
//       } catch (Exception e) {
// 	  lg.log(ParamParse.class, "Exception in getInitParameter", e, Logger.DEBUG);
//       }
    lg.log(ParamParse.class, "Got valueAsString: "+valueAsString, Logger.DEBUG);
    boolean ret = defaultVal;
    if (valueAsString != null) {
      try {
        final String cleanValue = valueAsString.toLowerCase();
        ret = cleanValue.equals("true");
        lg.log(ParamParse.class, "Read " + name + " from initParameters: " + ret,
            Logger.DEBUG);
      } catch (Exception e) {
        lg.log(ParamParse.class, "Couldn't parse " + name +
            " from initParameters, using: " + ret,
            Logger.ERROR);
      }
    }
    return ret;
  }

}




