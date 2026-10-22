# Dicionário do banco de dados

Contrato consolidado das migrações V1–V6, atualizado em 22/10/2026. A presença de uma migração no código não comprova que ela já foi aplicada em um ambiente remoto.

## Escopo e convenções

As dez tabelas de domínio ficam no schema privado `glicnote`. Não há acesso direto para anon/authenticated/service_role: a API valida o JWT do Supabase e aplica propriedade e permissões. Datas `timestamptz` representam instantes; `date` é calendário sem horário. “Obrigatório” refere-se ao banco, não necessariamente a um campo editável na interface. PK implica NOT NULL. Campos opcionais podem ser null; valores padrão não impedem null quando não há NOT NULL.

Identidades, sessões e OAuth são gerenciados pelo Supabase Auth (`auth.users` e tabelas internas), não pelas migrações de domínio. Não há senhas Google ou tokens nas tabelas abaixo. Flyway mantém sua tabela técnica `flyway_schema_history`; consulte o final.

## profiles

Perfil privado do usuário, vinculado por UUID à identidade do Supabase Auth. O banco não possui FK para auth.users: a sincronização da identidade ocorre na API.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `id` | `uuid` | Sim | `—` | UUID da identidade Supabase Auth (subject do JWT), PK sem FK para auth.users. |
| `name` | `varchar(120)` | Não | `—` | Nome de exibição; no perfil vem da identidade Google. |
| `username` | `varchar(30)` | Não | `—` | Nome público único (3–30 letras minúsculas, números ou underscore). |
| `email` | `varchar(320)` | Não | `—` | E-mail da identidade Google, privado e somente leitura na interface. |
| `photo_url` | `varchar(2048)` | Não | `—` | URL do avatar Google; API aceita HTTPS. |
| `time_zone` | `varchar(80)` | Sim | `'UTC'` | Fuso IANA; em aferições preserva o fuso usado ao registrar. |
| `birthday_month` | `smallint` | Não | `—` | Mês do aniversário, 1–12; compatibilidade e medalhas. |
| `birthday_day` | `smallint` | Não | `—` | Dia válido no mês (referência ano 2000 permite 29/02). |
| `created_at` | `timestamptz` | Sim | `now()` | Instante de criação (com fuso). |
| `updated_at` | `timestamptz` | Sim | `now()` | Instante da última atualização. |
| `birth_date` | `date` | Não | — | Nascimento completo opcional; idade calculada, não armazenada. |
| `diabetes_type` | `varchar(10)` | Não | — | Tipo opcional: type_1 ou type_2. Informação de saúde privada. |

`id` coincide com o subject do Auth. `username` é UNIQUE. Mês/dia devem ser ambos nulos ou válidos; se birth_date estiver preenchido, devem coincidir com ele. A API rejeita nascimento futuro. Nome/e-mail não são editáveis pelo cadastro complementar. O perfil público limita-se a id, nome, username e foto.

## emergency_contacts

Um contato de emergência privado por usuário.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `user_id` | `uuid` | Sim | `—` | Dono do registro; referencia profiles.id. |
| `name` | `varchar(120)` | Sim | `—` | Nome do contato de emergência, informado pelo usuário. |
| `phone` | `varchar(16)` | Sim | `—` | Telefone internacional E.164, + e 7–15 dígitos, primeiro não zero. |
| `relationship` | `varchar(80)` | Não | `—` | Relação com o usuário, como familiar ou responsável. |
| `created_at` | `timestamptz` | Sim | `now()` | Instante de criação (com fuso). |
| `updated_at` | `timestamptz` | Sim | `now()` | Instante da última atualização. |

user_id é PK e FK: no máximo um contato por perfil. Exclusão do perfil remove o contato.

## readings

Aferições de glicemia e insulina opcional, com instante e calendário local preservados.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `id` | `uuid` | Sim | `—` | Identificador UUID da entidade. |
| `user_id` | `uuid` | Sim | `—` | Dono do registro; referencia profiles.id. |
| `glucose_mg_dl` | `integer` | Sim | `—` | Glicemia inteira positiva em mg/dL. |
| `insulin_units` | `numeric(8,3)` | Não | `—` | Insulina em UI, opcional, não negativa; numeric(8,3). |
| `moment` | `varchar(30)` | Sim | `—` | fasting, after_breakfast, after_lunch, after_snack, after_dinner ou other. |
| `measured_at` | `timestamptz` | Sim | `—` | Instante real da aferição, armazenado com fuso. |
| `time_zone` | `varchar(80)` | Sim | `—` | Fuso IANA; em aferições preserva o fuso usado ao registrar. |
| `local_date` | `date` | Sim | `—` | Dia da aferição no fuso histórico, usado nas regras e medalhas. |
| `version` | `integer` | Sim | `0` | Versão para controle de concorrência. |
| `created_at` | `timestamptz` | Sim | `now()` | Instante de criação (com fuso). |
| `updated_at` | `timestamptz` | Sim | `now()` | Instante da última atualização. |

Índices: readings_timeline(user_id, measured_at DESC, id DESC), readings_calendar(user_id, local_date, moment). V3/V4 definem UNIQUE(user_id, local_date, moment) somente quando moment <> other e UNIQUE(user_id, measured_at) somente para other; também existe UNIQUE(user_id, glucose_mg_dl, moment, measured_at). UNIQUE(id,user_id) suporta FKs compostas de propriedade. Exclusão do perfil remove as aferições.

## follows

Solicitações direcionadas de acompanhamento e seu estado atual.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `id` | `uuid` | Sim | `gen_random_uuid()` | Identificador UUID da entidade. |
| `requester_id` | `uuid` | Sim | `—` | Quem solicita acompanhar; FK para profiles. |
| `target_id` | `uuid` | Sim | `—` | Usuário que recebe a solicitação; FK para profiles. |
| `status` | `varchar(15)` | Sim | `—` | pending, accepted, declined, cancelled ou ended. |
| `version` | `integer` | Sim | `0` | Versão para controle de concorrência. |
| `requested_at` | `timestamptz` | Sim | `now()` | Data da solicitação atual. |
| `responded_at` | `timestamptz` | Não | `—` | Data da resposta à solicitação. |
| `ended_at` | `timestamptz` | Não | `—` | Data de encerramento do acompanhamento. |

UNIQUE(requester_id,target_id); não permite solicitar a si mesmo. Índices follows_incoming(target_id,status,requested_at DESC) e follows_outgoing(requester_id,status,target_id). Exclusão de qualquer perfil remove a relação. Aceitação não é recíproca.

## blocks

Bloqueios direcionados entre usuários.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `blocker_id` | `uuid` | Sim | `—` | Usuário que bloqueia; FK para profiles. |
| `blocked_id` | `uuid` | Sim | `—` | Usuário bloqueado; FK para profiles. |
| `created_at` | `timestamptz` | Sim | `now()` | Instante de criação (com fuso). |

PK composta(blocker_id,blocked_id); não permite bloquear a si mesmo. Exclusão de qualquer perfil remove o bloqueio. Permissões da API consideram bloqueios nas interações.

## special_dates

Calendário de referência para medalhas de datas especiais.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `id` | `uuid` | Sim | `gen_random_uuid()` | Identificador UUID da entidade. |
| `code` | `varchar(80)` | Sim | `—` | Código estável único, independente do idioma. |
| `kind` | `varchar(20)` | Sim | `—` | fixed_annual (todo ano), specific_date (data única) ou birthday (aniversário do usuário). |
| `month` | `smallint` | Não | `—` | Mês para data anual fixa. |
| `day` | `smallint` | Não | `—` | Dia para data anual fixa. |
| `specific_date` | `date` | Não | `—` | Data completa quando kind=specific_date. |
| `active` | `boolean` | Sim | `true` | Indica se a definição está habilitada. |

code é UNIQUE. birthday não usa month/day/specific_date; fixed_annual exige mês/dia válidos e specific_date null; specific_date exige data completa e mês/dia null.

## badges

Catálogo de medalhas e regras de concessão.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `id` | `uuid` | Sim | `gen_random_uuid()` | Identificador UUID da entidade. |
| `code` | `varchar(80)` | Sim | `—` | Código estável único, independente do idioma. |
| `icon_key` | `varchar(80)` | Sim | `—` | Chave estável do ícone; não é arquivo binário. |
| `rule_type` | `varchar(20)` | Sim | `—` | reading_count, day_streak ou special_date. |
| `target` | `integer` | Não | `—` | Meta positiva de quantidade ou dias consecutivos. |
| `special_date_id` | `uuid` | Não | `—` | FK para special_dates; usada em regras de data especial. |
| `recurrence` | `varchar(10)` | Sim | `'once'` | once ou annual. |
| `rule_version` | `integer` | Sim | `1` | Versão da regra; em earned_badges captura a versão usada na concessão. |
| `sort_order` | `integer` | Sim | `0` | Ordem de apresentação no catálogo. |
| `active` | `boolean` | Sim | `true` | Indica se a definição está habilitada. |

code é UNIQUE. reading_count/day_streak exigem target > 0, sem special_date_id e recurrence=once. special_date exige referência e target null. Datas referenciadas não têm exclusão em cascata.

## earned_badges

Medalhas efetivamente concedidas ao usuário, preservando edição e versão da regra.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `id` | `uuid` | Sim | `gen_random_uuid()` | Identificador UUID da entidade. |
| `user_id` | `uuid` | Sim | `—` | Dono do registro; referencia profiles.id. |
| `badge_id` | `uuid` | Sim | `—` | FK para badges; identifica a medalha concedida. |
| `edition` | `varchar(20)` | Sim | `—` | Edição que diferencia recorrências da mesma medalha. |
| `rule_version` | `integer` | Sim | `—` | Versão da regra; em earned_badges captura a versão usada na concessão. |
| `source_reading_id` | `uuid` | Não | `—` | Aferição de origem, pertencente ao mesmo usuário; pode ficar null se ela for excluída. |
| `achieved_at` | `timestamptz` | Sim | `—` | Instante em que o requisito foi atingido. |
| `earned_at` | `timestamptz` | Sim | `now()` | Instante em que a concessão foi persistida. |

UNIQUE(user_id,badge_id,edition) impede concessão repetida. UNIQUE(id,user_id) permite validar propriedade no feed. FK composta(source_reading_id,user_id) garante origem do mesmo usuário; excluir a aferição limpa somente source_reading_id e preserva a conquista. Excluir perfil remove conquistas; catálogo referenciado não é removido em cascata.

## posts

Publicações explícitas de uma aferição ou medalha no feed.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `id` | `uuid` | Sim | `gen_random_uuid()` | Identificador UUID da entidade. |
| `author_id` | `uuid` | Sim | `—` | Autor da publicação; FK para profiles e dono da aferição/conquista. |
| `type` | `varchar(10)` | Sim | `—` | reading ou badge; determina a referência preenchida. |
| `reading_id` | `uuid` | Não | `—` | Aferição publicada; no máximo um post por aferição. |
| `earned_badge_id` | `uuid` | Não | `—` | Conquista publicada/notificada; FK para earned_badges. |
| `caption` | `varchar(1000)` | Não | `—` | Legenda opcional. |
| `created_at` | `timestamptz` | Sim | `now()` | Instante de criação (com fuso). |
| `updated_at` | `timestamptz` | Sim | `now()` | Instante da última atualização. |

type=reading exige reading_id e earned_badge_id null; type=badge exige earned_badge_id e reading_id null. Cada referência tem UNIQUE. FKs compostas garantem que o autor seja dono do conteúdo; exclusão do autor ou conteúdo remove o post. Índice posts_feed(author_id,created_at DESC,id DESC). Permissões do feed são aplicadas na API; criar aferição não significa publicá-la.

## notifications

Caixa de notificações de acompanhamento e conquistas.

| Campo | Tipo | Obrigatório | Padrão | Finalidade |
|---|---|---|---|---|
| `id` | `uuid` | Sim | `gen_random_uuid()` | Identificador UUID da entidade. |
| `recipient_id` | `uuid` | Sim | `—` | Destinatário privado da notificação; FK para profiles. |
| `actor_id` | `uuid` | Não | `—` | Usuário que originou a ação; FK para profiles. |
| `type` | `varchar(25)` | Sim | `—` | follow_requested, follow_accepted ou badge_earned. |
| `follow_id` | `uuid` | Não | `—` | Solicitação relacionada; FK para follows. |
| `request_version` | `integer` | Não | `—` | Versão da solicitação que originou o evento. |
| `earned_badge_id` | `uuid` | Não | `—` | Conquista publicada/notificada; FK para earned_badges. |
| `event_key` | `varchar(200)` | Sim | `—` | Chave única para impedir notificações duplicadas do mesmo evento. |
| `created_at` | `timestamptz` | Sim | `now()` | Instante de criação (com fuso). |
| `read_at` | `timestamptz` | Não | `—` | Instante em que a notificação foi lida; null significa não lida. |

type: follow_requested, follow_accepted ou badge_earned. Eventos de follow exigem follow_id/request_version e nenhuma conquista; badge_earned exige conquista e nenhum follow/version. event_key é UNIQUE. Índice notifications_inbox(recipient_id,created_at DESC,id DESC). Exclusão de destinatário, ator ou entidade referenciada remove notificações relacionadas.

## Catálogo inicial (V2)

Medalhas por 1, 10, 50 e 100 aferições; sequências de 3, 7, 14 e 30 dias. Datas: aniversário do usuário e ano novo (01/01), com medalhas anuais. Não há regra por valor “bom” de glicemia ou por dose de insulina.

## Evolução das migrações

| Versão | Alteração |
|---|---|
| V1 | Schema de domínio, dez tabelas, relações, índices e revogações de acesso. |
| V2 | Catálogo inicial de medalhas/datas. |
| V3 | Índices contra duplicidade de aferições. |
| V4 | Permite vários registros other por dia; bloqueia mesmo instante. |
| V5 | Nascimento completo opcional, coerente com mês/dia legado. |
| V6 | Tipo de diabetes opcional: type_1/type_2. |

## Tabela técnica flyway_schema_history

Gerenciada pelo Flyway, não é entidade de produto. Registra as migrações realmente executadas em cada banco. Não editar manualmente.

| Campo | Tipo usual PostgreSQL | Finalidade |
|---|---|---|
| installed_rank | integer, PK | Ordem de instalação. |
| version | varchar(50), opcional | Versão da migração. |
| description | varchar(200) | Descrição. |
| type | varchar(20) | Tipo de migração. |
| script | varchar(1000) | Arquivo executado. |
| checksum | integer, opcional | Integridade do arquivo. |
| installed_by | varchar(100) | Usuário do banco executor. |
| installed_on | timestamp | Instante de instalação. |
| execution_time | integer | Duração em milissegundos. |
| success | boolean | Resultado da instalação. |

Esse formato é gerenciado pela versão do Flyway; as migrações V1–V6 definem somente o domínio descrito acima.

## Manutenção

Fonte de verdade: `src/main/resources/db/migration/` na API. Documente novos campos, constraints, índices e permissões no mesmo trabalho que criar a migração. Não alterar arquivos já aplicados. O modelo de produto está em [modelagem-dominio.md](modelagem-dominio.md); decisões operacionais no harness do repositório Flutter.
