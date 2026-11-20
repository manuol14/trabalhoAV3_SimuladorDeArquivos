import java.util.HashMap;
import java.util.Map;

public class DiretorioVirtual extends NoSistema {
    private static final long serialVersionUID = 1L;
    private Map<String, NoSistema> filhos;

    public DiretorioVirtual(String nome, DiretorioVirtual pai) {
        super(nome, pai);
        this.filhos = new HashMap<>();
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