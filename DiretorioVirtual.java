import java.util.Map;
import java.util.TreeMap;

/**
 * Diretório: uma tabela que liga cada nome ao seu item (como no Linux, é o
 * diretório que guarda os nomes; os atributos ficam no cabeçalho de cada item).
 */
public class DiretorioVirtual extends NoSistema {
    // TreeMap mantém os nomes em ordem alfabética, então o ls sai ordenado como no Linux
    private final Map<String, NoSistema> filhos = new TreeMap<>();

    public DiretorioVirtual(String nome, DiretorioVirtual pai, CabecalhoArquivo cabecalho) {
        super(nome, pai, cabecalho);
    }

    public Map<String, NoSistema> getFilhos() { return filhos; }

    public void adicionarFilho(NoSistema no) {
        filhos.put(no.getNome(), no);
    }

    public void removerFilho(String nome) {
        filhos.remove(nome);
    }

    public NoSistema buscarFilho(String nome) {
        return filhos.get(nome);
    }

    @Override
    public boolean isDiretorio() { return true; }
}
