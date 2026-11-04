package fsim;

/** Não há memória suficiente na RAM para alocação. */
public class MemoryFullException extends FSException {
    private static final long serialVersionUID = 1L;

    public final boolean externalFragmentation;

    public MemoryFullException(String message) {
        this(message, false);
    }

    public MemoryFullException(String message, boolean externalFragmentation) {
        super(message);
        this.externalFragmentation = externalFragmentation;
    }
}
