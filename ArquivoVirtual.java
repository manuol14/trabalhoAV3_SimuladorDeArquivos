import java.nio.charset.StandardCharsets;

/** Arquivo comum: um cabeçalho (herdado de NoSistema) + o conteúdo. */
public class ArquivoVirtual extends NoSistema {
    private String conteudo;

    public ArquivoVirtual(String nome, DiretorioVirtual pai, String conteudo, CabecalhoArquivo cabecalho) {
        super(nome, pai, cabecalho);
        this.conteudo = conteudo;
        atualizarTamanho();
    }

    public String getConteudo() { return conteudo; }

    /** Troca o conteúdo: atualiza o tamanho e a data de modificação no cabeçalho. */
    public void setConteudo(String conteudo) {
        this.conteudo = conteudo;
        atualizarTamanho();
        cabecalho.marcarModificacao();
    }

    /** Tamanho em BYTES (UTF-8), não em letras: "ação" tem 4 letras e 6 bytes. */
    private void atualizarTamanho() {
        cabecalho.setTamanho(conteudo.getBytes(StandardCharsets.UTF_8).length);
    }

    @Override
    public boolean isDiretorio() { return false; }
}
