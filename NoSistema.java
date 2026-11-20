import java.io.Serializable;
import java.time.LocalDateTime;

public abstract class NoSistema implements Serializable {
    private static final long serialVersionUID = 1L;
    
    protected String nome;
    protected LocalDateTime dataCriacao;
    protected DiretorioVirtual pai;

    public NoSistema(String nome, DiretorioVirtual pai) {
        this.nome = nome;
        this.pai = pai;
        this.dataCriacao = LocalDateTime.now();
    }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public DiretorioVirtual getPai() { return pai; }
    public void setPai(DiretorioVirtual pai) { this.pai = pai; }
    
    public abstract boolean isDiretorio();
}