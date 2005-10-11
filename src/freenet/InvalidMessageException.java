package freenet;

public class InvalidMessageException extends Exception
{
  public InvalidMessageException(String comment)
    {
      super(comment);
    }
}
