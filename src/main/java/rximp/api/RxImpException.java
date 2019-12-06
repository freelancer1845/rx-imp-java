package rximp.api;

/**
 * RxImpException
 */
public class RxImpException extends Exception {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    public RxImpException() {
    }

    public RxImpException(String message) {
        super(message);
    }

    public RxImpException(Throwable cause) {
        super(cause);
    }

    public RxImpException(String message, Throwable cause) {
        super(message, cause);
    }

    public RxImpException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

}