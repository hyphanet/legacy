package freenet.support;

import java.util.Enumeration;

/** Interface for maps, dictionaries, linked lists, etc. that can be
  * traversed forwards and backwards from any point.
  *
  * @author tavin
  */
public interface Walkable {

	/** Returns an Enumeration in forward order from the beginning.
	  *
	  * @return Enumeration in forward order
	 **/
	Enumeration forwardElements();

	/** Returns an Enumeration in forward order from a specified point.
	  *
	  * @param startAt the starting key
	  * @param inclusive whether to include startAt
	  * @return Enumeration in forward order
	 **/
	Enumeration forwardElements(Object startAt, boolean inclusive);

	/** Returns an Enumeration in reverse order from the end.
	  *
	  * @return Enumeration in reverse order
	 **/
	Enumeration reverseElements();

	/** Returns an Enumeration in reverse order from a specified point.
	  *
	  * @param startAt the starting key
	  * @param inclusive whether to include startAt
	  * @return Enumeration in reverse order
	 **/
	Enumeration reverseElements(Object startAt, boolean inclusive);
}
