package fsim;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Testes da Etapa 3: autenticação, modos dual, matriz/ACL POSIX, criptografia, integridade, auditoria e demos. */
public final class SecurityTest {
    private SecurityTest() {
    }

    public static void main(String[] args) {
        passwordHashingUsesSaltAndVerifies();
        kernelVsUserModePrivileges();
        aclMatrixExample31AndExtendedAcl();
        directoryTraversalAndCrudAtomicity();
        encryptionAtRestExample32AndRaid5();
        antivirusDetectsBlockTamperingAndMalware();
        defragPreservesSecurityAndIntegrity();
        allDemosRunCleanly();
        System.out.println("Todos os testes da Etapa 3 passaram.");
    }

    /** Verifica salt aleatório de 16 bytes, hash SHA-256 distinto para senhas iguais, login, passwd e logout. */
    static void passwordHashingUsesSaltAndVerifies() {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);
        check(fs.auth.currentUser().name().equals("root"), "sessão inicial deve ser root");
        check(fs.auth.mode() == AuthManager.Mode.MODO_KERNEL, "sessão inicial deve iniciar em MODO_KERNEL");

        AuthManager.User alice = fs.auth.addUser("alice", "MesmaSenha!42", "devs", false);
        AuthManager.User bruno = fs.auth.addUser("bruno", "MesmaSenha!42", "devs", false);
        check(alice.salt().length == 16 && bruno.salt().length == 16, "salt deve ter exatamente 16 bytes");
        check(alice.passwordHash().length == 32 && bruno.passwordHash().length == 32, "hash SHA-256 deve ter 32 bytes");
        check(!Arrays.equals(alice.salt(), bruno.salt()), "usuários distintos devem receber salts diferentes");
        check(!Arrays.equals(alice.passwordHash(), bruno.passwordHash()),
                "hashes SHA-256 devem diferir mesmo para senhas idênticas");
        check(!alice.hashHex().equals(bruno.hashHex()), "representação hexadecimal dos hashes deve diferir");

        fs.auth.login("alice", "MesmaSenha!42");
        check(fs.auth.currentUser().name().equals("alice"), "usuário autenticado deve ser alice");
        check(fs.auth.mode() == AuthManager.Mode.MODO_USUARIO && !fs.auth.isKernel(),
                "usuário comum deve operar em MODO_USUARIO");

        int auditBeforeFail = fs.audit.entries().size();
        try {
            fs.auth.login("alice", "SenhaIncorreta");
            throw new AssertionError("senha incorreta deveria lançar FSException");
        } catch (FSException expected) {
            // esperado
        }
        List<AuditLog.Entry> afterFail = fs.audit.entries();
        check(afterFail.size() == auditBeforeFail + 1, "falha de login deve gerar entrada no AuditLog");
        AuditLog.Entry failEntry = afterFail.get(auditBeforeFail);
        check(failEntry.category().equals("AUTENTICACAO"),
                "categoria esperada AUTENTICACAO, obtida " + failEntry.category());

        byte[] oldSalt = fs.auth.currentUser().salt();
        byte[] oldHash = fs.auth.currentUser().passwordHash();
        fs.auth.changePassword("alice", "NovaSenhaSegura#99");
        AuthManager.User updated = fs.auth.getUser("alice");
        check(!Arrays.equals(oldSalt, updated.salt()), "changePassword deve gerar um novo salt");
        check(!Arrays.equals(oldHash, updated.passwordHash()), "changePassword deve recalcular o hash");
        check(!fs.auth.verifyPassword(updated, "MesmaSenha!42"), "senha antiga deve ser invalidada");
        check(fs.auth.verifyPassword(updated, "NovaSenhaSegura#99"), "nova senha deve ser válida");

        try {
            fs.auth.login("alice", "MesmaSenha!42");
            throw new AssertionError("login com senha antiga não deve ser aceito");
        } catch (FSException expected) {
            // esperado
        }
        fs.auth.login("alice", "NovaSenhaSegura#99");
        check(fs.auth.currentUser().name().equals("alice"), "login com nova senha deve funcionar");

        fs.auth.logout();
        check(fs.auth.currentUser().name().equals("root"), "logout deve restaurar o usuário root");
        check(fs.auth.mode() == AuthManager.Mode.MODO_KERNEL && fs.auth.isKernel(),
                "logout deve restaurar MODO_KERNEL");
    }

    /** Verifica separação entre MODO_USUARIO e MODO_KERNEL, bloqueio de chamadas privilegiadas e elevação via sudo. */
    static void kernelVsUserModePrivileges() {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID1, 2);
        fs.mkdir("/dados", false);
        fs.create("/dados/arq.txt", "conteudo".getBytes(StandardCharsets.UTF_8));
        fs.auth.addGroup("devs");
        fs.auth.addUser("carlos", "segredo123", "devs", false);
        fs.auth.login("carlos", "segredo123");
        check(fs.auth.mode() == AuthManager.Mode.MODO_USUARIO, "carlos deve estar em MODO_USUARIO");

        expectPrivilegeDenied(fs, () -> fs.format(4096, 16, "indexada", "first", RaidArray.Level.RAID1, 2), "format");
        expectPrivilegeDenied(fs, fs::defrag, "defrag");
        expectPrivilegeDenied(fs, () -> fs.auth.addUser("outro", "1234", "devs", false), "useradd");
        expectPrivilegeDenied(fs, () -> fs.auth.addGroup("qa"), "groupadd");
        expectPrivilegeDenied(fs, () -> fs.chown("/dados/arq.txt", "carlos", "devs"), "chown");
        expectPrivilegeDenied(fs, () -> fs.tamperBlock(4, new byte[] {1, 2, 3}), "tamper_block");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        Shell shell = new Shell(fs, false, out);

        expectShellPrivilegeDenied(fs, shell, buf, "fail 1", "fail");
        check(!fs.disk.array.disks[0].failed, "disco 1 não deve falhar quando fail é negado");
        expectShellPrivilegeDenied(fs, shell, buf, "rebuild 1", "rebuild");
        expectShellPrivilegeDenied(fs, shell, buf, "iosched SSTF", "iosched");
        expectShellPrivilegeDenied(fs, shell, buf, "audit_log", "audit_log");

        buf.reset();
        shell.execute("sudo --password root groupadd qa", false);
        String sudoOut = buf.toString(StandardCharsets.UTF_8);
        check(sudoOut.contains("Grupo 'qa' criado."), "sudo deve executar groupadd com sucesso: " + sudoOut);
        check(fs.auth.groupExists("qa"), "grupo 'qa' deve ter sido cadastrado via sudo");
        check(fs.auth.currentUser().name().equals("carlos"), "sessão deve permanecer como 'carlos' após sudo");
        check(fs.auth.mode() == AuthManager.Mode.MODO_USUARIO && !fs.auth.isKernel(),
                "sessão deve retornar imediatamente a MODO_USUARIO após sudo");

        buf.reset();
        shell.execute("sudo --password senha_errada groupadd sec", false);
        String badSudo = buf.toString(StandardCharsets.UTF_8);
        check(badSudo.contains("Erro: sudo: senha de elevação incorreta"),
                "sudo com senha incorreta deve falhar: " + badSudo);
        check(!fs.auth.groupExists("sec"), "grupo não deve ser criado quando sudo falha");
        check(fs.auth.mode() == AuthManager.Mode.MODO_USUARIO, "modo deve continuar MODO_USUARIO");
    }

    /** Exemplo 3.1 do enunciado: matriz POSIX, bloqueio sem gerar E/S, ACL estendida, chmod simbólico e exec. */
    static void aclMatrixExample31AndExtendedAcl() {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        Shell shell = new Shell(fs, false, out);

        shell.execute("mkdir /sistema", false);
        shell.execute("create /sistema/config.sys --content \"kernel_mode=strict\\naudit=enabled\"", false);
        shell.execute("chmod 750 /sistema/config.sys", false);
        shell.execute("groupadd devs", false);
        shell.execute("useradd carlos --group devs --password segredo123", false);
        shell.execute("mkdir -p /home/carlos", false);
        shell.execute("chown carlos:devs /home/carlos", false);
        shell.execute("chmod 700 /home/carlos", false);

        fs.disk.array.resetQueues();
        buf.reset();
        shell.execute("login --user carlos --password segredo123", false);
        String loginOut = buf.toString(StandardCharsets.UTF_8);
        check(loginOut.contains("Autenticado com sucesso. Usuário: 'carlos' | Grupo: 'devs' | Modo: MODO_USUARIO"),
                "saída de login divergente: " + loginOut);

        buf.reset();
        shell.execute("read_file /sistema/config.sys", false);
        String aclOut = buf.toString(StandardCharsets.UTF_8);
        check(aclOut.contains("[SISTEMA DE SEGURANÇA - VERIFICAÇÃO DE ACL]"), "cabeçalho ACL ausente");
        check(aclOut.contains("Objeto      : /sistema/config.sys"), "campo Objeto ausente: " + aclOut);
        check(aclOut.contains("Proprietário: root (Grupo: admin)"), "campo Proprietário ausente: " + aclOut);
        check(aclOut.contains("Permissões  : rwxr-x--- (750)"), "campo Permissões ausente: " + aclOut);
        check(aclOut.contains("Ação Pedida : LEITURA por 'carlos' (Grupo: devs)"), "campo Ação Pedida ausente");
        check(aclOut.contains(
                "[NEGADO]: O usuário 'carlos' não possui permissão de leitura no objeto '/sistema/config.sys'."),
                "mensagem [NEGADO] ausente: " + aclOut);
        check(aclOut.contains("Tentativa não autorizada registrada nos logs do sistema."),
                "aviso de registro nos logs ausente: " + aclOut);
        check(fs.disk.array.disks[0].queue.isEmpty(),
                "leitura negada pela ACL não deve enfileirar requisições de E/S no disco físico");

        buf.reset();
        shell.execute("sudo --password root setfacl -m u:carlos:r-- /sistema/config.sys", false);
        Node cfg = fs.resolve("/sistema/config.sys", false);
        check(AccessControl.formatPermissions(cfg).equals("rwxr-x---+ (750)"),
                "permissão com ACL estendida deve exibir '+': " + AccessControl.formatPermissions(cfg));

        buf.reset();
        shell.execute("read_file /sistema/config.sys", false);
        String allowedOut = buf.toString(StandardCharsets.UTF_8);
        check(allowedOut.contains("kernel_mode=strict") && allowedOut.contains("audit=enabled"),
                "carlos deve conseguir ler após concessão via setfacl: " + allowedOut);
        check(!fs.disk.array.disks[0].queue.isEmpty(), "leitura autorizada deve gerar E/S no disco físico");

        shell.execute("sudo --password root setfacl -x u:carlos /sistema/config.sys", false);
        check(AccessControl.formatPermissions(cfg).equals("rwxr-x--- (750)"),
                "remoção da entrada ACL deve retirar o sufixo '+'");
        try {
            fs.read("/sistema/config.sys");
            throw new AssertionError("leitura deve voltar a falhar após revogação da ACL");
        } catch (AccessDeniedException expected) {
            // esperado
        }

        shell.execute("sudo --password root chmod 600 /sistema/config.sys", false);
        check(cfg.mode == 0600, "modo deve mudar para 600");
        shell.execute("sudo --password root chmod u=rwx,g=rx,o= /sistema/config.sys", false);
        check(cfg.mode == 0750 && AccessControl.formatPermissions(cfg).equals("rwxr-x--- (750)"),
                "chmod simbólico u=rwx,g=rx,o= deve resultar em 750");

        shell.execute("create /home/carlos/run.sh --content \"echo pronto\"", false);
        try {
            fs.execute("/home/carlos/run.sh");
            throw new AssertionError("arquivo sem permissão 'x' não deve executar");
        } catch (AccessDeniedException expected) {
            check(expected.actionLabel.equals("EXECUÇÃO"), "ação negada deve ser EXECUÇÃO");
        }
        shell.execute("chmod +x /home/carlos/run.sh", false);
        check(fs.resolve("/home/carlos/run.sh").mode == 0755, "chmod +x sobre 0644 deve resultar em 0755");
        buf.reset();
        shell.execute("exec /home/carlos/run.sh", false);
        String execOut = buf.toString(StandardCharsets.UTF_8);
        check(execOut.contains("Execução concluída: '/home/carlos/run.sh'"), "exec deve concluir após +x: " + execOut);
    }

    /** Travessia de diretórios ('x') e atomicidade de operações CRUD ('w') sem deixar blocos ou i-nodes órfãos. */
    static void directoryTraversalAndCrudAtomicity() {
        FileSystem fs = new FileSystem(4096, 32, "indexada", "first", RaidArray.Level.RAID0, 1);
        fs.mkdir("/secreto", false);
        fs.create("/secreto/interno.txt", "dados internos".getBytes(StandardCharsets.UTF_8));
        fs.chmod("/secreto", "744");

        fs.mkdir("/somente_leitura", false);
        fs.create("/somente_leitura/fixo.txt", "imutavel".getBytes(StandardCharsets.UTF_8));
        fs.chmod("/somente_leitura", "755");

        fs.mkdir("/publico", false);
        fs.chmod("/publico", "777");
        fs.create("/publico/origem.txt", "movel".getBytes(StandardCharsets.UTF_8));

        fs.auth.addUser("carlos", "123456", "users", false);
        fs.auth.login("carlos", "123456");

        try {
            fs.cd("/secreto");
            throw new AssertionError("cd em diretório sem 'x' deve falhar");
        } catch (AccessDeniedException expected) {
            check(expected.actionLabel.equals("EXECUÇÃO"), "cd deve exigir permissão de execução no diretório");
        }
        check(fs.cwd == fs.root, "cwd não deve mudar quando cd falha");

        try {
            fs.resolve("/secreto/interno.txt");
            throw new AssertionError("resolução de caminho interno sem 'x' no diretório pai deve falhar");
        } catch (AccessDeniedException expected) {
            // esperado
        }

        int freeBefore = fs.disk.freeCount();

        expectAccessDenied(() -> fs.create("/somente_leitura/novo.txt", "abc".getBytes(StandardCharsets.UTF_8)));
        check(fs.disk.freeCount() == freeBefore, "create negado não pode consumir i-node nem blocos de dados");
        check(!fs.exists("/somente_leitura/novo.txt"), "arquivo não deve existir após create negado");

        expectAccessDenied(() -> fs.create("/somente_leitura/grande.bin", 8192));
        check(fs.disk.freeCount() == freeBefore, "create por tamanho negado não pode consumir blocos");

        expectAccessDenied(() -> fs.mkdir("/somente_leitura/subdir", false));
        check(fs.disk.freeCount() == freeBefore, "mkdir negado não pode alocar i-node");
        check(!fs.exists("/somente_leitura/subdir"), "diretório não deve existir após mkdir negado");

        expectAccessDenied(() -> fs.mkdir("/somente_leitura/a/b", true));
        check(fs.disk.freeCount() == freeBefore, "mkdir -p negado não pode alocar i-nodes órfãos");

        expectAccessDenied(() -> fs.remove("/somente_leitura/fixo.txt", false));
        check(fs.disk.freeCount() == freeBefore, "rm negado não pode liberar nem alocar blocos");
        check(fs.exists("/somente_leitura/fixo.txt"), "arquivo protegido deve permanecer intacto após rm negado");

        expectAccessDenied(() -> fs.move("/somente_leitura/fixo.txt", "/publico/copia.txt"));
        check(fs.disk.freeCount() == freeBefore, "mv negado na origem não deve alterar contagem de blocos livres");
        check(fs.exists("/somente_leitura/fixo.txt") && !fs.exists("/publico/copia.txt"),
                "mv negado na origem deve ser atômico");

        expectAccessDenied(() -> fs.move("/publico/origem.txt", "/somente_leitura/origem.txt"));
        check(fs.disk.freeCount() == freeBefore, "mv negado no destino não deve alterar contagem de blocos livres");
        check(fs.exists("/publico/origem.txt") && !fs.exists("/somente_leitura/origem.txt"),
                "mv negado no destino deve ser atômico");
    }

    /** Exemplo 3.2 do enunciado: criptografia AES-256-CTR + HMAC-SHA256 em repouso e integração com RAID 5 degradado. */
    static void encryptionAtRestExample32AndRaid5() {
        FileSystem fs = new FileSystem(4096, 32, "indexada", "first", RaidArray.Level.RAID0, 1);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        Shell shell = new Shell(fs, false, out);

        shell.execute("groupadd devs", false);
        shell.execute("useradd carlos --group devs --password segredo123", false);
        shell.execute("mkdir -p /home/carlos", false);
        shell.execute("chown carlos:devs /home/carlos", false);
        shell.execute("chmod 700 /home/carlos", false);
        shell.execute("login --user carlos --password segredo123", false);

        String secret = "Segredo confidencial do projeto: lançamento da arquitetura segura!";
        Node node = fs.create("/home/carlos/segredo.txt", secret.getBytes(StandardCharsets.UTF_8));
        int sizeBefore = node.size;
        List<Integer> blocksBefore = fs.alloc.dataBlocks(node);
        check(new String(fs.disk.peek(blocksBefore.get(0)), StandardCharsets.UTF_8).contains("Segredo confidencial"),
                "antes de encrypt_file o texto claro deve estar gravado no bloco físico");

        buf.reset();
        shell.execute("encrypt_file /home/carlos/segredo.txt --key \"MinhaChave123\"", false);
        String encOut = buf.toString(StandardCharsets.UTF_8);
        check(encOut.contains("Arquivo '/home/carlos/segredo.txt' criptografado com sucesso."),
                "mensagem de sucesso de encrypt_file ausente: " + encOut);
        check(encOut.contains("Conteúdo gravado nos blocos físicos: \"0x")
                && encOut.contains("(Encrypted Ciphertext)\""),
                "amostra hexadecimal do ciphertext ausente: " + encOut);

        List<Integer> blocksAfter = fs.alloc.dataBlocks(node);
        check(node.size == sizeBefore, "AES-CTR sem padding deve manter exatamente o tamanho do arquivo");
        check(blocksAfter.size() == blocksBefore.size(), "número de blocos alocados não deve mudar");
        check(node.crypto != null, "metadados de criptografia devem estar registrados no Node");

        byte[] rawCipher = fs.disk.peek(blocksAfter.get(0));
        check(!new String(rawCipher, StandardCharsets.UTF_8).contains("Segredo confidencial"),
                "texto original não pode aparecer em claro nos blocos físicos");

        byte[] cipherSnapshot = rawCipher.clone();
        try {
            fs.read("/home/carlos/segredo.txt", "ChaveIncorreta");
            throw new AssertionError("read com chave incorreta deve falhar");
        } catch (FSException expected) {
            // esperado
        }
        try {
            fs.decryptFile("/home/carlos/segredo.txt", "ChaveIncorreta");
            throw new AssertionError("decryptFile com chave incorreta deve falhar");
        } catch (FSException expected) {
            // esperado
        }
        check(node.crypto != null && Arrays.equals(fs.disk.peek(blocksAfter.get(0)), cipherSnapshot),
                "tentativas com chave incorreta não podem corromper o bloco cifrado no disco");

        String clearInMemory = new String(fs.read("/home/carlos/segredo.txt", "MinhaChave123"), StandardCharsets.UTF_8);
        check(clearInMemory.equals(secret), "read com chave correta deve devolver texto claro em memória");
        check(node.crypto != null && Arrays.equals(fs.disk.peek(blocksAfter.get(0)), cipherSnapshot),
                "read com chave não deve remover a criptografia em repouso dos blocos físicos");

        fs.decryptFile("/home/carlos/segredo.txt", "MinhaChave123");
        check(node.crypto == null, "decryptFile deve limpar metadados de criptografia");
        byte[] rawRestored = Arrays.copyOf(fs.disk.peek(fs.alloc.dataBlocks(node).get(0)), node.size);
        check(new String(rawRestored, StandardCharsets.UTF_8).equals(secret),
                "decryptFile deve restaurar o texto plano nos blocos físicos");

        // Integração com a Etapa 2: arquivo criptografado em RAID 5 (3 discos) sobrevive à falha do Disco 2
        FileSystem raidFs = new FileSystem(512, 24, "indexada", "first", RaidArray.Level.RAID5, 3);
        String raidSecret = "registro-criptografado-raid5;".repeat(60);
        raidFs.create("/cofre.dat", raidSecret.getBytes(StandardCharsets.UTF_8));
        raidFs.encryptFile("/cofre.dat", "ChaveRaid5#2026");
        raidFs.disk.array.fail(1); // falha no Disco 2 (índice 0-based = 1)
        check(raidFs.disk.array.disks[1].failed, "Disco 2 deve estar marcado como falho");
        raidFs.decryptFile("/cofre.dat", "ChaveRaid5#2026");
        String recovered = new String(raidFs.read("/cofre.dat"), StandardCharsets.UTF_8);
        check(recovered.equals(raidSecret),
                "decryptFile em RAID 5 degradado deve reconstruir os blocos cifrados por XOR e restaurar o texto");
    }

    /** Exemplo 3.2: detecção de adulteração física (SHA-256), assinatura EICAR e isolamento em quarentena. */
    static void antivirusDetectsBlockTamperingAndMalware() {
        FileSystem fs = new FileSystem(4096, 32, "indexada", "first", RaidArray.Level.RAID0, 1);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        Shell shell = new Shell(fs, false, out);

        fs.mkdir("/sistema", false);
        fs.mkdir("/sistema/logs", false);
        Node appLog = fs.create("/sistema/logs/app.log",
                "2026-11-03 INFO Inicializacao concluida".getBytes(StandardCharsets.UTF_8));
        fs.chmod("/sistema/logs/app.log", "755");

        fs.mkdir("/home", false);
        fs.mkdir("/home/carlos", false);
        Node segredo = fs.create("/home/carlos/segredo.txt",
                "Segredo integro protegido".getBytes(StandardCharsets.UTF_8));
        fs.encryptFile("/home/carlos/segredo.txt", "MinhaChave123");

        Node malware = fs.create("/home/carlos/eicar.com",
                "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                        .getBytes(StandardCharsets.UTF_8));
        fs.chmod("/home/carlos/eicar.com", "755");

        int logDataBlock = fs.alloc.dataBlocks(appLog).get(0);
        fs.tamperBlock(logDataBlock, "ADULTERACAO_DIRETA".getBytes(StandardCharsets.UTF_8));

        buf.reset();
        shell.execute("antivirus_scan /", false);
        String scanOut = buf.toString(StandardCharsets.UTF_8);
        check(scanOut.contains("[MÓDULO DE SEGURANÇA - VARREDURA DE INTEGRIDADE]"),
                "cabeçalho da varredura ausente: " + scanOut);
        check(hasLineWith(scanOut, "/home/carlos/segredo.txt", "[OK - Criptografado e Íntegro]"),
                "segredo.txt deve aparecer como [OK - Criptografado e Íntegro]: " + scanOut);
        check(hasLineWith(scanOut, "/sistema/logs/app.log", "[ALERTA] Hash do bloco difere do registro oficial!"),
                "app.log deve reportar [ALERTA] de hash divergente: " + scanOut);
        check(scanOut.contains("Resultado: Possível modificação direta não autorizada no bloco do disco."),
                "diagnóstico de adulteração física ausente: " + scanOut);
        check(hasLineWith(scanOut, "/home/carlos/eicar.com",
                "[AMEAÇA] Assinatura maliciosa detectada: 'EICAR-Test-Signature'!"),
                "arquivo com EICAR deve reportar [AMEAÇA] EICAR-Test-Signature: " + scanOut);
        check(!fs.integrity.isQuarantined(appLog) && !fs.integrity.isQuarantined(malware),
                "sem --quarantine os arquivos ainda não devem estar em quarentena");

        buf.reset();
        shell.execute("antivirus_scan / --quarantine", false);
        String quarOut = buf.toString(StandardCharsets.UTF_8);
        check(quarOut.contains("Ação     : Arquivo isolado em quarentena (permissões 000)."),
                "relatório deve indicar isolamento em quarentena: " + quarOut);
        check(appLog.mode == 0 && fs.integrity.isQuarantined(appLog),
                "app.log adulterado deve ficar com mode == 0 e em quarentena");
        check(malware.mode == 0 && fs.integrity.isQuarantined(malware),
                "arquivo malicioso deve ficar com mode == 0 e em quarentena");
        check(!fs.integrity.isQuarantined(segredo) && segredo.mode == Node.DEFAULT_FILE_MODE,
                "arquivo íntegro não deve entrar em quarentena");

        for (String path : List.of("/sistema/logs/app.log", "/home/carlos/eicar.com")) {
            expectQuarantineBlocked(() -> fs.read(path), "leitura em " + path);
            expectQuarantineBlocked(() -> fs.write(path, "x".getBytes(StandardCharsets.UTF_8), false),
                    "escrita em " + path);
            expectQuarantineBlocked(() -> fs.execute(path), "execução em " + path);
        }

        fs.auth.addUser("carlos", "123456", "users", false);
        fs.auth.login("carlos", "123456");
        for (String path : List.of("/sistema/logs/app.log", "/home/carlos/eicar.com")) {
            expectAccessDenied(() -> fs.read(path));
            expectAccessDenied(() -> fs.write(path, "x".getBytes(StandardCharsets.UTF_8), false));
            expectAccessDenied(() -> fs.execute(path));
        }
    }

    /** Desfragmentação na alocação contígua atualiza hashes oficiais e preserva permissões, ACLs e criptografia. */
    static void defragPreservesSecurityAndIntegrity() {
        FileSystem fs = new FileSystem(1024, 16, "contigua", "first", RaidArray.Level.RAID0, 1);
        fs.auth.addGroup("devs");
        fs.auth.addUser("carlos", "123456", "devs", false);
        fs.auth.addUser("ana", "123456", "devs", false);

        fs.create("/base.bin", new byte[2048]);
        fs.create("/lacuna1.tmp", new byte[2048]);

        String secretText = "Relatório criptografado realocado pela compactação! ".repeat(25);
        Node cofre = fs.create("/cofre.txt", secretText.getBytes(StandardCharsets.UTF_8));
        fs.chown("/cofre.txt", "carlos", "devs");
        fs.chmod("/cofre.txt", "640");
        fs.setAcl("/cofre.txt", "u:ana:r--");
        fs.encryptFile("/cofre.txt", "ChaveDefrag!77");

        fs.create("/lacuna2.tmp", new byte[2048]);
        fs.create("/rodape.txt", "dados finais integros".getBytes(StandardCharsets.UTF_8));

        fs.remove("/lacuna1.tmp", false);
        fs.remove("/lacuna2.tmp", false);
        check(fs.alloc.externalReport().needsCompaction(), "disco deve estar fragmentado antes do defrag");
        List<Integer> blocksBefore = fs.alloc.dataBlocks(cofre);

        int moved = fs.defrag();
        check(moved > 0, "defrag deve mover blocos para eliminar lacunas");
        check(!blocksBefore.equals(fs.alloc.dataBlocks(cofre)), "blocos de /cofre.txt devem mudar após defrag");
        check(!fs.alloc.externalReport().needsCompaction(), "defrag deve eliminar fragmentação externa");

        IntegrityMonitor.ScanReport scan = fs.scanIntegrity("/", false);
        check(scan.totalFiles() == 3, "devem restar 3 arquivos após defrag");
        check(scan.alerts() == 0, "scanIntegrity após defrag deve ter 0 alertas, obteve " + scan.alerts());

        check(cofre.owner.equals("carlos") && cofre.group.equals("devs"), "dono e grupo devem ser preservados");
        check(cofre.mode == 0640, "modo 0640 deve ser preservado");
        check(cofre.userAcl.getOrDefault("ana", -1) == 4, "ACL u:ana:r-- deve ser preservada");

        fs.auth.login("ana", "123456");
        String anaRead = new String(fs.read("/cofre.txt", "ChaveDefrag!77"), StandardCharsets.UTF_8);
        check(anaRead.equals(secretText), "ana deve ler /cofre.txt pela ACL estendida após defrag");

        fs.auth.login("carlos", "123456");
        fs.decryptFile("/cofre.txt", "ChaveDefrag!77");
        String restored = new String(fs.read("/cofre.txt"), StandardCharsets.UTF_8);
        check(restored.equals(secretText), "decryptFile após defrag deve recuperar o texto original intacto");
    }

    /** Executa todas as demos do Shell garantindo zero regressão nas Etapas 1 e 2 e cobertura completa da Etapa 3. */
    static void allDemosRunCleanly() {
        DemoRun indexada = runDemo("indexada");
        check(!indexada.output().contains("Erro:"), "demo indexada não deve produzir erros: " + indexada.output());
        check(indexada.output().contains("i-node no bloco 03 → índice [04, 05, 06]"),
                "app.log na demo indexada deve continuar em 'i-node no bloco 03 → índice [04, 05, 06]'");
        Node appLog = indexada.fs().resolve("/sistema/logs/app.log");
        check(indexada.fs().alloc.describe(appLog).equals("i-node no bloco 03 → índice [04, 05, 06]"),
                "descrição de alocação de app.log sofreu regressão: " + indexada.fs().alloc.describe(appLog));

        DemoRun contigua = runDemo("contigua");
        check(contigua.output().contains("Compactação concluída:"),
                "demo contigua deve concluir compactação: " + contigua.output());
        check(contigua.fs().exists("/home/user/grande.bin"),
                "demo contigua deve alocar grande.bin após defrag");

        DemoRun encadeada = runDemo("encadeada");
        check(!encadeada.output().contains("Erro:"), "demo encadeada não deve produzir erros: " + encadeada.output());
        check(encadeada.output().contains("[MAPA DE ALOCAÇÃO DO DISCO (Encadeado / FAT)]"),
                "demo encadeada deve renderizar mapa FAT");

        DemoRun raid5 = runDemo("raid5");
        check(!raid5.output().contains("Erro:"), "demo raid5 não deve produzir erros: " + raid5.output());
        check(raid5.output().contains("Disco 2 reconstruído:"), "demo raid5 deve reconstruir o Disco 2");

        DemoRun seguranca = runDemo("seguranca");
        check(!seguranca.output().contains("Erro:"), "demo seguranca não deve produzir erros: " + seguranca.output());
        check(seguranca.output().contains("[SISTEMA DE SEGURANÇA - VERIFICAÇÃO DE ACL]"),
                "demo seguranca deve exibir o painel de verificação de ACL");
        check(seguranca.output().contains("Arquivo '/home/carlos/segredo.txt' criptografado com sucesso."),
                "demo seguranca deve exibir a criptografia em repouso");
        check(seguranca.output().contains("[MÓDULO DE SEGURANÇA - VARREDURA DE INTEGRIDADE]"),
                "demo seguranca deve exibir a varredura de integridade");
        check(seguranca.output().contains("[OK - Criptografado e Íntegro]")
                && seguranca.output().contains("[ALERTA] Hash do bloco difere do registro oficial!")
                && seguranca.output().contains("[AMEAÇA] Assinatura maliciosa detectada: 'EICAR-Test-Signature'!"),
                "demo seguranca deve exibir todos os diagnósticos de integridade e antivírus");
        check(seguranca.output().contains("[LOG DE AUDITORIA DE SEGURANÇA]"),
                "demo seguranca deve exibir o log de auditoria");
    }

    // -------------------------------------------------------------- auxiliares
    private record DemoRun(FileSystem fs, String output) {
    }

    private static DemoRun runDemo(String name) {
        FileSystem fs = new FileSystem(4096, 16, "indexada", "first", RaidArray.Level.RAID0, 1);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        Shell shell = new Shell(fs, false, out);
        shell.execute("demo " + name, false);
        return new DemoRun(fs, buf.toString(StandardCharsets.UTF_8));
    }

    private static void expectPrivilegeDenied(FileSystem fs, Runnable action, String operation) {
        int before = fs.audit.entries().size();
        try {
            action.run();
            throw new AssertionError("operação '" + operation + "' deveria falhar em MODO_USUARIO");
        } catch (FSException expected) {
            // esperado
        }
        List<AuditLog.Entry> entries = fs.audit.entries();
        check(entries.size() == before + 1, "bloqueio de '" + operation + "' deve gravar entrada no AuditLog");
        AuditLog.Entry last = entries.get(before);
        check(last.category().equals("PRIVILEGIO"),
                "categoria esperada PRIVILEGIO para '" + operation + "', obtida " + last.category());
        check(last.detail().contains(operation), "detalhe da auditoria deve citar '" + operation + "'");
    }

    private static void expectShellPrivilegeDenied(FileSystem fs, Shell shell, ByteArrayOutputStream buf,
            String command, String operation) {
        int before = fs.audit.entries().size();
        buf.reset();
        shell.execute(command, false);
        String output = buf.toString(StandardCharsets.UTF_8);
        check(output.contains("Erro: operação privilegiada: '" + operation + "' exige MODO_KERNEL"),
                "comando '" + command + "' deveria ser bloqueado em MODO_USUARIO: " + output);
        List<AuditLog.Entry> entries = fs.audit.entries();
        check(entries.size() == before + 1 && entries.get(before).category().equals("PRIVILEGIO"),
                "bloqueio de '" + command + "' no Shell deve registrar PRIVILEGIO no AuditLog");
    }

    private static void expectAccessDenied(Runnable action) {
        try {
            action.run();
            throw new AssertionError("operação sem permissão deveria lançar AccessDeniedException");
        } catch (AccessDeniedException expected) {
            // esperado
        }
    }

    private static void expectQuarantineBlocked(Runnable action, String description) {
        try {
            action.run();
            throw new AssertionError(description + " deveria ser bloqueada pela quarentena");
        } catch (FSException expected) {
            check(expected.getMessage().contains("quarentena"),
                    "mensagem deveria mencionar quarentena: " + expected.getMessage());
        }
    }

    private static boolean hasLineWith(String text, String firstToken, String secondToken) {
        for (String line : text.split("\\R")) {
            if (line.contains(firstToken) && line.contains(secondToken)) {
                return true;
            }
        }
        return false;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
