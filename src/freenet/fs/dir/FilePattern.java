package freenet.fs.dir;

/**
 * For performing very simplistic pattern matching on FileNumber
 * objects.  The key() value is used as a starting point for walking
 * through a lexicographically ordered list of FileNumbers.  The
 * isLimitedBy() tests are used to terminate the process without
 * having to walk through the entire list.
 */
public interface FilePattern {

    /**
     * @return  true to search in ascending lexicographic order
     */
    boolean ascending();

    /**
     * @return  a starting search point
     */
    FileNumber key();

    /**
     * @return  true, if the argument matches this pattern
     */
    boolean matches(FileNumber fn);

    /**
     * @return  true, if no FileNumber objects lexicographically
     *          more distant than the argument will match
     */
    boolean isLimitedBy(FileNumber fn);
}


