# Desenvolvimento e deploy

Fluxo: `feat/<nome>` ou `fix/<nome>` → PR para `dev` → PR de `dev` para
`master`, usando merge commit. `master` é produção; não renomear a branch nem
usar force push. Após publicar, sincronize master de volta em dev por merge.

```bash
git fetch origin
git switch -c feat/minha-alteracao origin/dev
./scripts/test.sh
git push -u origin HEAD
# abrir PR para dev; após integração, abrir dev para master
```

API checks executa integração em PostgreSQL descartável e build Docker em PRs
e pushes de dev/master. Nunca aponta os testes para o banco Cloud.
O Render está declarado para publicar master após os checks passarem; a aplicação
dessa configuração ao serviço existente depende do painel/Blueprint. Consulte
[deploy-render.md](deploy-render.md). Dev não publica em produção.

Flyway executa migrações incrementais na inicialização. Não alterar migrações
aplicadas, limpar banco, resetar dados ou executar seed em produção. Mudanças de
contrato precisam ser compatíveis com o app instalado; publicar API antes do app.

Proteções recomendadas para dev/master: PR obrigatório, check `verify`, bloquear
force push e exclusão. Verifique as regras no GitHub para confirmar ativação.

Após publicar, verificar o SHA e status Live no Render e GET /health. Para rollback,
republique um SHA anterior compatível com o schema atual pelo Render. Rollback de
código não desfaz migrações; prefira uma migração corretiva incremental.
