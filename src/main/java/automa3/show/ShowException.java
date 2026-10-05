package automa3.show;

/** A show creator step that cannot go on, with a message for the user. */
public class ShowException extends Exception {

    public ShowException(String message) {
        super(message);
    }

    public ShowException(String message, Throwable cause) {
        super(message, cause);
    }
}
