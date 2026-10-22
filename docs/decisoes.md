# Decisões de implementação

## Autenticação e hospedagem

Mantivemos Java 17/Spring Boot, reconstruindo a implementação. Supabase hospeda PostgreSQL e autenticação Google, enquanto o container da API precisa de um host Java/Docker, como Render. Cloudflare Workers e Edge Functions não são o destino do JAR Spring Boot.

O app autentica no Supabase e esta API é um OAuth2 Resource Server. Identidades Google e sessões permanecem no Supabase Auth. `profiles.id` é o UUID `sub` do access token Supabase; não é o `sub` do ID token Google. Isso preserva o vínculo de identidade mesmo se o endereço de e-mail mudar. A API não recebe nem persiste a senha/refresh token Google. O bootstrap só preenche um perfil inexistente e não sobrescreve edições locais.

## Persistência e fronteira de acesso

SQL explícito com Spring JdbcClient e PostgreSQL permite expressar constraints, paginação e locks sem expor entidades ORM. Flyway gerencia schema novo `glicnote`; os clientes acessam apenas a API. Não habilitamos PostgREST para essas tabelas. Verificações de dono e visibilidade ficam nos serviços/consultas, com FKs compostas reforçando a autoria das publicações e da origem das medalhas.

O contrato separa DTOs públicos e privados. O papel `service_role` não é uma identidade de usuário aceita por esta API. Em produção, uma role JDBC dedicada pode receber os privilégios necessários no schema; executar as migrações com role própria pelo mecanismo padrão do Flyway.

## Escritas, idempotência e concorrência

Aferições aceitam UUIDs do cliente e repetição idêntica, normalizando instantes para microssegundos antes da gravação. Edição/exclusão usa versão para impedir sobrescrita silenciosa. Mutações de aferição e perfil que afetam medalhas serializam pelo lock da linha do usuário; concessão e notificação compartilham a transação e possuem chaves únicas.

Relações/bloqueios bloqueiam os dois perfis em ordem determinística, evitando que aceite e bloqueio simultâneos mantenham uma autorização indevida. Versão detecta decisões sobre solicitações antigas. O acesso ao feed é verificado novamente a cada requisição, incluindo por ID de publicação.

A avaliação de medalhas é síncrona nesta fase. Caso seja separada em workers, adotar outbox transacional e consumidores idempotentes. Não emitir eventos apenas em memória após o commit.

## Tempo e medalhas

`measuredAt` é o instante real informado; `localDate` é calculada usando o fuso IANA da aferição. Mudar o fuso atual do perfil não reescreve datas históricas. Não se usa a data de sincronização como dia do registro.

Dias consecutivos são calculados sobre datas distintas ordenadas; quantidade e sequência são critérios diferentes. Conquistas concedidas são marcos preservados, enquanto progresso atual considera os registros existentes. Exclusão da origem remove apenas a referência da conquista, sem remover a conquista. Anuais identificam a edição pelo ano da data local elegível.

## Compartilhamento e feed

Aferição e medalha privadas não são publicações. Compartilhar exige operação explícita. O feed consulta próprias publicações e de acompanhados aprovados, e sempre exclui pares bloqueados. Remover acompanhante retira acesso a publicações anteriores e futuras; ao aceitar novamente, o acesso retorna às publicações compartilhadas existentes.

A versão inicial usa consultas simples e páginas limitadas a 50. A montagem do feed consulta as origens por item; isso é uma implementação inicial com custo linear no tamanho da página. Uma próxima otimização pode carregar autores/origens em lote sem alterar o contrato. Não materializar um feed por destinatário antes de medir necessidade.

## Limites conhecidos

- O domínio e os endpoints estão implementados; não foi criado projeto Supabase nem habilitado Google em uma conta real.
- Não há integração das novas rotas no Flutter nesta entrega.
- Sessões/identidades são gerenciadas pelo provedor; exclusão completa de conta, incluindo `auth.users`, exige fluxo administrativo coordenado ainda não exposto.
- Não há endpoint de upload de fotos; a foto inicial vem da identidade Google/Supabase. Storage pode ser conectado depois.
- Notificações são internas e paginadas; push exige cadastro e gestão de dispositivos/tokens.
- Não há importação automática de dados legados ou do armazenamento em memória do app.
- Ainda não há limitação por taxa no serviço. Antes de abertura ampla, definir as cotas de busca, solicitações e escritas na infraestrutura escolhida.
- Catálogo de medalhas/datas é gerenciado por migrações. Não há painel administrativo ou endpoint público para conceder medalhas.
- Sem ranking, comentários ou curtidas.
