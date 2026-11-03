package fsim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitor de integridade física de blocos e varredura de ameaças do sistema de arquivos.
 *
 * Modela dois mecanismos complementares de proteção do sistema operacional:
 * (1) verificação criptográfica de integridade por bloco lógico usando hashes SHA-256 (estilo Tripwire / dm-verity),
 * capaz de detectar corrupção silenciosa ou adulteração direta nos discos físicos fora da camada do sistema de
 * arquivos; e (2) inspeção por assinaturas conhecidas de código malicioso (antivírus em espaço de sistema) com
 * isolamento preventivo por quarentena (revogação de permissões POSIX/ACL e bloqueio de leitura, escrita e execução).
 */
final class IntegrityMonitor {
    private static final String CAT_INTEGRITY = "INTEGRIDADE";

    private static final Map<String, String> SIGNATURES;

    static {
        Map<String, String> catalog = new LinkedHashMap<>();
        catalog.put("EICAR-Test-Signature", "EICAR-STANDARD-ANTIVIRUS-TEST-FILE");
        catalog.put("Trojan.Dropper.Sim", "TROJAN_DROPPER");
        catalog.put("Ransom.Locker.Sim", "RANSOM_LOCK");
        catalog.put("Worm.Payload.Sim", "MALWARE_PAYLOAD");
        SIGNATURES = Collections.unmodifiableMap(catalog);
    }

    enum Status { OK, OK_ENCRYPTED, TAMPERED, MALWARE }

    record FileFinding(Node node, Status status, String signature, boolean quarantined) {
    }

    record ScanReport(List<FileFinding> findings, int totalFiles, int alerts) {
    }

    private final Map<Node, List<byte[]>> officialHashes = new HashMap<>();
    private final Set<Node> quarantinedNodes = new HashSet<>();

    // ------------------------------------------------------ hashes oficiais

    /** Registra os hashes SHA-256 oficiais dos blocos de `node` usando `peek` (sem gerar E/S extra). */
    void recordOfficial(Node node, Disk disk, List<Integer> dataBlocks) {
        List<byte[]> hashes = new ArrayList<>(dataBlocks.size());
        for (int b : dataBlocks) {
            hashes.add(sha256(disk.peek(b)));
        }
        officialHashes.put(node, List.copyOf(hashes));
    }

    void remove(Node node) {
        officialHashes.remove(node);
        quarantinedNodes.remove(node);
    }

    void reset() {
        officialHashes.clear();
        quarantinedNodes.clear();
    }

    boolean isQuarantined(Node node) {
        return quarantinedNodes.contains(node);
    }

    void unquarantine(Node node) {
        quarantinedNodes.remove(node);
    }

    // --------------------------------------------------- varredura e defesa

    /** Varre os arquivos da subárvore `start` verificando hashes SHA-256 dos blocos e assinaturas maliciosas. */
    ScanReport scan(FileSystem fs, Node start, boolean quarantine) {
        String user = fs.auth.currentUser().name();
        String mode = fs.auth.mode().name();
        List<FileFinding> findings = new ArrayList<>();
        int alerts = 0;

        for (Node n : fs.walk(start)) {
            if (n.isDir) {
                continue;
            }
            List<Integer> blocks = fs.alloc.dataBlocks(n);
            List<byte[]> expected = officialHashes.getOrDefault(n, List.of());
            byte[] content = new byte[Math.max(0, n.size)];
            int offset = 0;
            boolean hashMismatch = blocks.size() != expected.size();

            for (int k = 0; k < blocks.size(); k++) {
                int b = blocks.get(k);
                byte[] rawBlock = fs.disk.read(b);
                byte[] actualHash = sha256(rawBlock);
                if (k < expected.size() && !MessageDigest.isEqual(expected.get(k), actualHash)) {
                    hashMismatch = true;
                }
                if (offset < content.length) {
                    int len = Math.min(rawBlock.length, content.length - offset);
                    System.arraycopy(rawBlock, 0, content, offset, len);
                    offset += len;
                }
            }

            Status status;
            String signature;
            if (hashMismatch) {
                status = Status.TAMPERED;
                signature = null;
            } else if (n.crypto == null) {
                String text = new String(content, StandardCharsets.UTF_8);
                String matched = detectSignature(text);
                if (matched != null) {
                    status = Status.MALWARE;
                    signature = matched;
                } else {
                    status = Status.OK;
                    signature = null;
                }
            } else {
                status = Status.OK_ENCRYPTED;
                signature = null;
            }

            boolean isolated = quarantinedNodes.contains(n);
            if (status == Status.TAMPERED || status == Status.MALWARE) {
                alerts++;
                if (quarantine) {
                    n.mode = 0;
                    n.userAcl.clear();
                    n.groupAcl.clear();
                    quarantinedNodes.add(n);
                    isolated = true;
                }
                if (status == Status.TAMPERED) {
                    fs.audit.record(CAT_INTEGRITY, user, mode,
                            "adulteração física de blocos detectada em '" + n.path() + "'"
                                    + (quarantine ? " (isolado em quarentena)" : ""));
                } else {
                    fs.audit.record(CAT_INTEGRITY, user, mode,
                            "assinatura maliciosa '" + signature + "' detectada em '" + n.path() + "'"
                                    + (quarantine ? " (isolado em quarentena)" : ""));
                }
            }
            findings.add(new FileFinding(n, status, signature, isolated));
        }

        fs.audit.record(CAT_INTEGRITY, user, mode,
                "varredura concluída em '" + start.path() + "': "
                        + findings.size() + " arquivo(s), " + alerts + " alerta(s)");
        return new ScanReport(List.copyOf(findings), findings.size(), alerts);
    }

    /** Simula corrupção ou adulteração direta em um bloco lógico do arranjo sem passar pelo sistema de arquivos. */
    void tamperBlock(Disk disk, int logicalBlock, byte[] payload, AuthManager auth, AuditLog audit) {
        auth.requireKernel("tamper_block");
        if (logicalBlock < 0 || logicalBlock >= disk.numBlocks) {
            throw new FSException("bloco inválido: " + logicalBlock + " (use 0 a " + (disk.numBlocks - 1) + ")");
        }
        byte[] cur = disk.peek(logicalBlock);
        byte[] raw = Arrays.copyOf(cur, disk.blockSize);
        if (payload == null || payload.length == 0) {
            raw[0] ^= (byte) 0xFF;
        } else {
            int len = Math.min(payload.length, raw.length);
            System.arraycopy(payload, 0, raw, 0, len);
            if (Arrays.equals(cur, raw)) {
                raw[0] ^= (byte) 0xFF;
            }
        }
        // grava direto no arranjo sem atualizar hashes oficiais nem enfileirar E/S normal
        disk.array.write(logicalBlock, raw, false);
        if (audit != null) {
            audit.record(CAT_INTEGRITY, auth.currentUser().name(), auth.mode().name(),
                    "adulteração física simulada no bloco lógico " + logicalBlock);
        }
    }

    // ------------------------------------------------------------- internos

    private static String detectSignature(String text) {
        for (Map.Entry<String, String> entry : SIGNATURES.entrySet()) {
            if (text.contains(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("algoritmo SHA-256 indisponível", e);
        }
    }
}
