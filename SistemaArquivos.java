import java.io.*;
import java.util.UUID;

public class SistemaArquivos {
    private DiretorioVirtual raiz;
    private DiretorioVirtual diretorioAtual;
    private final JournalManager journal;
    private final String arquivoDisco;

    public SistemaArquivos(String arquivoDisco, String arquivoJournal) {
        this.arquivoDisco = arquivoDisco;
        this.journal = new JournalManager(arquivoJournal);
        this.journal.verificarIntegridade();
        carregarDisco();
    }

    private void carregarDisco() {
        File file = new File(arquivoDisco);
        if (file.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                this.raiz = (DiretorioVirtual) ois.readObject();
                this.diretorioAtual = this.raiz;
                System.out.println("Sistema de arquivos montado a partir de " + arquivoDisco);
                return;
            } catch (Exception e) {
                System.err.println("Disco corrompido ou ilegível. Formatando novo disco...");
            }
        }
        this.raiz = new DiretorioVirtual("/", null);
        this.diretorioAtual = this.raiz;
        salvarDisco();
    }

    private void salvarDisco() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(arquivoDisco))) {
            oos.writeObject(this.raiz);
        } catch (IOException e) {
            System.err.println("Erro ao persistir disco virtual: " + e.getMessage());
        }
    }


    public void criarDiretorio(String nome) {
        if (diretorioAtual.buscarFilho(nome) != null) {
            System.out.println("Erro: Já existe um arquivo ou diretório com o nome '" + nome + "'.");
            return;
        }
        String txId = UUID.randomUUID().toString().substring(0, 8);
        journal.registrarOperacao(txId, "MKDIR", nome, "PENDENTE");

        DiretorioVirtual novoDir = new DiretorioVirtual(nome, diretorioAtual);
        diretorioAtual.adicionarFilho(novoDir);
        salvarDisco();

        journal.registrarOperacao(txId, "MKDIR", nome, "COMMIT");
        System.out.println("Diretório '" + nome + "' criado.");
    }

    public void criarArquivo(String nome, String conteudo) {
        if (diretorioAtual.buscarFilho(nome) != null) {
            System.out.println("Erro: Já existe um item com esse nome.");
            return;
        }
        String txId = UUID.randomUUID().toString().substring(0, 8);
        journal.registrarOperacao(txId, "TOUCH", nome, "PENDENTE");

        ArquivoVirtual novoArq = new ArquivoVirtual(nome, diretorioAtual, conteudo);
        diretorioAtual.adicionarFilho(novoArq);
        salvarDisco();

        journal.registrarOperacao(txId, "TOUCH", nome, "COMMIT");
        System.out.println("Arquivo '" + nome + "' criado.");
    }

    public void copiarArquivo(String origem, String destino) {
        NoSistema no = diretorioAtual.buscarFilho(origem);
        if (no == null || no.isDiretorio()) {
            System.out.println("Erro: Arquivo de origem não encontrado.");
            return;
        }
        if (diretorioAtual.buscarFilho(destino) != null) {
            System.out.println("Erro: O destino já existe.");
            return;
        }

        String txId = UUID.randomUUID().toString().substring(0, 8);
        journal.registrarOperacao(txId, "CP", origem + " -> " + destino, "PENDENTE");

        ArquivoVirtual original = (ArquivoVirtual) no;
        ArquivoVirtual copia = new ArquivoVirtual(destino, diretorioAtual, original.getConteudo());
        diretorioAtual.adicionarFilho(copia);
        salvarDisco();

        journal.registrarOperacao(txId, "CP", origem + " -> " + destino, "COMMIT");
        System.out.println("Arquivo copiado para '" + destino + "'.");
    }

    public void renomear(String nomeAntigo, String nomeNovo) {
        NoSistema no = diretorioAtual.buscarFilho(nomeAntigo);
        if (no == null) {
            System.out.println("Erro: Item '" + nomeAntigo + "' não encontrado.");
            return;
        }
        if (diretorioAtual.buscarFilho(nomeNovo) != null) {
            System.out.println("Erro: Já existe um item chamado '" + nomeNovo + "'.");
            return;
        }

        String txId = UUID.randomUUID().toString().substring(0, 8);
        journal.registrarOperacao(txId, "MV", nomeAntigo + " -> " + nomeNovo, "PENDENTE");

        diretorioAtual.removerFilho(nomeAntigo);
        no.setNome(nomeNovo);
        diretorioAtual.adicionarFilho(no);
        salvarDisco();

        journal.registrarOperacao(txId, "MV", nomeAntigo + " -> " + nomeNovo, "COMMIT");
        System.out.println("Renomeado com sucesso.");
    }

    public void apagar(String nome) {
        NoSistema no = diretorioAtual.buscarFilho(nome);
        if (no == null) {
            System.out.println("Erro: Item '" + nome + "' não encontrado.");
            return;
        }

        String txId = UUID.randomUUID().toString().substring(0, 8);
        String tipo = no.isDiretorio() ? "RMDIR" : "RM";
        journal.registrarOperacao(txId, tipo, nome, "PENDENTE");

        diretorioAtual.removerFilho(nome);
        salvarDisco();

        journal.registrarOperacao(txId, tipo, nome, "COMMIT");
        System.out.println("Item '" + nome + "' removido.");
    }

    public void listar() {
        System.out.println("Conteúdo de [" + getCaminhoAtual() + "]:");
        if (diretorioAtual.getFilhos().isEmpty()) {
            System.out.println("  (vazio)");
            return;
        }
        for (NoSistema no : diretorioAtual.getFilhos().values()) {
            String tipo = no.isDiretorio() ? "[DIR] " : "[ARQ] ";
            System.out.println("  " + tipo + no.getNome());
        }
    }

    public void mudarDiretorio(String nome) {
        if (nome.equals("..")) {
            if (diretorioAtual.getPai() != null) {
                diretorioAtual = diretorioAtual.getPai();
            }
            return;
        }
        if (nome.equals("/")) {
            diretorioAtual = raiz;
            return;
        }
        NoSistema alvo = diretorioAtual.buscarFilho(nome);
        if (alvo != null && alvo.isDiretorio()) {
            diretorioAtual = (DiretorioVirtual) alvo;
        } else {
            System.out.println("Erro: Diretório '" + nome + "' inválido.");
        }
    }

    public String getCaminhoAtual() {
        if (diretorioAtual == raiz) return "/";
        StringBuilder sb = new StringBuilder();
        DiretorioVirtual temp = diretorioAtual;
        while (temp != null && temp != raiz) {
            sb.insert(0, "/" + temp.getNome());
            temp = temp.getPai();
        }
        return sb.toString();
    }
}