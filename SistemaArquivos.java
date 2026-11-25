import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * O simulador do sistema de arquivos: os comandos (ls, mkdir, cp, mv, rm...)
 * trabalham sobre a árvore em memória e, a cada alteração, o disco virtual é
 * gravado seguindo o journaling (ver executarTransacao).
 *
 * Todos os comandos aceitam caminhos no padrão do Linux:
 *   absolutos: /docs/teste.txt    relativos: docs/teste.txt, ../teste.txt, ./teste.txt
 */
public class SistemaArquivos {
    private static final String PERMISSOES_ARQUIVO = "rw-r--r--";    // 644, padrão do Ubuntu/Zorin (umask 022)
    private static final String PERMISSOES_DIRETORIO = "rwxr-xr-x";  // 755
    private static final int TAMANHO_MAXIMO_NOME = 255;              // limite do Linux, em bytes
    private static final long INODE_RAIZ = 1;
    private static final DateTimeFormatter DATA_CURTA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATA_COMPLETA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final DiscoVirtual disco;
    private final JournalManager journal;
    private final String dono = System.getProperty("user.name");
    private Superbloco superbloco;
    private DiretorioVirtual diretorioAtual;

    public SistemaArquivos(String arquivoDisco, String arquivoJournal) {
        this.disco = new DiscoVirtual(arquivoDisco);
        this.journal = new JournalManager(arquivoJournal);
    }

    public boolean discoExiste() { return disco.existe(); }

    public void verificarFormatoDoDisco() throws IOException, DiscoVirtual.FormatoInvalidoException {
        disco.verificarFormato();
    }

    public String getArquivoDisco() { return disco.getNomeArquivo(); }

    public String getCaminhoAtual() { return diretorioAtual.getCaminho(); }

    // ==================================================================
    // Formatar, montar e desmontar
    // ==================================================================

    /** Cria um disco novo e vazio, só com a raiz "/". */
    public void formatar(char[] senha) throws IOException {
        DiretorioVirtual raiz = new DiretorioVirtual("/", null,
                new CabecalhoArquivo(INODE_RAIZ, CabecalhoArquivo.TIPO_DIRETORIO, dono, PERMISSOES_DIRETORIO));
        superbloco = new Superbloco(raiz, INODE_RAIZ + 1);

        String tx = novoIdTransacao();
        journal.registrarOperacao(tx, "FORMATAR", disco.getNomeArquivo(), JournalManager.PENDENTE);
        superbloco.setUltimaTransacao(tx);
        disco.formatar(senha, superbloco);
        journal.registrarOperacao(tx, "FORMATAR", disco.getNomeArquivo(), JournalManager.COMMIT);
        diretorioAtual = raiz;
    }

    /**
     * Monta um disco existente:
     *  1) descriptografa com a senha (se estiver errada, nada é alterado);
     *  2) se o disco ficou SUJO (o programa não saiu pelo exit), avisa;
     *  3) roda a recuperação do journal;
     *  4) marca o disco como SUJO (em uso) até o exit.
     */
    public void montar(char[] senha)
            throws IOException, DiscoVirtual.SenhaIncorretaException, DiscoVirtual.FormatoInvalidoException {
        superbloco = disco.montar(senha);

        if (disco.lerEstado() == DiscoVirtual.ESTADO_SUJO) {
            System.out.println("[AVISO] O disco não foi desmontado corretamente na última sessão (estado SUJO).");
        }
        int resolvidas = journal.recuperar(superbloco.getUltimaTransacao());
        if (resolvidas == 0) {
            System.out.println("[JOURNAL] Nenhuma transação pendente. Disco consistente.");
        }

        superbloco.registrarMontagem();
        disco.salvar(superbloco);   // grava a montagem e deixa o estado como SUJO enquanto estiver em uso
        diretorioAtual = superbloco.getRaiz();
        System.out.println("Sistema de arquivos montado a partir de " + disco.getNomeArquivo());
    }

    /** Marca o disco como LIMPO: na próxima montagem ele sabe que a saída foi normal. */
    public void desmontar() {
        try {
            disco.marcarEstado(DiscoVirtual.ESTADO_LIMPO);
            System.out.println("Disco desmontado e marcado como LIMPO.");
        } catch (IOException e) {
            System.err.println("Erro ao desmontar o disco: " + e.getMessage());
        }
    }

    // ==================================================================
    // Journaling
    // ==================================================================

    /**
     * Executa uma alteração seguindo o journaling (write-ahead logging):
     *  1) grava PENDENTE no journal ANTES de mexer em qualquer coisa;
     *  2) aplica a alteração na árvore em memória;
     *  3) anota o ID da transação no superbloco e grava o disco inteiro (de forma atômica);
     *  4) grava COMMIT no journal.
     * Se o programa cair entre 1 e 4, a recuperação na próxima montagem decide pelo
     * ID anotado no superbloco se a alteração foi gravada (COMMIT) ou não (ABORTADA).
     */
    private boolean executarTransacao(String operacao, String detalhes, Runnable alteracao) {
        String tx = novoIdTransacao();
        if (!journal.registrarOperacao(tx, operacao, detalhes, JournalManager.PENDENTE)) {
            System.out.println("Erro: não foi possível escrever no journal. Operação cancelada.");
            return false;
        }

        alteracao.run();
        superbloco.setUltimaTransacao(tx);

        try {
            disco.salvar(superbloco);
        } catch (IOException e) {
            System.err.println("Erro ao gravar o disco: " + e.getMessage() + ". A operação foi desfeita.");
            journal.registrarOperacao(tx, operacao, detalhes, JournalManager.ABORTADA);
            recarregarDoDisco();
            return false;
        }

        journal.registrarOperacao(tx, operacao, detalhes, JournalManager.COMMIT);
        return true;
    }

    /** Descarta o que está na memória e volta ao que está gravado no disco. */
    private void recarregarDoDisco() {
        String caminhoAtual = diretorioAtual.getCaminho();
        try {
            superbloco = disco.recarregar();
        } catch (Exception e) {
            System.err.println("Falha grave: não foi possível reler o disco (" + e.getMessage() + "). Encerrando.");
            System.exit(1);
        }
        NoSistema alvo = resolver(caminhoAtual);
        diretorioAtual = (alvo != null && alvo.isDiretorio()) ? (DiretorioVirtual) alvo : superbloco.getRaiz();
    }

    private static String novoIdTransacao() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ==================================================================
    // Caminhos
    // ==================================================================

    /**
     * Encontra o item de um caminho absoluto (começa com "/") ou relativo
     * (começa no diretório atual). Entende "." e "..". Devolve null se não existir.
     */
    private NoSistema resolver(String caminho) {
        NoSistema atual = caminho.startsWith("/") ? superbloco.getRaiz() : diretorioAtual;
        for (String parte : caminho.split("/")) {
            if (parte.isEmpty() || parte.equals(".")) {
                continue;
            }
            if (!atual.isDiretorio()) {
                return null;   // ex.: /docs/a.txt/outra -> a.txt não é diretório
            }
            DiretorioVirtual diretorio = (DiretorioVirtual) atual;
            if (parte.equals("..")) {
                // ".." na raiz continua na raiz, igual ao Linux
                atual = (diretorio.getPai() != null) ? diretorio.getPai() : diretorio;
            } else {
                atual = diretorio.buscarFilho(parte);
                if (atual == null) {
                    return null;
                }
            }
        }
        return atual;
    }

    /** Diretório onde um item vai ficar + o nome que ele vai ter. */
    private static class Destino {
        final DiretorioVirtual pai;
        final String nome;

        Destino(DiretorioVirtual pai, String nome) {
            this.pai = pai;
            this.nome = nome;
        }

        String caminho() {
            return juntar(pai, nome);
        }
    }

    /**
     * Separa um caminho de algo que vai ser criado em "diretório pai" + "nome final".
     * Ex.: "/docs/sub/novo.txt" -> pai = /docs/sub, nome = novo.txt.
     * Mostra o erro e devolve null se o pai não existir ou o nome for inválido.
     */
    private Destino separarPaiENome(String caminho) {
        String limpo = caminho;
        while (limpo.length() > 1 && limpo.endsWith("/")) {
            limpo = limpo.substring(0, limpo.length() - 1);
        }
        int ultimaBarra = limpo.lastIndexOf('/');
        String caminhoPai = (ultimaBarra < 0) ? "." : (ultimaBarra == 0 ? "/" : limpo.substring(0, ultimaBarra));
        String nome = limpo.substring(ultimaBarra + 1);

        NoSistema pai = resolver(caminhoPai);
        if (pai == null || !pai.isDiretorio()) {
            erro("o diretório '" + caminhoPai + "' não existe.");
            return null;
        }
        String problema = validarNome(nome);
        if (problema != null) {
            erro(problema);
            return null;
        }
        return new Destino((DiretorioVirtual) pai, nome);
    }

    /**
     * Destino de cp/mv, como no Linux: se o destino é um diretório que já existe,
     * o item vai para dentro dele com o mesmo nome; senão, o destino é o novo caminho.
     */
    private Destino destinoDeCopiaOuMovimento(String destino, String nomeOriginal) {
        NoSistema alvo = resolver(destino);
        if (alvo != null && alvo.isDiretorio()) {
            return new Destino((DiretorioVirtual) alvo, nomeOriginal);
        }
        return separarPaiENome(destino);
    }

    /** Regras de nome do Linux. Devolve a mensagem de erro, ou null se o nome for válido. */
    private static String validarNome(String nome) {
        if (nome.isEmpty()) {
            return "nome vazio.";
        }
        if (nome.equals(".") || nome.equals("..")) {
            return "'" + nome + "' é um nome reservado.";
        }
        if (nome.getBytes(StandardCharsets.UTF_8).length > TAMANHO_MAXIMO_NOME) {
            return "o nome passa de " + TAMANHO_MAXIMO_NOME + " bytes (limite do Linux).";
        }
        for (char c : nome.toCharArray()) {
            if (Character.isISOControl(c)) {
                return "o nome tem caracteres de controle.";
            }
        }
        return null;
    }

    private static String juntar(DiretorioVirtual pai, String nome) {
        String caminhoDoPai = pai.getCaminho();
        return caminhoDoPai.equals("/") ? "/" + nome : caminhoDoPai + "/" + nome;
    }

    private CabecalhoArquivo novoCabecalho(char tipo, String permissoes) {
        return new CabecalhoArquivo(superbloco.gerarInode(), tipo, dono, permissoes);
    }

    private static void erro(String mensagem) {
        System.out.println("Erro: " + mensagem);
    }

    // ==================================================================
    // Operações
    // ==================================================================

    public void criarDiretorio(String caminho) {
        Destino destino = separarPaiENome(caminho);
        if (destino == null) {
            return;
        }
        if (destino.pai.buscarFilho(destino.nome) != null) {
            erro("'" + destino.caminho() + "' já existe.");
            return;
        }
        boolean ok = executarTransacao("MKDIR", destino.caminho(), () -> {
            DiretorioVirtual novo = new DiretorioVirtual(destino.nome, destino.pai,
                    novoCabecalho(CabecalhoArquivo.TIPO_DIRETORIO, PERMISSOES_DIRETORIO));
            destino.pai.adicionarFilho(novo);
            destino.pai.getCabecalho().marcarModificacao();
        });
        if (ok) {
            System.out.println("Diretório '" + destino.caminho() + "' criado.");
        }
    }

    /**
     * touch: cria o arquivo. Se ele já existir, faz como o Linux e só atualiza a data de
     * modificação; se vier conteúdo junto, o conteúdo antigo é substituído.
     * conteudo == null quando o usuário não passou conteúdo.
     */
    public void criarArquivo(String caminho, String conteudo) {
        Destino destino = separarPaiENome(caminho);
        if (destino == null) {
            return;
        }
        NoSistema existente = destino.pai.buscarFilho(destino.nome);

        if (existente != null) {
            boolean trocaConteudo = conteudo != null && !existente.isDiretorio();
            String detalhes = destino.caminho() + (trocaConteudo ? " (conteúdo substituído)" : " (data atualizada)");
            boolean ok = executarTransacao("TOUCH", detalhes, () -> {
                if (trocaConteudo) {
                    ((ArquivoVirtual) existente).setConteudo(conteudo);
                } else {
                    existente.getCabecalho().marcarModificacao();
                }
            });
            if (ok) {
                System.out.println("'" + destino.caminho() + "' atualizado.");
            }
            return;
        }

        boolean ok = executarTransacao("TOUCH", destino.caminho(), () -> {
            ArquivoVirtual novo = new ArquivoVirtual(destino.nome, destino.pai, conteudo == null ? "" : conteudo,
                    novoCabecalho(CabecalhoArquivo.TIPO_ARQUIVO, PERMISSOES_ARQUIVO));
            destino.pai.adicionarFilho(novo);
            destino.pai.getCabecalho().marcarModificacao();
        });
        if (ok) {
            System.out.println("Arquivo '" + destino.caminho() + "' criado.");
        }
    }

    /** cp: a cópia ganha um cabeçalho NOVO (inode e datas novos), como no Linux. */
    public void copiarArquivo(String origem, String destino) {
        NoSistema item = resolver(origem);
        if (item == null) {
            erro("'" + origem + "' não existe.");
            return;
        }
        if (item.isDiretorio()) {
            erro("'" + item.getCaminho() + "' é um diretório (o cp copia apenas arquivos).");
            return;
        }
        Destino alvo = destinoDeCopiaOuMovimento(destino, item.getNome());
        if (alvo == null) {
            return;
        }
        if (alvo.pai.buscarFilho(alvo.nome) != null) {
            erro("'" + alvo.caminho() + "' já existe.");
            return;
        }
        ArquivoVirtual original = (ArquivoVirtual) item;
        boolean ok = executarTransacao("CP", original.getCaminho() + " -> " + alvo.caminho(), () -> {
            ArquivoVirtual copia = new ArquivoVirtual(alvo.nome, alvo.pai, original.getConteudo(),
                    novoCabecalho(CabecalhoArquivo.TIPO_ARQUIVO, original.getCabecalho().getPermissoes()));
            alvo.pai.adicionarFilho(copia);
            alvo.pai.getCabecalho().marcarModificacao();
        });
        if (ok) {
            System.out.println("Arquivo copiado para '" + alvo.caminho() + "'.");
        }
    }

    /** mv: renomeia e/ou move arquivos e diretórios (para outro diretório, como no Linux). */
    public void renomear(String origem, String destino) {
        NoSistema item = resolver(origem);
        if (item == null) {
            erro("'" + origem + "' não existe.");
            return;
        }
        if (item == superbloco.getRaiz()) {
            erro("não é possível mover a raiz '/'.");
            return;
        }
        Destino alvo = destinoDeCopiaOuMovimento(destino, item.getNome());
        if (alvo == null) {
            return;
        }
        NoSistema jaExiste = alvo.pai.buscarFilho(alvo.nome);
        if (jaExiste == item) {
            erro("origem e destino são o mesmo item.");
            return;
        }
        if (jaExiste != null) {
            erro("'" + alvo.caminho() + "' já existe.");
            return;
        }
        if (item.isDiretorio() && alvo.pai.estaDentroDe((DiretorioVirtual) item)) {
            erro("não é possível mover '" + item.getCaminho() + "' para dentro dele mesmo.");
            return;
        }

        String caminhoAntigo = item.getCaminho();
        String caminhoNovo = alvo.caminho();
        boolean ok = executarTransacao("MV", caminhoAntigo + " -> " + caminhoNovo, () -> {
            DiretorioVirtual paiAntigo = item.getPai();
            paiAntigo.removerFilho(item.getNome());
            item.setNome(alvo.nome);
            item.setPai(alvo.pai);
            alvo.pai.adicionarFilho(item);
            paiAntigo.getCabecalho().marcarModificacao();
            alvo.pai.getCabecalho().marcarModificacao();
        });
        if (ok) {
            System.out.println("'" + caminhoAntigo + "' -> '" + caminhoNovo + "'.");
        }
    }

    /** rm: apaga um arquivo, ou um diretório com tudo o que tem dentro. */
    public void apagar(String caminho) {
        NoSistema item = resolver(caminho);
        if (item == null) {
            erro("'" + caminho + "' não existe.");
            return;
        }
        if (item == superbloco.getRaiz()) {
            erro("não é possível apagar a raiz '/'.");
            return;
        }
        if (item.isDiretorio() && diretorioAtual.estaDentroDe((DiretorioVirtual) item)) {
            erro("você está dentro de '" + item.getCaminho() + "'. Saia dele antes de apagar.");
            return;
        }
        String caminhoCompleto = item.getCaminho();
        String operacao = item.isDiretorio() ? "RMDIR" : "RM";
        boolean ok = executarTransacao(operacao, caminhoCompleto, () -> {
            DiretorioVirtual pai = item.getPai();
            pai.removerFilho(item.getNome());
            pai.getCabecalho().marcarModificacao();
        });
        if (ok) {
            System.out.println("'" + caminhoCompleto + "' removido.");
        }
    }

    // ==================================================================
    // Consultas (não alteram o disco)
    // ==================================================================

    /** ls [-l] [caminho]. Sem caminho, lista o diretório atual. */
    public void listar(String caminho, boolean detalhado) {
        NoSistema alvo = (caminho == null) ? diretorioAtual : resolver(caminho);
        if (alvo == null) {
            erro("'" + caminho + "' não existe.");
            return;
        }
        if (!alvo.isDiretorio()) {
            imprimirItem(alvo, alvo.getCaminho(), detalhado);   // ls de um arquivo mostra só ele
            return;
        }
        DiretorioVirtual diretorio = (DiretorioVirtual) alvo;
        System.out.println("Conteúdo de [" + diretorio.getCaminho() + "]:");
        if (diretorio.getFilhos().isEmpty()) {
            System.out.println("  (vazio)");
            return;
        }
        for (NoSistema filho : diretorio.getFilhos().values()) {
            imprimirItem(filho, filho.getNome(), detalhado);
        }
    }

    private static void imprimirItem(NoSistema item, String nomeExibido, boolean detalhado) {
        if (!detalhado) {
            System.out.println("  " + (item.isDiretorio() ? "[DIR] " : "[ARQ] ") + nomeExibido);
            return;
        }
        CabecalhoArquivo c = item.getCabecalho();
        String tamanho = item.isDiretorio() ? "-" : String.valueOf(c.getTamanho());
        System.out.println(String.format("  %s  %-12s %8s  %s  %s", c.getPermissoesCompletas(), c.getDono(),
                tamanho, c.getDataModificacao().format(DATA_CURTA), nomeExibido));
    }

    public void mudarDiretorio(String caminho) {
        NoSistema alvo = resolver(caminho);
        if (alvo == null) {
            erro("'" + caminho + "' não existe.");
        } else if (!alvo.isDiretorio()) {
            erro("'" + alvo.getCaminho() + "' não é um diretório.");
        } else {
            diretorioAtual = (DiretorioVirtual) alvo;
        }
    }

    /** cat: mostra o conteúdo (só pelo simulador, com a senha, dá para ver o conteúdo). */
    public void mostrarConteudo(String caminho) {
        NoSistema alvo = resolver(caminho);
        if (alvo == null) {
            erro("'" + caminho + "' não existe.");
        } else if (alvo.isDiretorio()) {
            erro("'" + alvo.getCaminho() + "' é um diretório.");
        } else {
            System.out.println(((ArquivoVirtual) alvo).getConteudo());
        }
    }

    /** stat: mostra o cabeçalho (atributos) completo de um item. */
    public void mostrarCabecalho(String caminho) {
        NoSistema alvo = resolver(caminho);
        if (alvo == null) {
            erro("'" + caminho + "' não existe.");
            return;
        }
        CabecalhoArquivo c = alvo.getCabecalho();
        System.out.println("  Caminho:       " + alvo.getCaminho());
        System.out.println("  Tipo:          " + (alvo.isDiretorio() ? "diretório" : "arquivo"));
        System.out.println("  Inode:         " + c.getInode());
        if (alvo.isDiretorio()) {
            System.out.println("  Itens:         " + ((DiretorioVirtual) alvo).getFilhos().size());
        } else {
            System.out.println("  Tamanho:       " + c.getTamanho() + " bytes");
        }
        System.out.println("  Permissões:    " + c.getPermissoesCompletas());
        System.out.println("  Dono:          " + c.getDono());
        System.out.println("  Criado em:     " + c.getDataCriacao().format(DATA_COMPLETA));
        System.out.println("  Modificado em: " + c.getDataModificacao().format(DATA_COMPLETA));
    }

    /** info: mostra o cabeçalho do disco e o superbloco. */
    public void mostrarInfoDisco() {
        long[] contagem = new long[3];   // {diretórios, arquivos, bytes}
        contar(superbloco.getRaiz(), contagem);
        String estado;
        try {
            estado = disco.lerEstado() == DiscoVirtual.ESTADO_SUJO ? "SUJO (em uso)" : "LIMPO";
        } catch (IOException e) {
            estado = "não foi possível ler";
        }
        System.out.println("  Arquivo do disco:  " + disco.getNomeArquivo());
        System.out.println("  Formato:           SIMFS v1, dados cifrados com AES-256-GCM (chave gerada da senha com PBKDF2)");
        System.out.println("  Estado:            " + estado);
        System.out.println("  Formatado em:      " + superbloco.getDataFormatacao().format(DATA_COMPLETA));
        System.out.println("  Última montagem:   " + superbloco.getUltimaMontagem().format(DATA_COMPLETA));
        System.out.println("  Montagens:         " + superbloco.getMontagens());
        System.out.println("  Diretórios:        " + contagem[0] + " (contando a raiz)");
        System.out.println("  Arquivos:          " + contagem[1] + " (" + contagem[2] + " bytes de conteúdo)");
        System.out.println("  Próximo inode:     " + superbloco.getProximoInode());
    }

    private static void contar(NoSistema item, long[] contagem) {
        if (item.isDiretorio()) {
            contagem[0]++;
            for (NoSistema filho : ((DiretorioVirtual) item).getFilhos().values()) {
                contar(filho, contagem);
            }
        } else {
            contagem[1]++;
            contagem[2] += item.getCabecalho().getTamanho();
        }
    }
}
