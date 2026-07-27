# Архив для GitHub: Create Car Winch

Этот архив добавляет в репозиторий конвертер `.bbmodel`, автоматическую загрузку моделей из папки `1` твоего GitHub и workflow, который собирает `.jar`.

## Вариант A: добавить файлы в существующий репозиторий

1. Скачай и распакуй архив.
2. Открой `https://github.com/Danzy-189/create-car-winch`.
3. Нажми **Add file -> Upload files**.
4. Перетащи в окно GitHub содержимое архива: папки `.github`, `tools` и файл `README_RU.md`.
5. Проверь, что получилось:

```text
.github/workflows/build.yml
tools/convert_bbmodel.py
tools/import_models.sh
build.gradle
gradle.properties
settings.gradle
src/
```

6. Нажми **Commit changes**.

## Вариант B: создать новый репозиторий

1. На GitHub нажми **New repository**.
2. Назови его, например, `create-car-winch-final`.
3. Создай репозиторий без README.
4. Загрузить нужно не только этот архив, а **исходный проект мода** из старого репозитория вместе с папками `.github` и `tools` из этого архива.
5. В корне обязательно должны быть `build.gradle`, `gradle.properties`, `settings.gradle` и `src`.

## Как получить jar

1. Открой вкладку **Actions**.
2. Выбери **Build Create Car Winch**.
3. Нажми **Run workflow** справа.
4. Выбери ветку `main`.
5. Нажми зелёную кнопку **Run workflow**.
6. Дождись зелёной галочки. Первый запуск может идти 5-15 минут.
7. Открой завершившийся запуск.
8. Прокрути вниз до **Artifacts**.
9. Скачай `create-car-winch-jar` и распакуй его.
10. Внутри будет `carwinch-1.0.0.jar`.

## Где окажутся модели

Workflow каждый раз берёт файлы из:

```text
https://github.com/Danzy-189/create-car-winch/tree/main/1
```

Он скачивает:

```text
winch.bbmodel
winch_1.bbmodel
towbar.bbmodel
towbar_1.bbmodel
winch.png
winch_drum.png
towbar.png
winch_hook.png
iron_rope.png
```

Затем автоматически конвертирует `.bbmodel` в Minecraft JSON и кладёт результат в `src/main/resources/assets/carwinch/models/block/`.

Важно: конвертер поддерживает кубы, UV-развёртку и простые вращения. Формат Blockbench `free` не всегда экспортируется в Minecraft идеально, поэтому после первой сборки обязательно проверь модели в игре.

## Если хочешь хранить модели прямо в новом репозитории

Создай в корне папки:

```text
models/
textures/
```

Положи туда свои `.bbmodel` и PNG. Затем измени в `.github/workflows/build.yml` шаг импорта на:

```yaml
- name: Import local models
  run: bash tools/import_models.sh
```

В `tools/import_models.sh` замени строки `curl` на копирование из локальных папок, либо оставь загрузку из старого репозитория.

## Установка jar в Minecraft

Нужен Minecraft Java Edition 1.21.1 с NeoForge и совместимыми Create и Create: Aeronautics. Положи `carwinch-1.0.0.jar` в:

```text
%appdata%\\.minecraft\\mods
```

Запускай профиль NeoForge 1.21.1. Simulated отдельно обычно не нужен, если он встроен в установленный Create: Aeronautics.

## Ошибки сборки

Если запуск красный, открой **Actions -> Build Create Car Winch -> Build jar**, прокрути вниз и пришли последние 30-50 строк лога. Красный крест чаще всего означает несовпадение версий Create/Aeronautics или ошибку исходников, а не проблему с моделями.
