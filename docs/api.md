# Contrato HTTP `/v1`

Todas as rotas abaixo exigem access token Supabase no cabeçalho `Authorization: Bearer ...`. O único endpoint público é `GET /health`. Datas/horas JSON usam ISO 8601; IDs são UUIDs; propriedades usam camelCase.

## Rotas

| Método | Rota | Comportamento |
|---|---|---|
| GET | `/v1/me` | Perfil privado; cria perfil inicial se necessário |
| PUT | `/v1/me` | Atualiza nome, nome de usuário, fuso e aniversário opcional |
| GET / PUT / DELETE | `/v1/me/emergency-contact` | Contato privado; ausência retorna 404; DELETE retorna 204 |
| GET | `/v1/users?query=ana&limit=20` | Busca por prefixo de username ou trecho de nome; mínimo 2 caracteres |
| GET | `/v1/users/{id}` | Apenas perfil público; bloqueados retornam 404 |
| POST | `/v1/readings` | Registra aferição; UUID opcional permite repetição idempotente |
| GET | `/v1/readings` | Lista privada com filtros e cursor |
| GET | `/v1/readings/summary` | Contagem, média e última aferição, respeitando os filtros |
| GET / PUT | `/v1/readings/{id}` | Consulta ou edição da própria aferição |
| DELETE | `/v1/readings/{id}?version=0` | Exclui própria aferição e publicação vinculada; 204 |
| POST | `/v1/follow-requests/{targetId}` | Solicita acompanhar; não concede acesso imediato |
| PUT | `/v1/follow-requests/{id}/decision` | Destinatário aceita ou recusa |
| PUT | `/v1/follow-requests/{id}/cancel` | Solicitante cancela pendência |
| GET | `/v1/follows?direction=incoming&status=pending` | Lista relações/solicitações do usuário |
| DELETE | `/v1/follows/{id}?version=1` | Solicitante deixa de acompanhar ou acompanhado remove vínculo aceito |
| GET | `/v1/blocks?limit=50` | IDs bloqueados pelo usuário; lista limitada |
| PUT / DELETE | `/v1/blocks/{targetId}` | Bloqueia/desbloqueia; 204 |
| POST | `/v1/posts` | Compartilha voluntariamente uma aferição ou medalha própria |
| GET | `/v1/feed` | Publicações próprias e de acompanhados aprovados |
| GET / PUT / DELETE | `/v1/posts/{id}` | Consulta autorizada; edição de legenda/exclusão somente pelo autor |
| GET | `/v1/me/badges` | Catálogo, progresso, sequência atual, maior sequência e conquistas |
| GET | `/v1/me/badges/{id}` | Uma conquista própria; o ID é o de `earned_badges` |
| GET | `/v1/notifications` | Caixa privada de notificações internas |
| PUT | `/v1/notifications/{id}/read` | Marca notificação própria como lida; idempotente, 204 |

## Perfil

```json
{
  "name": "Ana",
  "username": "ana_silva",
  "timeZone": "America/Sao_Paulo",
  "birthdayMonth": 10,
  "birthdayDay": 21
}
```

Nome de usuário: 3–30 caracteres, letras ASCII minúsculas, dígitos e `_`; único. Aniversário é opcional, mas mês e dia precisam ser informados juntos e formar uma data válida, considerando 29/02 válido. PUT substitui os campos editáveis; omitir aniversário remove a configuração. E-mail/foto iniciais vêm da identidade autenticada, não do corpo enviado.

Contato de emergência, em seu endpoint próprio:

```json
{"name":"Maria","phone":"+5511999991234","relationship":"Mãe"}
```

Telefone normalizado com prefixo internacional, sem espaços; vínculo é opcional. Perfil público contém apenas `id`, `name`, `username`, `photoUrl`.

## Aferições

POST `/v1/readings`:

```json
{
  "id": "0f6bd5c8-d10d-4df3-83ad-ece14d76199d",
  "glucoseMgDl": 94,
  "insulinUnits": 2.5,
  "moment": "fasting",
  "measuredAt": "2026-10-20T10:30:00Z",
  "timeZone": "America/Sao_Paulo"
}
```

`id` pode ser omitido para geração no servidor. `insulinUnits` pode ser nulo/omitido; aceita até 5 dígitos inteiros e 3 casas decimais. Números JSON usam ponto decimal, independentemente do idioma do aparelho. Não aceitar `userId` do cliente; o dono vem do token. Campos desconhecidos são rejeitados.

Momentos: `fasting`, `after_breakfast`, `after_lunch`, `after_snack`, `after_dinner`, `other`. Não enviar rótulos traduzidos.

A resposta inclui `id`, `userId`, os campos registrados, `localDate`, `version`, `createdAt` e `updatedAt`. PUT usa os mesmos campos editáveis mais `version`; não aceita trocar `id` ou dono. POST repetido com o mesmo ID e os mesmos dados devolve o registro; ID reutilizado com outro conteúdo retorna 409. Datas futuras são rejeitadas. A aferição usa o dia do fuso informado, sem recalcular o histórico ao mudar o fuso do perfil.

### Unicidade das aferições

Cada usuário pode registrar **uma aferição por momento e dia local** (exceto `other`).
Mudar glicemia, insulina ou horário não libera um segundo registro para o mesmo
momento/dia. A data é `localDate`, calculada pelo servidor no fuso da aferição;
mudar o fuso do perfil não altera os dias históricos. Além disso, a mesma combinação
de usuário, glicemia, momento e instante não pode ser repetida usando outro fuso.
A insulina não participa da identidade da aferição.

`other` permite vários registros no mesmo dia. Nesse momento, só é proibido
repetir o mesmo instante para o mesmo usuário, independentemente de glicemia,
insulina ou fuso; a precisão é de microssegundos, igual ao banco. A regra também
vale em edições. A migração V4 substitui o limite diário de `other` por esse índice.

POST com outro ID ou PUT que viole a regra retorna **409 `duplicate_reading`**.
Repetir o mesmo ID com o mesmo conteúdo continua idempotente. A regra vale também
para envios simultâneos e é reforçada por índices únicos no banco.
Após excluir um registro, é possível registrar novamente esse momento/dia.
A migração V3 não remove registros: bases com duplicações anteriores precisam
resolvê-las antes de aplicar os índices.

Filtros: `from=2026-10-01`, `to=2026-10-21`, `moment=fasting`; combináveis, com extremos inclusivos na data local de cada aferição. Os mesmos filtros existem em `/summary`. Sem registros, resumo retorna `count: 0`, `averageGlucoseMgDl: null` e `latest: null`.

## Acompanhamento

A solicita acompanhar B. B é o destinatário; apenas B decide. Para aceitar/recusar:

```json
{"accept":true,"version":0}
```

Para cancelar, enviar `{"version":0}`. A resposta contém `id`, `requesterId`, `targetId`, `status`, `version` e timestamps. Consultas de relações adicionam os perfis públicos dos dois lados.

`direction`: `incoming` ou `outgoing`. `status`: `pending`, `accepted`, `declined`, `cancelled`, `ended`. Novas solicitações depois de recusa/cancelamento/encerramento reutilizam o vínculo e incrementam a versão. Repetir uma pendência não cria nova notificação. Uma decisão sobre versão antiga retorna 409.

O vínculo é direcional. Aprovação concede acesso às publicações compartilhadas anteriores e futuras do acompanhado, enquanto o vínculo continuar aceito. Não concede acesso ao endpoint privado de aferições, contato de emergência ou progresso de medalhas.

Bloqueio em qualquer direção encerra/cancela vínculos nas duas direções e impede busca, perfil público, solicitações e leitura social entre o par. Desbloquear não restaura a aprovação.

## Feed

POST `/v1/posts`:

```json
{"type":"reading","referenceId":"0f6bd5c8-d10d-4df3-83ad-ece14d76199d","caption":"Registro de hoje"}
```

`type` pode ser `reading` ou `badge`; neste último caso, `referenceId` é a conquista do usuário, não a definição da medalha. `caption` é opcional, até 1.000 caracteres. Uma origem tem no máximo uma publicação. Repetir o compartilhamento devolve a publicação original, sem modificar a legenda; usar PUT para editar.

Itens têm `id`, `author` (perfil público), `type`, `caption`, timestamps e um dos campos `reading` ou `badge`. Não há cópia dos valores clínicos na publicação; editar a aferição atualiza sua apresentação no feed. Remover publicação mantém a origem privada. Excluir aferição remove a publicação vinculada.

O feed é cronológico por publicação, não pela data de aferição. Não há ranking, curtidas ou comentários nesta versão. Leitura por ID/link aplica a mesma autorização da lista. Revogar vínculo ou bloquear retira acesso no servidor.

## Medalhas

Catálogo inicial: primeira aferição; 10/50/100 aferições; 3/7/14/30 dias consecutivos; aniversário e Ano Novo anuais.

- Quantidade conta registros; sequência conta datas locais distintas.
- Sem registro hoje, a sequência pode continuar ativa se houver registro ontem; antes disso, sequência atual é zero.
- Registros retroativos e edições preenchem lacunas e reavaliam conquistas.
- Anuais usam o ano de ocorrência; aniversário só participa se configurado e 29/02 não tem substituição automática.
- Medalhas já conquistadas são preservadas ao editar/excluir aferições; progresso atual é recalculado.
- Nenhum critério usa a glicemia ou a dose de insulina. Registrar/conquistar não publica automaticamente.
- A avaliação segura ocorre no servidor, na transação da alteração, com lock por usuário e unicidade de `(user,badge,edition)`.

`/v1/me/badges` retorna `readingCount`, `currentStreak`, `longestStreak` e `badges`. Cada item contém definição, progresso atual, melhor progresso, `achieved` e conquistas por edição. Para medalhas anuais, `achieved` refere-se ao ano corrente do perfil; `awards` mantém anos anteriores. Definições retornam códigos estáveis para tradução no Flutter.

## Paginação e erros

Aferições, feed, relações e notificações usam `limit` (1–50, padrão 20) e `cursor` opaco. Resposta: `{"items": [...], "nextCursor": "..."}`; `nextCursor: null` encerra a página. Não enviar o cursor de uma consulta em outra combinação de filtros. Busca de usuários e lista de bloqueios são limitadas; a paginação completa dessas duas listas pode ser acrescentada quando necessária.

Erros de domínio usam `application/problem+json` com `status`, `title` e `code` estável. Validação inclui nomes dos campos em `fields`, sem ecoar os valores. O filtro de autenticação pode devolver 401/403 sem esse corpo; tratar primeiro o status HTTP.

| Status | Exemplo |
|---|---|
| 400 | `validation_failed`, `invalid_date_range`, `invalid_time_zone`, `future_reading`, `invalid_cursor` |
| 401 | Access token ausente, assinatura inválida, expirado ou claims inválidas |
| 404 | Recurso inexistente ou sem autorização para consultá-lo |
| 409 | `stale_version`, `constraint_conflict`, `already_following`, `reading_id_reused`, `duplicate_reading` |

Respostas com dados privados não devem ser armazenadas em caches compartilhados. No cliente, isolar caches por usuário, remover dados sociais após bloqueio/revogação e limpar/segregar no logout. Conteúdo de notificações usa tipos/códigos, nunca frases persistidas em um idioma específico.
