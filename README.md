# Warden Mic (Fabric, Minecraft Java 26.3)

Requisitos: JDK 25, Gradle 9.4+, Fabric Loader 0.19.5+, Fabric API 0.161.0+26.3.
Compilar: `gradle build` (o con GitHub Actions) -> .jar en `build/libs/`.

Mecánica:
- Tu micrófono sube una barra de alerta solo si un Warden está dentro del radio de audición.
- Al pasar la raya del 8/10 el Warden te persigue hasta que la barra baje a 25%.
- Fuera de la persecución, el Warden ignora vibraciones, olfato, cercanía y golpes.

Ajustes en `WardenMic.java`: THRESHOLD, radios, CHASE_START, CHASE_STOP, GAIN_*, *_DECAY.
Micrófono: `config/wardenmic.txt`.
