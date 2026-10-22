# Publicar a primeira API no Render

O Supabase Cloud já está criado e migrado. O Render executará somente a API Java.
Configuração usada: Docker, plano Free, health check `/health` e banco Supabase.

1. Crie a conta em https://render.com/ com Continue with GitHub e permita acesso
   ao repositório `MoraesFS-N/glicnote-api`.
2. No painel Render, escolha **New → Web Service**, conecte esse repositório e
   selecione a branch **feat/supabase-backend**. A branch main ainda contém a API antiga.
3. Nome: `glicnote-api` (o Render pode exigir outro nome disponível). Runtime:
   **Docker**. Root Directory: vazio. Dockerfile: `./Dockerfile`. Plano: **Free**.
4. Em Environment, configure:

   | Variável | Valor |
   |---|---|
   | `SUPABASE_URL` | `https://wwgdipdpiumvuibqlejr.supabase.co` |
   | `DB_URL` | `jdbc:postgresql://aws-0-us-east-2.pooler.supabase.com:5432/postgres?sslmode=require` |
   | `DB_USERNAME` | `postgres.wwgdipdpiumvuibqlejr` |
   | `DB_PASSWORD` | Copiar do `.env.cloud` local, mantendo segredo |
   | `DB_POOL_SIZE` | `5` |

   A API não precisa de chave secret/service_role nem do segredo OAuth Google.
   A porta interna segue a variável PORT do Render. CORS pode ficar vazio para o app nativo.
5. Em Advanced, defina **Health Check Path: `/health`** e desative deploy automático
   nesta primeira configuração. Clique em **Create Web Service / Deploy**.
6. Aguarde o status **Live**. Abra `https://SEU-SERVICO.onrender.com/health`;
   a resposta esperada é HTTP 200. Migrações já aplicadas não são repetidas.
7. Envie a URL pública da API para configurar `API_BASE_URL` no Flutter, recompilar
   e instalar o app. Isso remove a dependência do computador e do `adb reverse`.

O plano gratuito pode suspender o serviço por inatividade e demorar a responder
na primeira chamada seguinte. A tela de carregamento/erro do app permite tentar novamente.

## Política de privacidade

A página pública está em `/privacy.html`, sem login. **O texto é preliminar**:
completar contato de privacidade, retenção e bases legais antes de usá-lo como
política definitiva no Google OAuth/lojas. Depois, a URL HTTPS pública dessa página
pode ser cadastrada nos dados de marca do Google Auth Platform. O callback OAuth
continua sendo `https://wwgdipdpiumvuibqlejr.supabase.co/auth/v1/callback`; não é a URL do Render.

Referências: [Web Services](https://render.com/docs/web-services),
[Docker](https://render.com/docs/docker), [Plano gratuito](https://render.com/docs/free).
