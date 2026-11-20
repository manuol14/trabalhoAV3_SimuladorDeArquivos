import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;

public class JournalManager {
    private final Path caminhoJournal;

    public JournalManager(String arquivoLog) {
        this.caminhoJournal = Paths.get(arquivoLog);
        inicializar();
    }

    private void inicializar() {
        try {
            if (!Files.exists(caminhoJournal)) {
                Files.createFile(caminhoJournal);
            }
        } catch (IOException e) {
            System.err.println("Erro ao inicializar o Journal: " + e.getMessage());
        }
    }

  
    public void registrarOperacao(String idTransacao, String operacao, String detalhes, String status) {
        String linha = String.format("[%s] TX:%s | OP:%s | DETALHES:%s | STATUS:%s%n",
                LocalDateTime.now(), idTransacao, operacao, detalhes, status);
        try {
            Files.writeString(caminhoJournal, linha, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Falha crítica ao escrever no Journal: " + e.getMessage());
        }
    }

    public void verificarIntegridade() {
        try {
            List<String> linhas = Files.readAllLines(caminhoJournal);
            for (String linha : linhas) {
                if (linha.contains("STATUS:PENDENTE")) {
      
                    System.out.println("[JOURNAL RECOVERY] Verificando transação incompleta: " + linha);
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler Journal na inicialização: " + e.getMessage());
        }
    }
}