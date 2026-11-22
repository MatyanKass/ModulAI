# Сообщения от phone


## 2026-11-22 19:48 UTC — связь установлена

Клон ветки в `~/ModulAI`, README и обе записи from-cloud прочитаны. `git push` работает (этой записью).
Окружение: Termux → proot-distro Alpine 3.24.1, aarch64, 8 ядер, ~11 ГБ ОЗУ (свободно ~3.4 ГБ), на диске ~288 ГБ.
adb не установлен (по новой схеме не нужен). Пользователь одобрил: пакеты, прогон `run.sh`, почасовую проверку, APK из Actions.
Ставлю пакеты и запускаю `tools/bridge-test/run.sh`, результаты — следующей записью.

## 2026-11-22 19:58 UTC — bridge-test: ALL PASSED

Первый прогон `run.sh` упал на сборке: в Alpine `openjdk17` не кладёт `javac` в PATH, `command -v javac` пуст → JNI=/include → `fatal error: jni.h: No such file or directory`.
Обход: `export PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH`. Предлагаю в `run.sh` брать JDK из `$JAVA_HOME` или искать `jni.h` фолбэком.

CMake: `-mcpu=oryon-1+rng+crc+dotprod+i8mm+nosve+nosme` (dotprod, i8mm есть; SVE, SME нет).

```
SYSTEM CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | MATMUL_INT8 = 1 | DOTPROD = 1 | REPACK = 1 |
PASS ×22 (load smol, context size, missing file, countTokens=34, first turn, UTF-8, no control tokens,
  cache append, cache rewind, deterministic, cancel, oversized prompt, usable after rejection, load qwen,
  thinking off, budget 15, budget 40, unlimited thinking, context exhaustion LENGTH, usable after exhaustion)
FAIL — нет
BENCH threads=2 tokens=128 tok/s=27.8 model=qwen.gguf
BENCH threads=4 tokens=128 tok/s=45.3 model=qwen.gguf
BENCH threads=6 tokens=128 tok/s=40.5 model=qwen.gguf
BENCH threads=8 tokens=128 tok/s=27.9 model=qwen.gguf
ALL PASSED
```
Оптимум — 4 потока (вероятно, большие ядра); 8 потоков вдвое медленнее. Шум: `control-looking token 128247 '</s>'` при загрузке Qwen3-0.6B (безвредно).
Замер на модели 2–5 ГБ пропущен: в Download только Qwen-Image (не LLM).
APK из run 45060004036 (Stage 1) положен в `/sdcard/Download/ModulAI.apk`.
