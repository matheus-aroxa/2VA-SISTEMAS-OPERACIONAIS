package fsim;

/** Negação de acesso pela Matriz de Permissões / ACL do sistema de arquivos. */
public class AccessDeniedException extends FSException {
    private static final long serialVersionUID = 1L;

    final String path;
    final String owner;
    final String ownerGroup;
    final String permissions;
    final String actionLabel;
    final String actionNoun;
    final String user;
    final String userGroup;

    public AccessDeniedException(String path, String owner, String ownerGroup, String permissions,
                                 String actionLabel, String actionNoun, String user, String userGroup) {
        super("permissão negada: " + actionNoun + " em '" + path + "' para o usuário '" + user + "'");
        this.path = path;
        this.owner = owner;
        this.ownerGroup = ownerGroup;
        this.permissions = permissions;
        this.actionLabel = actionLabel;
        this.actionNoun = actionNoun;
        this.user = user;
        this.userGroup = userGroup;
    }
}
