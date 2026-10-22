# GlicNote API

API Java 17 / Spring Boot 3.5 com PostgreSQL, Flyway e autenticação Supabase. Substitui a implementação inicial do repositório por um contrato `/v1` baseado na [modelagem de domínio](docs/modelagem-dominio.md).

Implementa perfil, contato de emergência, aferições, acompanhamento mediante aprovação, bloqueios, feed de compartilhamentos opcionais, medalhas e notificações internas. Google e sessões são gerenciados pelo Supabase Auth; a API valida os access tokens do Supabase.

## Executar localmente

Requisitos: Java 17 e Docker. Maven está incluído pelo wrapper.

```bash
docker compose up -d db
export DB_URL=jdbc:postgresql://localhost:5433/glicnote
export DB_USERNAME=glicnote
export DB_PASSWORD=glicnote-local-only
export SUPABASE_URL=https://SEU_PROJETO.supabase.co
./mvnw spring-boot:run
```

Ou API e banco em containers:

```bash
SUPABASE_URL=https://SEU_PROJETO.supabase.co docker compose up -d --build --wait
```

O Compose padrão cria banco local persistente em volume, espera sua prontidão, executa as migrações e verifica a saúde da API. Para apenas experimentar a inicialização, `docker compose up -d --build --wait` também funciona sem configurar Supabase; as rotas privadas continuam exigindo um token válido do projeto configurado.

Comandos úteis:

```bash
docker compose ps
docker compose logs -f api
curl http://localhost:8080/health
docker compose down
```

`down` preserva o volume do banco. `API_PORT` e `LOCAL_DB_PORT` permitem mudar as portas do host. O processo roda como usuário sem privilégios administrativos, tem healthcheck e reinício automático. As portas locais ficam vinculadas a `127.0.0.1`.

Para usar **PostgreSQL remoto do Supabase**, sem subir banco local:

```bash
cp .env.example .env
# Preencha SUPABASE_URL, DB_URL, DB_USERNAME e DB_PASSWORD no .env.
docker compose -f compose.supabase.yaml up -d --build --wait
docker compose -f compose.supabase.yaml logs -f api
```

`compose.supabase.yaml` é um arquivo independente. Ele rejeita variáveis remotas ausentes. O Compose padrão usa sempre o banco local, mesmo que `.env` tenha uma URL remota; escolha o arquivo conforme o ambiente.

A API fica em `http://localhost:8080`. `GET /health` é público e indica que o processo iniciou; os endpoints `/v1/**` exigem `Authorization: Bearer <access_token>`. Não existe login local de teste ou endpoint que aceite um UUID como autenticação.

Os exemplos de senha do Compose valem apenas para o banco local. O Spring não carrega `.env` automaticamente; exporte as variáveis ou use seu gerenciador de ambiente. O Compose carrega `.env` para interpolação.

## Testes

```bash
./scripts/test.sh
```

O script cria um PostgreSQL 17 temporário em porta aleatória, executa `./mvnw verify` e remove somente esse container ao terminar. As migrações rodam no banco real. Os testes geram uma chave RSA e um servidor JWKS temporário, usando o mesmo decoder e filtro de segurança da aplicação. Não dependem de uma conta Supabase nem de credenciais reais.

Os testes de integração têm sufixo `IT` e rodam no Maven Failsafe (`verify`); `mvn test` isolado não os executa. Em infraestrutura de testes própria, configure `TEST_DB_URL`, `TEST_DB_USERNAME` e `TEST_DB_PASSWORD` antes de `./mvnw verify`. Use um banco descartável: cada teste limpa as tabelas de usuários com cascata.

Cobertura inclui assinatura e claims JWT, isolamento de dados, períodos e fusos, insulina decimal, idempotência, paginação, concorrência, versões, solicitações, bloqueios, feed e regras de medalhas.

## Configurar Supabase e Google

1. Criar um projeto Supabase e habilitar Google em **Authentication → Sign In / Providers**, com os clientes OAuth e redirects exigidos pelas plataformas do app.
2. Configurar uma chave de assinatura assimétrica ativa (`ES256` ou `RS256`). Esta API usa JWKS públicos e não aceita o segredo legado `HS256` nem tokens de serviço.
3. No Flutter, autenticar com Supabase Auth/Google e enviar o **access token do Supabase**, não o ID token Google, para esta API.
4. Configurar a URL do projeto em `SUPABASE_URL`, sem barra no final.
5. Configurar `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` usando a conexão PostgreSQL do projeto. Para uma API persistente em rede IPv4, usar o **Session pooler**, porta 5432, copiado do painel Connect. Não usar a URL HTTP do projeto como URL JDBC.
6. Iniciar a API para executar as migrações. O primeiro acesso autenticado cria o perfil com o mesmo UUID de `auth.users`; `PUT /v1/me` conclui nome de usuário e fuso.

Exemplo de URL JDBC do pooler: `jdbc:postgresql://aws-0-REGIAO.pooler.supabase.com:5432/postgres?sslmode=require`. A senha fica em variável separada. O usuário do pooler pode ser `postgres.<project-ref>`; copiar o valor exato do painel.

As tabelas ficam no schema privado `glicnote`, fora do schema público exposto pelo PostgREST. Não adicionar `glicnote` aos schemas expostos do Supabase nem conceder acesso às roles dos clientes. As migrações removem privilégios públicos e das roles Supabase `anon`, `authenticated` e `service_role`, quando presentes. Somente a API usa a conexão JDBC; a chave de banco nunca entra no aplicativo.

Supabase gerencia identidades e sessões: não duplicamos essas tabelas nem armazenamos refresh tokens do usuário no nosso domínio. A assinatura, emissor, expiração, público `authenticated`, role e UUID do sujeito são validados. Sessões anônimas são rejeitadas. Logout/renovação pertencem ao Supabase; um access token já emitido segue válido até expirar conforme a política do provedor.

Referências oficiais: [Google no Supabase](https://supabase.com/docs/guides/auth/social-login/auth-google), [chaves de assinatura](https://supabase.com/docs/guides/auth/signing-keys), [conexão PostgreSQL](https://supabase.com/docs/guides/database/connecting-to-postgres).

## Banco e migrações

- `V1__domain.sql`: schema, tabelas, constraints, índices e permissões.
- `V2__badge_catalog.sql`: medalhas por quantidade/sequência, aniversário e Ano Novo.
- UUIDs gerados no cliente são aceitos em novas aferições para sincronização idempotente.
- Datas de ocorrência são instantes UTC; o servidor calcula a data civil no fuso IANA informado.
- Precisão de insulina: até três casas decimais; `null` significa não informado e zero é um valor explícito.
- Precisão de instantes: microssegundos, compatível com PostgreSQL. A entrada é normalizada antes da persistência.
- Atualização/exclusão de aferições e decisões sociais exigem a versão atual.

Esta base foi recriada, conforme o novo escopo. As migrações antigas foram substituídas: executar em banco/schema novo. Não aplicar sobre um schema que tenha a sequência antiga de migrações e não executar `flyway clean` em dados existentes. Se houver dados antigos a preservar, uma migração de conversão deve ser escrita antes de conectá-los.

## Deploy

Supabase hospeda banco e autenticação. Esta API Spring Boot é executada em um servidor/container separado; ela não roda dentro de Supabase Edge Functions ou Cloudflare Workers.

`Dockerfile` e `render.yaml` preparam uma instância **Render Free** usando o PostgreSQL do Supabase. O Blueprint desabilita deploy automático e solicita as variáveis remotas. Nenhum recurso remoto é criado ao adicionar esses arquivos ao projeto. O plano gratuito do Render pode dormir por inatividade; não há banco Render no Blueprint.

Antes de iniciar um deploy, configurar `SUPABASE_URL`, `DB_URL`, `DB_USERNAME` e `DB_PASSWORD`. `CORS_ORIGINS` aceita origens web exatas separadas por vírgula; pode ficar vazio para clientes nativos. Credenciais de migração devem ter permissão de criar o schema e suas tabelas. A separação entre uma role de migração e uma role de execução pode ser configurada com as propriedades padrão do Flyway/Spring.

`.github/workflows/ci.yml` executa os testes em PostgreSQL descartável e verifica a construção Docker. Nenhum deploy está configurado no CI.

## Contrato e regras

Consulte [o contrato HTTP](docs/api.md) e [as decisões de implementação](docs/decisoes.md).

A API retorna códigos estáveis e valores brutos; nome de medalha, período do dia e mensagens são traduzidos no Flutter. O backend não assume o idioma do usuário. `mg/dL` continua sendo a unidade de glicemia; traduzir a unidade de insulina como UI/IU na apresentação.

Não fazem parte desta entrega: integração visual das funcionalidades sociais no Flutter, cadastro de fotos no Storage, push remoto e exclusão completa da conta Supabase. Esses fluxos precisam ser conectados/configurados depois; não há endpoints fictícios para eles.

### Regra de aferições repetidas

Uma aferição por momento em cada dia local, por usuário, exceto “Outro”.
Alterar glicemia ou insulina não permite repetir o momento no mesmo dia.
O app avisa antes do envio quando o registro já está carregado; a API garante a
regra em novas inclusões e edições, inclusive entre dispositivos. Repetir uma
requisição após falha de conexão continua seguro e não cria outra aferição.

“Outro” permite vários registros por dia; somente o mesmo instante é bloqueado,
independentemente da glicemia ou insulina.

## Foto Google e política de privacidade

A API importa `avatar_url`, com fallback para `picture`, dos metadados da sessão
validada. URLs HTTPS válidas atualizam a foto em contas existentes sem sobrescrever
nome/username editados. A política pública preliminar fica em `/privacy.html`, sem
autenticação; o restante de `/v1` continua privado.

Veja [a minuta da política](docs/politica-de-privacidade.md) e
[o passo a passo do Render](docs/deploy-render.md). O responsável informado é Tech Mow;
o e-mail de privacidade, retenção e bases legais ainda precisam ser definidos.
