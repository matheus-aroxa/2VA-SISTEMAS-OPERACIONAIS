package fsim;

import java.util.HashMap;
import java.util.Map;

/** Arquivo ou diretório da árvore. A identidade do objeto é usada como chave pelos alocadores. */
final class Node {
    String name;
    final boolean isDir;
    Node parent;
    final Map<String, Node> children = new HashMap<>();
    int size;

    Node(String name, boolean isDir, Node parent) {
        this.name = name;
        this.isDir = isDir;
        this.parent = parent;
    }

    String path() {
        if (parent == null) {
            return "/";
        }
        String p = parent.path();
        return p.equals("/") ? p + name : p + "/" + name;
    }

    boolean isAncestorOf(Node other) {
        for (Node n = other; n != null; n = n.parent) {
            if (n == this) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return (isDir ? "<Dir " : "<Arq ") + path() + ">";
    }
}
