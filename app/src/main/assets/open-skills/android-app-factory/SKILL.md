---
name: android-app-factory
description: Factoría de aplicaciones Android nativas (Kotlin + Gradle, Compose opcional) que compila en este mismo teléfono, instala aquí mismo y verifica visualmente con capturas. Úsala para crear, compilar, firmar e instalar apps Android sin ordenador.
---

# android-app-factory — Factoría de Apps Android en este teléfono (Kotlin + Gradle)

Metodología para diseñar, programar, compilar, firmar, instalar y verificar aplicaciones nativas de Android ejecutando todo el pipeline dentro de este mismo teléfono (Debian en OpenCode). Versiones probadas aquí: AGP 8.5.2, Kotlin 1.9.24, compileSdk 35, JDK 21, Gradle 8.7.

Si el toolchain no está instalado, empieza por el paso 0. Solo hay que hacerlo una vez por dispositivo.

---

## 0. Toolchain (una vez por dispositivo)

```bash
open-factory-setup
```

Instala y deja verificado: JDK 21, Gradle 8.7 en `/opt/gradle-8.7`, SDK en `/opt/android-sdk` (platforms 34/35, cmdline-tools, platform-tools) y el `aapt2` ARM con su override en `~/.gradle/gradle.properties`. Al final imprime las versiones; si algo falla, lo dice.

Rutas fijas del toolchain (no las cambies: los proyectos las esperan):

- **JAVA_HOME**: `/usr/lib/jvm/java-21-openjdk-arm64`
- **Gradle**: `/opt/gradle-8.7/bin/gradle`
- **SDK**: `/opt/android-sdk` (`ANDROID_HOME` y `ANDROID_SDK_ROOT` apuntan aquí)
- **Override aapt2 ARM**: `android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.1/aapt2`

---

## 1. Estructura de Proyecto Canónica

```
MiApp/
├── settings.gradle
├── build.gradle
├── gradle.properties              # org.gradle.jvmargs=-Xmx3g
├── local.properties               # sdk.dir=/opt/android-sdk
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/autor/miapp/
```

`settings.gradle` (probado):

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MiApp"
include(":app")
```

`build.gradle` raíz (versiones probadas en este teléfono):

```groovy
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
```

`app/build.gradle` (Material o vistas clásicas; para Compose añade el plugin `org.jetbrains.kotlin.plugin.compose`):

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autor.miapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autor.miapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
```

---

## 2. Compilar e Instalar Aquí Mismo

```bash
cd ~/opencode/MiApp
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
/opt/gradle-8.7/bin/gradle :app:clean assembleDebug
```

Instalar en este teléfono de forma 100% autónoma (sobrevive a reinicios, con o sin cable, vía Puente Nativo A11y o ADB):

```bash
pc install app/build/outputs/apk/debug/*-debug.apk
```

(o `adb install -r ...` que redirige a `pc install` automáticamente si ADB está offline). Y lanzar:

```bash
pc open com.autor.miapp
```

Notas:

- Compila SIEMPRE con clean antes: en este filesystem Gradle reutiliza clases viejas.
- Primera compilación descarga dependencias (tarda y tira de red); las siguientes reutilizan caché.
- `isMinifyEnabled=false` siempre en debug (minify puede romper nativos sin stack legible).
- Tras reinstalar, Android puede revocar el permiso de "instalar desconocidas": la app debe pedirlo y reanudar.
- No apagues la pantalla en compilaciones largas; si se duerme el móvil, el sistema puede matar el proceso.
- Si falta espacio, limpia con `/opt/gradle-8.7/bin/gradle clean` y borra cachés viejas de `~/.gradle/caches/`.
- Registra cada app nueva en `inventory` con evidencia en la misma sesión.

---

## 3. Verificación Visual Autónoma

Nunca preguntes cómo se ve la app; compruébalo tú mismo:

```bash
pc shot /bridge/app.png
pc dump /bridge/app.xml
pc shell logcat -d | grep "TAG_DE_LA_APP"
```

Abre la imagen, verifica que no hay FATAL del paquete, toca con `pc tap X Y`, navega con `pc swipe`, escribe con `pc text "..."`.

---

## 4. Iconos y Firma Release

Iconos adaptativos con Python (Pillow). Guarda esto como `iconos.py` y lánzalo con la imagen base (mínimo 1024x1024):

```bash
pip install pillow
python3 iconos.py base.png app/src/main/res/
```

```python
import os, sys
from PIL import Image, ImageDraw

src = Image.open(sys.argv[1]).convert("RGB")
res = sys.argv[2]

mask_sq = Image.new("L", (4096, 4096), 0)
ImageDraw.Draw(mask_sq).rounded_rectangle((128, 128, 4096 - 128, 4096 - 128), radius=860, fill=255)
sq = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
sq.paste(src, (0, 0), mask_sq.resize((1024, 1024), Image.Resampling.LANCZOS))

for folder, size in {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
                     "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}.items():
    td = os.path.join(res, folder)
    os.makedirs(td, exist_ok=True)
    sq.resize((size, size), Image.Resampling.LANCZOS).save(os.path.join(td, "ic_launcher.png"))

fg = Image.new("RGB", (432, 432), (20, 23, 31))
fg.paste(src.resize((350, 350), Image.Resampling.LANCZOS), (41, 41))
os.makedirs(os.path.join(res, "drawable"), exist_ok=True)
fg.save(os.path.join(res, "drawable", "ic_launcher_foreground.png"))
print("Iconos generados.")
```

Declara en `res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

Keystore y firma release con el JDK del teléfono:

```bash
keytool -genkeypair -keystore release.keystore -alias appkey -keyalg RSA -keysize 2048 -validity 10000
/opt/gradle-8.7/bin/gradle assembleRelease
```

Para pasar el APK a alguien: cópialo al almacenamiento compartido y se comparte como un archivo normal:

```bash
cp app/build/outputs/apk/release/*-release.apk /sdcard/Download/
```

---

## 5. Reglas de Rendimiento

- Fuerza 120 Hz poniendo en `MainActivity.onCreate` el modo de más refresco (rodeado de try/catch):

```kotlin
try {
    val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION") window.windowManager.defaultDisplay
    }
    display?.supportedModes?.maxByOrNull { it.refreshRate }?.let {
        window.attributes = window.attributes.apply { preferredDisplayModeId = it.modeId }
    }
} catch (_: Throwable) {}
```

- Sin retardos artificiales en eventos táctiles; vibra solo al confirmar clic, no al apoyar el dedo.
- Servicios persistentes como foreground service con notificación silenciosa (en Android 14+, `foregroundServiceType="specialUse"`).

---

## 6. Logs en Tiempo Real

```bash
pc shell logcat -v time | grep "TAG_DE_LA_APP"
```
