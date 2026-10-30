package fsim;

/** Não há blocos livres suficientes no disco. */
public class DiskFullException extends FSException {
    private static final long serialVersionUID = 1L;

    public DiskFullException(String message) {
        super(message);
    }
}
