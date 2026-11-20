import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
    
        SistemaArquivos fs = new SistemaArquivos("disco_virtual.dat", "fs_journal.log");
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Simulador de Sistema de Arquivos (Digite 'help' para comandos) ===");

        while (true) {
            System.out.print("user@zorin-sim:" + fs.getCaminhoAtual() + "$ ");
            String linha = scanner.nextLine().trim();
            if (linha.isEmpty()) continue;

            String[] partes = linha.split("\\s+");
            String comando = partes[0].toLowerCase();

            switch (comando) {
                case "ls":
                    fs.listar();
                    break;
                case "mkdir":
                    if (partes.length < 2) System.out.println("Uso: mkdir <nome_diretorio>");
                    else fs.criarDiretorio(partes[1]);
                    break;
                case "touch":
                    if (partes.length < 2) System.out.println("Uso: touch <nome_arquivo> [conteudo]");
                    else {
                        String conteudo = partes.length > 2 ? linha.substring(linha.indexOf(partes[2])) : "";
                        fs.criarArquivo(partes[1], conteudo);
                    }
                    break;
                case "cp":
                    if (partes.length < 3) System.out.println("Uso: cp <origem> <destino>");
                    else fs.copiarArquivo(partes[1], partes[2]);
                    break;
                case "mv":
                    if (partes.length < 3) System.out.println("Uso: mv <nome_antigo> <nome_novo>");
                    else fs.renomear(partes[1], partes[2]);
                    break;
                case "rm":
                    if (partes.length < 2) System.out.println("Uso: rm <nome_arquivo_ou_dir>");
                    else fs.apagar(partes[1]);
                    break;
                case "cd":
                    if (partes.length < 2) System.out.println("Uso: cd <diretorio | .. | />");
                    else fs.mudarDiretorio(partes[1]);
                    break;
                case "help":
                    System.out.println("Comandos: ls, mkdir, touch, cp, mv, rm, cd, exit");
                    break;
                case "exit":
                    System.out.println("Desmontando sistema de arquivos...");
                    scanner.close();
                    return;
                default:
                    System.out.println("Comando não reconhecido: " + comando);
            }
        }
    }
}