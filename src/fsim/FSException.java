package fsim;

/** Erro do sistema de arquivos (exibido ao usuário pelo shell). */
public class FSException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FSException(String message) {
        super(message);
    }
}
