package com.mindtravel.reduction.desktop

import java.io.File
import java.util.Base64

/** Give Windows a persistent file drop, plus bitmap data for image-aware paste targets. */
internal fun copyCardExport(file: File): Boolean = runCatching {
    val translate = ProcessBuilder("wslpath", "-w", file.absolutePath).start()
    val windowsPath = translate.inputStream.bufferedReader().readText().trim()
    check(translate.waitFor() == 0 && windowsPath.isNotEmpty())
    val encodedPath = Base64.getEncoder().encodeToString(windowsPath.toByteArray())
    val script = """
        ${'$'}ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName System.Drawing
        ${'$'}src = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encodedPath'))
        ${'$'}dir = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Reduction\exports'
        [IO.Directory]::CreateDirectory(${'$'}dir) | Out-Null
        ${'$'}file = Join-Path ${'$'}dir ([IO.Path]::GetFileName(${'$'}src))
        [IO.File]::Copy(${'$'}src, ${'$'}file, ${'$'}true)
        ${'$'}data = New-Object Windows.Forms.DataObject
        ${'$'}paths = New-Object Collections.Specialized.StringCollection
        [void]${'$'}paths.Add(${'$'}file)
        ${'$'}data.SetFileDropList(${'$'}paths)
        if ([IO.Path]::GetExtension(${'$'}file) -in @('.png','.jpg')) {
            ${'$'}image = [Drawing.Bitmap]::FromFile(${'$'}file)
            ${'$'}data.SetImage(${'$'}image)
        }
        try { [Windows.Forms.Clipboard]::SetDataObject(${'$'}data, ${'$'}true, 10, 100) }
        finally { if (${'$'}image) { ${'$'}image.Dispose() } }
    """.trimIndent()
    val command = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    ProcessBuilder("/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",
        "-NoProfile","-NonInteractive","-STA","-EncodedCommand",command)
        .redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
}.getOrDefault(false)
