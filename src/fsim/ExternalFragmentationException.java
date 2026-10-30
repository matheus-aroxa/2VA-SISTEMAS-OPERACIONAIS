package fsim;

/** Há espaço livre suficiente, mas nenhuma lacuna contígua grande o bastante. */
public class ExternalFragmentationException extends DiskFullException {
    private static final long serialVersionUID = 1L;

    public ExternalFragmentationException(String message) {
        super(message);
    }
}
