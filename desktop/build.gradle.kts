// Reduction on a computer: the same conversation core as the phone app, served to a browser.
// The core's sources are compiled straight from the app module: one copy of the code, two hosts.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val core = "../app/src/main/java"

sourceSets {
    main {
        kotlin {
            srcDir(core)
            // Everything that does not need Android.
            include("com/mindtravel/reduction/desktop/**")
            include("com/mindtravel/reduction/core/**")
            include("com/mindtravel/reduction/model/DeepSeekHttp.kt")
            include("com/mindtravel/reduction/model/ModelGraph.kt")
            include("com/mindtravel/reduction/model/chat/**")
            include("com/mindtravel/reduction/model/voice/DeepSeekAudioApi.kt")
            include("com/mindtravel/reduction/model/voice/TtsSynthesizer.kt")
            include("com/mindtravel/reduction/model/voice/TtsCache.kt")
            include("com/mindtravel/reduction/feature/chat/**")
            include("com/mindtravel/reduction/feature/sync/DataSync.kt")
            include("com/mindtravel/reduction/feature/article/**")
            include("com/mindtravel/reduction/feature/memo/data/**")
            include("com/mindtravel/reduction/feature/memo/MemoCard.kt")
            include("com/mindtravel/reduction/feature/memo/MemoTool.kt")
            include("com/mindtravel/reduction/feature/memo/MemoView.kt")
            include("com/mindtravel/reduction/feature/voice/ReplyStreamer.kt")
            include("com/mindtravel/reduction/feature/voice/SentenceSplitter.kt")
            include("com/mindtravel/reduction/feature/voice/SpeechTextExtractor.kt")
            include("com/mindtravel/reduction/feature/voice/SpeechController.kt")
            include("com/mindtravel/reduction/feature/voice/SpokenCues.kt")
            include("com/mindtravel/reduction/feature/voice/EndWords.kt")
            include("com/mindtravel/reduction/hardware/audio/Loudness.kt")
            include("com/mindtravel/reduction/hardware/audio/SpeechOutput.kt")
            include("com/mindtravel/reduction/hardware/audio/WavPcm.kt")
            include("com/mindtravel/reduction/hardware/pc/PcGateway.kt")
            include("com/mindtravel/reduction/hardware/pc/PcInfo.kt")
            include("com/mindtravel/reduction/hardware/pc/PcProfile.kt")
        }
        resources {
            srcDir("../app/src/main/resources")
            // The page, the scripts that run sub agents, and the bundled cue recordings, as the phone has them.
            srcDir("../app/src/main/assets")
            srcDir("../app/src/main/res/raw")
            exclude("kws/**", "kws-en/**", "vad/**", "tts-aishell3/**")
        }
    }
}

val localTts = layout.buildDirectory.dir("local-tts")
val prepareTts by tasks.registering(Exec::class) {
    val script = rootProject.file("tools/models/fetch-models.sh")
    inputs.file(script)
    inputs.property("host", System.getProperty("os.name") + System.getProperty("os.arch"))
    outputs.dir(localTts)
    commandLine("bash", script, "--desktop")
}

dependencies {
    implementation(files(localTts.map { it.file("sherpa-onnx-jvm.jar") }).builtBy(prepareTts))
    runtimeOnly(files(localTts.map { it.file("sherpa-onnx-native.jar") }).builtBy(prepareTts))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.mindtravel.reduction.desktop.MainKt")
    applicationName = "reduction"
    // A plain IPv4 listener: the only kind Windows forwards "localhost" to when this runs inside WSL.
    applicationDefaultJvmArgs = listOf("-Djava.net.preferIPv4Stack=true")
}

distributions.main {
    contents.from(localTts.map { it.dir("tts-aishell3") }) {
        into("models/tts-aishell3")
    }
}

tasks.named<JavaExec>("run") {
    systemProperty("reduction.tts.dir", localTts.get().dir("tts-aishell3").asFile.absolutePath)
}
