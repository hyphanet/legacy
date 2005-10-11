package freenet.support.servlet;

/**
 * Thrown by the HttpServlet when parsing. Equivalent to 400
 *
 * @author oskar
 */

public class BadRequestException extends Exception {

    public BadRequestException(String reason) {
        super(reason);
    }



}
