$ErrorActionPreference = 'Stop'
# Robolectric native-graphics UI & core tests. Reuses the dependency cache of OneUI_Viewer_Android/tests/ui.
$UiRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $UiRoot '../..'))
$ViewerCache = [IO.Path]::GetFullPath((Join-Path $ProjectRoot '../OneUI_Viewer_Android/tests/ui/.cache'))
$Toolchain = $env:PLAY_TOOLCHAIN
if (-not $Toolchain) { $Toolchain = $env:PRISM_TOOLCHAIN }
$ToolchainFile = Join-Path $ProjectRoot 'toolchain.path'
if (-not $Toolchain -and (Test-Path -LiteralPath $ToolchainFile)) { $Toolchain = (Get-Content -LiteralPath $ToolchainFile -TotalCount 1).Trim() }
if (-not $Toolchain) { throw 'Set PLAY_TOOLCHAIN or create toolchain.path' }
$JavaHome = Join-Path $Toolchain 'jdk21/jdk-21.0.13+9'
$Stage = Join-Path $env:LOCALAPPDATA 'Temp\Workspace_Android_Build'
$Work = Join-Path $env:TEMP 'WorkspaceUiTest'
if (Test-Path -LiteralPath $Work) { Remove-Item -LiteralPath $Work -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$Work\test-classes", "$Work\out", "$Work\cfg\com\android\tools" | Out-Null

$Deps = @(Get-ChildItem (Join-Path $ViewerCache 'dependencies') -Filter '*.jar' | ForEach-Object FullName)
$Deps += @(Get-ChildItem $ViewerCache -Directory | ForEach-Object { Join-Path $_.FullName 'classes.jar' } | Where-Object { Test-Path $_ })
$AndroidJar = Join-Path $Toolchain 'platforms-android-35/android-35/android.jar'
$AppClasses = Join-Path $Stage 'build\classes'
$Manifest = Join-Path $Stage 'app\src\main\AndroidManifest.xml'
$ResApk = Join-Path $Stage 'build\unsigned.apk'
if (-not (Test-Path $ResApk)) { throw 'Run build.ps1 first' }

$props = "android_merged_manifest=$($Manifest.Replace('\','/'))`nandroid_resource_apk=$($ResApk.Replace('\','/'))`nandroid_custom_package=com.oneuibook.workspace`n"
[IO.File]::WriteAllText("$Work\cfg\com\android\tools\test_config.properties", $props)

& (Join-Path $JavaHome 'bin/javac.exe') --release 11 -encoding UTF-8 -cp ((@($AppClasses, $AndroidJar) + $Deps) -join ';') -d "$Work\test-classes" (Join-Path $UiRoot 'WorkspaceUiTest.java')
if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }
$Cp = (@($AppClasses, "$Work\test-classes", "$Work\cfg") + $Deps) -join ';'
& (Join-Path $JavaHome 'bin/java.exe') '--add-opens=java.base/java.io=ALL-UNNAMED' "-Duser.home=$ViewerCache/home" "-Dmaven.repo.local=$ViewerCache/m2" "-Djava.io.tmpdir=$Work" '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2' "-Dworkspace.output=$Work\out" -cp $Cp org.junit.runner.JUnitCore com.oneuibook.workspace.WorkspaceUiTest
$code = $LASTEXITCODE
New-Item -ItemType Directory -Force -Path (Join-Path $UiRoot 'output') | Out-Null
Copy-Item "$Work\out\*" (Join-Path $UiRoot 'output') -Force
if ($code -ne 0) { throw 'UI checks failed' }
