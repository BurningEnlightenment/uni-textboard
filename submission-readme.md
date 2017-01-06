# Über dieses Dokument
Ursprünglich mit UTF-8 kodiert und bedient sich des markdown Stils.

# Vorraussetzungen
- JDK & JRE 1.8


# Kompilation & Ausführung
Ich habe ein 10 Zeilen [gradle](https://gradle.org) build script
mitgeliefert. Mithilfe des sogenannten gradle wrappers kann das Projekt
über die Kommandozeile per `gradlew jar` (windows) oder `./gradlew jar`
(linux) in eine jar Datei kompiliert werden, welche sich danach in dem
Verzeichnis `build/libs` befindet. Die jar Datei kann dann ganz normal
mit `java -jar TextBoard.jar` ausgeführt werden.

Alternativ kann das Programm auch manuell mit `javac` kompiliert werden.
Um dies ein wenig zu erleichtern, befindet sich im TextBoard Verzeichnis
die `src.lst` Datei, welche die Pfade sämtlicher Quelldateien beinhaltet
und mit `javac` wie folgt benutzt wird: `javac @src.lst` (angenommen die
Datei befindet sich im aktuellen Verzeichnis und `javac` im `exe path`).
Die resultierenden `class files` können dann ohne Weiteres ausgeführt
werden: `java onl.gassmann.textboard.server.Program`


# Konfiguration
Sämtliche Optionen können sowohl per Konfigurationsdatei als auch über
Kommandozeilenargumente spezifiziert werden. Wobei die über die
Kommandozeile konfigurierten Werte Vorrang vor denen in der
Konfigurationsdatei haben. Es ist zu beachten, dass sowohl Schlüssel als
auch Werte case sensitive interpretiert und auch keine Leerzeichen o.ä.
entfernt werden (u.U. tut dies der Kommandozeileninterpreter!).

Die Konfigurationsdatei ist eine einfache UTF-8 Text Datei, welche
`server.cfg` heißen und sich im Ausführungsverzeichnis befinden muss. In
Dieser kann pro Zeile eine Option der Form `key=value` spezifiziert werden.

Wenn die Kommandozeile zur Konfiguration benutzt wird, so müssen die
Argumente die Form `--key=value` haben und etwaige Leerzeichen im `value`
Teil müssen escaped werden (z.B. `--key="va lue"` oder `"--key=va lue"`).

Die Programmoptionen sind:
* `port`: eine positive Ganzzahl kleiner 65536; der Port an dem der
Server neue Verbindungen akzeptiert. Standardwert: `4242`
* `database_directory`: relativer (zum Ausführungsverzeichnis) oder
absoluter Verzeichnispfad in welchem sich die Datenbank befindet oder
angelegt werden soll. Standardwert: Ausführungsverzeichnis
* `charset`: String. Spezifiziert den Zeichensatz mit welchem die Netz-
werkkommunikation kodiert wird. Standardwert: Systemstandard

Zudem verwendet das Projekt die Standard Java log Klassen, welche über
Standard Java `*.properties` Dateien konfiguriert werden kann. Ich habe
der Distribution eine Beispielkonfiguration beigelegt, deren Nutzung
explizit per `-Djava.util.logging.config.file=logging.properties`
aktiviert werden muss.

