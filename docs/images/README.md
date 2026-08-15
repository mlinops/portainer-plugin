# Скриншоты для hosting (Portainer plugin)

**Статус:** `config.png` + `build.png` на месте; опциональные `step-*.png` тоже добавлены (PORT-69 / M-HOST-1).  
**Запрещено:** placeholder / fake PNG, внутренние hostname, реальные токены / API keys.

Hosting / plugins.jenkins.io ожидает **реальные** PNG рядом с этим файлом. Ссылки уже заготовлены в корневом [`README.md`](../../README.md) (секция Screenshots).

---

## 1. Какие файлы нужны

### Обязательно для hosting (минимум)

| Файл | Где снимать | Что показывает |
|------|-------------|----------------|
| `config.png` | Manage Jenkins → **System** → секция **Portainer** | GlobalConfiguration |
| `build.png` | Job → **Configure** → Build Steps → **Portainer Stack Deployment** | Типичный build step |

Двух кадров достаточно, чтобы закрыть hosting-nit (как у GitLab parameter plugins: `{config,build}.png`).

### Опционально (не блокирует hosting)

Дополнительные кадры **не обязательны** для jenkinsci hosting. Имеет смысл только если хотите богаче selling README / plugins.jenkins.io:

| Файл (пример) | Где |
|---------------|-----|
| `step-secret.png` | Job configure → **Portainer Stack Secret** |
| `step-config.png` | Job configure → **Portainer Stack Config** |
| `step-manifest.png` | Job configure → **Portainer Manifest Deployment** |
| `step-helm.png` | Job configure → **Portainer Helm Deployment** |

**Рекомендация до hosting:** держать **минимальный набор из 2 PNG**. Остальные шаги описаны текстом и Pipeline-примерами в README.

**Не снимать** (не нужно для hosting):

- Build console / лог успешного деплоя (секреты, URL, шум)
- Credentials store с открытым Secret text
- Расширенные Vault Manual-поля с реальными URL/AppRole

---

## 2. Что должно быть видно на кадре

### `config.png` — System → Portainer

Показать заполненную секцию **Portainer**:

| Поле | Пример значения на скрине |
|------|---------------------------|
| Display name | `default` или `Production Portainer` |
| Portainer URL | `https://portainer.example:9443` (или `http://portainer.example:9000`) |
| API key credentials | выбранный credential **по ID/имени**, не значение токена |

Допустимо: свёрнутый блок Advanced (timeouts).  
Не нужно: Test connection (кнопки нет — preflight на билде).

### `build.png` — Job configure → Portainer Stack Deployment

Показать форму шага **Portainer Stack Deployment** с безопасными sample-полями:

| Что показать | Пример |
|--------------|--------|
| Connection | **Inherit from System** (предпочтительно) |
| Endpoint ID | например `1` |
| Stack type | Compose / Swarm (как в UI) |
| Stack name | `demo-stack` |
| Stack source | **Repository** |
| Repository URL | `https://gitlab.example/group/repo.git` |
| Compose file path | `docker-compose.yml` |
| Repository reference | `refs/heads/main` |

Vault на Stack: оставить **Not connected** (дефолт) — проще и без лишних секретных полей.  
Не разворачивать Manual Portainer URL + raw key на скрине.

### Общие правила содержимого

- Только хосты `*.example`: `portainer.example`, `gitlab.example`, `vault.example`
- Никаких внутренних FQDN / IP / VPN-имён
- Никаких plaintext токенов, паролей, YAML с секретами
- Credential picker показывает только ID/описание, не Secret value
- Тема Jenkins: светлая **или** тёмная — на выбор; главное читаемость полей

---

## 3. Где снимать

| Скрин | Путь в UI | Не путать с |
|-------|-----------|-------------|
| `config.png` | **Manage Jenkins → System** → прокрутить до **Portainer** | Manage link отдельной страницы нет; это GlobalConfiguration в System |
| `build.png` | **New Item** (Freestyle) → Configure → **Add build step** → **Portainer Stack Deployment** | Не Pipeline Blue Ocean; не Build History / Console |

Окружение: локальный Jenkins с установленным плагином (`mvn hpi:run`, docker compose из workspace, или test instance). Реальный Portainer для кадра **не обязателен** — достаточно заполненной формы.

---

## 4. Гигиена изображений

- [x] Обрезать до релевантной секции формы (без лишнего хедера/сайдбара, если мешает)
- [x] Разрешение достаточное для plugins.jenkins.io (читаемый текст полей)
- [x] PNG (не JPEG с артефактами)
- [x] Имена файлов точно: `config.png`, `build.png` (lowercase)
- [x] Нет watermark / IDE / браузерных password-менеджеров поверх формы
- [x] Проверить кадр глазами: нет токенов, нет внутренних URL
- [x] Светлая/тёмная тема — опционально; не нужны оба варианта для hosting

---

## 5. После съёмки: пути и README

1. Положить файлы сюда:
   - `portainer-plugin/docs/images/config.png`
   - `portainer-plugin/docs/images/build.png`
2. В корневом `README.md` секция **Screenshots**: заменить code-fence шаблон на live embeds:

```markdown
![Manage Jenkins → System → Portainer](docs/images/config.png)
![Job configure → Portainer Stack Deployment](docs/images/build.png)
```

3. Проверить превью README на GitHub (относительные пути `docs/images/…`).
4. Не коммитить пустые/1×1/сгенерированные «заглушки».

---

## 6. Чеклист (отмечать по ходу)

### Подготовка

- [x] Jenkins с этим плагином запущен локально
- [x] Credential **Secret text** создан (значение может быть dummy; на скрине видно только выбор ID)
- [x] В System → Portainer URL = `https://portainer.example` (или `:9443` / `:9000`)
- [x] Freestyle job создан, добавлен шаг **Portainer Stack Deployment** с `*.example` полями

### Съёмка

- [x] Снят и сохранён `docs/images/config.png`
- [x] Снят и сохранён `docs/images/build.png`
- [x] Кадры обрезаны, текст читаем, секретов нет
- [x] Опционально: `step-helm.png`, `step-manifest.png`, `step-config.png`, `step-secret.png`

### README / hosting

- [x] В `README.md` вставлены live `![…](docs/images/config.png)` и `build.png`
- [x] Code-fence «After the PNGs exist…» убран или заменён на реальные картинки
- [x] Опциональные step-*.png добавлены в README (не блокируют hosting)
- [x] Готово к PR / hosting (M-HOST-1 / PORT-69)

---

## Краткая шпаргалка

| Нужно | Не нужно |
|-------|----------|
| `config.png` + `build.png` | Fake/placeholder PNG |
| `*.example` URL | Внутренние hostname |
| Credential ID в select | Значение Access token |
| Job **configure** Stack | Console log билда |
| Live embeds в README | Пять скринов всех step’ов до hosting |

Tracked as **PORT-69** / review **M-HOST-1**.
