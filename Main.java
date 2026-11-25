import java.io.Console;
import java.io.IOException;
import java.util.Arrays;
import java.util.Scanner;

/** Shell do simulador: pede a senha, monta o disco e lê os comandos. */
public class Main {
    private static final int TENTATIVAS_DE_SENHA = 3;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        SistemaArquivos fs = new SistemaArquivos("disco_virtual.dat", "fs_journal.log");

        System.out.println("=== Simulador de Sistema de Arquivos (Digite 'help' para comandos) ===");
        if (!montarDisco(fs, scanner)) {
            System.exit(1);
        }

        boolean rodando = true;
        while (rodando) {
            System.out.print("user@zorin-sim:" + fs.getCaminhoAtual() + "$ ");
            if (!scanner.hasNextLine()) {   // Ctrl+D: sai igual ao exit
                System.out.println();
                break;
            }
            String linha = scanner.nextLine().trim();
            if (linha.isEmpty()) continue;

            String[] partes = linha.split("\\s+");
            String comando = partes[0].toLowerCase();

            switch (comando) {
                case "ls": {
                    boolean detalhado = false;
                    String caminho = null;
                    for (int i = 1; i < partes.length; i++) {
                        if (partes[i].equals("-l")) detalhado = true;
                        else caminho = partes[i];
                    }
                    fs.listar(caminho, detalhado);
                    break;
                }
                case "cd":
                    fs.mudarDiretorio(partes.length < 2 ? "/" : partes[1]);
                    break;
                case "pwd":
                    System.out.println(fs.getCaminhoAtual());
                    break;
                case "mkdir":
                    if (partes.length < 2) System.out.println("Uso: mkdir <caminho>");
                    else fs.criarDiretorio(partes[1]);
                    break;
                case "touch": {
                    // split com limite 3: tudo depois do caminho é o conteúdo, com os espaços
                    String[] p = linha.split("\\s+", 3);
                    if (p.length < 2) System.out.println("Uso: touch <caminho> [conteudo]");
                    else fs.criarArquivo(p[1], p.length > 2 ? p[2] : null);
                    break;
                }
                case "cat":
                    if (partes.length < 2) System.out.println("Uso: cat <caminho>");
                    else fs.mostrarConteudo(partes[1]);
                    break;
                case "cp":
                    if (partes.length < 3) System.out.println("Uso: cp <origem> <destino>");
                    else fs.copiarArquivo(partes[1], partes[2]);
                    break;
                case "mv":
                    if (partes.length < 3) System.out.println("Uso: mv <origem> <destino>");
                    else fs.renomear(partes[1], partes[2]);
                    break;
                case "rm":
                    if (partes.length < 2) System.out.println("Uso: rm <caminho>");
                    else fs.apagar(partes[1]);
                    break;
                case "stat":
                    if (partes.length < 2) System.out.println("Uso: stat <caminho>");
                    else fs.mostrarCabecalho(partes[1]);
                    break;
                case "info":
                    fs.mostrarInfoDisco();
                    break;
                case "help":
                    imprimirAjuda();
                    break;
                case "exit":
                    rodando = false;
                    break;
                default:
                    System.out.println("Comando não reconhecido: " + comando);
            }
        }

        System.out.println("Desmontando sistema de arquivos...");
        fs.desmontar();
        scanner.close();
    }

    private static void imprimirAjuda() {
        System.out.println("Caminhos podem ser absolutos (/docs/a.txt) ou relativos (docs/a.txt, ../a.txt).");
        System.out.println("  ls [-l] [caminho]           lista um diretório (-l mostra o cabeçalho de cada item)");
        System.out.println("  cd [caminho]                entra num diretório (sem caminho, volta para /)");
        System.out.println("  pwd                         mostra o caminho do diretório atual");
        System.out.println("  mkdir <caminho>             cria um diretório");
        System.out.println("  touch <caminho> [conteudo]  cria um arquivo (se já existir, atualiza a data/conteúdo)");
        System.out.println("  cat <caminho>               mostra o conteúdo de um arquivo");
        System.out.println("  cp <origem> <destino>       copia um arquivo");
        System.out.println("  mv <origem> <destino>       renomeia ou move um arquivo/diretório");
        System.out.println("  rm <caminho>                apaga um arquivo ou diretório (com tudo dentro)");
        System.out.println("  stat <caminho>              mostra o cabeçalho (atributos) de um item");
        System.out.println("  info                        mostra o cabeçalho do disco e o superbloco");
        System.out.println("  exit                        desmonta o disco e sai (Ctrl+D também)");
    }

    // ------------------------------------------------------------------
    // Senha e montagem
    // ------------------------------------------------------------------

    /** Monta o disco existente (pedindo a senha) ou formata um novo. Devolve false se não montou. */
    private static boolean montarDisco(SistemaArquivos fs, Scanner scanner) {
        if (System.console() == null) {
            System.out.println("(Aviso: este terminal não esconde a senha; ela vai aparecer enquanto você digita.)");
        }
        if (!fs.discoExiste()) {
            return formatarNovoDisco(fs, scanner);
        }

        // Confere o cabeçalho antes de pedir a senha (ex.: disco da versão antiga, sem criptografia)
        try {
            fs.verificarFormatoDoDisco();
        } catch (DiscoVirtual.FormatoInvalidoException e) {
            avisarFormatoInvalido(fs, e);
            return false;
        } catch (IOException e) {
            System.err.println("Erro ao ler o disco: " + e.getMessage());
            return false;
        }

        for (int tentativa = 1; tentativa <= TENTATIVAS_DE_SENHA; tentativa++) {
            char[] senha = lerSenha("Senha do disco: ", scanner);
            if (senha == null) return false;
            try {
                fs.montar(senha);
                return true;
            } catch (DiscoVirtual.SenhaIncorretaException e) {
                System.out.println("Senha incorreta (ou disco corrompido). Tentativas restantes: "
                        + (TENTATIVAS_DE_SENHA - tentativa));
            } catch (DiscoVirtual.FormatoInvalidoException e) {
                avisarFormatoInvalido(fs, e);
                return false;
            } catch (IOException e) {
                System.err.println("Erro ao ler o disco: " + e.getMessage());
                return false;
            } finally {
                Arrays.fill(senha, '\0');   // apaga a senha da memória
            }
        }
        System.out.println("Disco não montado. Nada foi alterado.");
        return false;
    }

    private static void avisarFormatoInvalido(SistemaArquivos fs, DiscoVirtual.FormatoInvalidoException e) {
        System.out.println("'" + fs.getArquivoDisco() + "' não é um disco deste simulador: " + e.getMessage() + ".");
        System.out.println("Se for um disco da versão antiga, apague ou renomeie o arquivo para criar um novo.");
        System.out.println("Nada foi alterado.");
    }

    private static boolean formatarNovoDisco(SistemaArquivos fs, Scanner scanner) {
        System.out.println("Nenhum disco encontrado em '" + fs.getArquivoDisco() + "'. Um novo disco será formatado.");
        for (int tentativa = 1; tentativa <= TENTATIVAS_DE_SENHA; tentativa++) {
            char[] senha = lerSenha("Crie uma senha para o disco: ", scanner);
            if (senha == null) return false;
            if (senha.length == 0) {
                System.out.println("A senha não pode ser vazia.");
                continue;
            }
            char[] confirmacao = lerSenha("Repita a senha: ", scanner);
            if (confirmacao == null) return false;
            try {
                if (!Arrays.equals(senha, confirmacao)) {
                    System.out.println("As senhas não conferem.");
                    continue;
                }
                fs.formatar(senha);
                System.out.println("Disco formatado e montado. Guarde a senha: sem ela o disco não abre.");
                return true;
            } catch (IOException e) {
                System.err.println("Erro ao criar o disco: " + e.getMessage());
                return false;
            } finally {
                Arrays.fill(senha, '\0');
                Arrays.fill(confirmacao, '\0');
            }
        }
        return false;
    }

    /** Lê a senha escondida quando o terminal permite; senão lê como texto normal. */
    private static char[] lerSenha(String mensagem, Scanner scanner) {
        Console console = System.console();
        if (console != null) {
            return console.readPassword(mensagem);
        }
        System.out.print(mensagem);
        return scanner.hasNextLine() ? scanner.nextLine().toCharArray() : null;
    }
}
