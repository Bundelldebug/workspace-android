$ErrorActionPreference = "Stop"

# Pure Java + aapt2/d8 build (no Gradle), same toolchain as Prism_Music_Android.
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$DistDir = Join-Path $ProjectRoot "dist"
$ApkName = "Workspace_Android.apk"

# ASCII-only staging path so native SDK tools never see the Korean project folder name
$StageRoot = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Temp\Workspace_Android_Build'))
if ((Split-Path $StageRoot -Leaf) -ne 'Workspace_Android_Build') { throw 'Unsafe staging path' }
if (Test-Path -LiteralPath $StageRoot) { Remove-Item -LiteralPath $StageRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $StageRoot | Out-Null
Copy-Item -Recurse -Force (Join-Path $ProjectRoot "app") (Join-Path $StageRoot "app")
$AppDir = Join-Path $StageRoot "app\src\main"
$BuildDir = Join-Path $StageRoot "build"

$ToolchainBase = $env:PLAY_TOOLCHAIN
if (-not $ToolchainBase) { $ToolchainBase = $env:PRISM_TOOLCHAIN }
$ToolchainFile = Join-Path $ProjectRoot "toolchain.path"
if (-not $ToolchainBase -and (Test-Path -LiteralPath $ToolchainFile)) { $ToolchainBase = (Get-Content -LiteralPath $ToolchainFile -TotalCount 1).Trim() }
if (-not $ToolchainBase) { throw "Set PLAY_TOOLCHAIN or create toolchain.path" }
$JavaHome = Join-Path $ToolchainBase "jdk21\jdk-21.0.13+9"
$BuildTools = Join-Path $ToolchainBase "build-tools-35.0.0\android-15"
$AndroidJar = Join-Path $ToolchainBase "platforms-android-35\android-35\android.jar"
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$BuildTools;$env:PATH"
$Javac = Join-Path $JavaHome "bin\javac.exe"
$JarTool = Join-Path $JavaHome "bin\jar.exe"
$KeyTool = Join-Path $JavaHome "bin\keytool.exe"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"

foreach ($d in @("compiled_res", "gen", "classes", "dex")) { New-Item -ItemType Directory -Force -Path "$BuildDir\$d" | Out-Null }
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

Write-Host "[1/5] aapt2 compile + link"
$ResZip = Join-Path $BuildDir "compiled_res\res.zip"
& $Aapt2 compile --dir "$AppDir\res" -o $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
$UnsignedApk = Join-Path $BuildDir "unsigned.apk"
& $Aapt2 link -I $AndroidJar --manifest "$AppDir\AndroidManifest.xml" --java "$BuildDir\gen" -o $UnsignedApk $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[2/5] javac"
$JavaFiles = @()
$JavaFiles += Get-ChildItem -Recurse -Filter "*.java" "$AppDir\java" | Select-Object -ExpandProperty FullName
$JavaFiles += Get-ChildItem -Recurse -Filter "*.java" "$BuildDir\gen" | Select-Object -ExpandProperty FullName
$SourceListFile = Join-Path $BuildDir "sources.txt"
[System.IO.File]::WriteAllLines($SourceListFile, $JavaFiles, (New-Object System.Text.UTF8Encoding($false)))
& $Javac --release 11 -encoding UTF-8 -Xlint:-options -cp $AndroidJar -d "$BuildDir\classes" "@$SourceListFile"
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "[3/5] d8"
$ClassesJar = Join-Path $BuildDir "classes.jar"
& $JarTool cf $ClassesJar -C "$BuildDir\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar creation failed" }
& $D8 --release --min-api 31 --lib $AndroidJar --output "$BuildDir\dex" $ClassesJar
if ($LASTEXITCODE -ne 0) { throw "d8 dexing failed" }

Write-Host "[4/5] package + zipalign"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::Open($UnsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    Get-ChildItem -Filter "*.dex" "$BuildDir\dex" | ForEach-Object {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Zip, $_.FullName, $_.Name, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    $AssetsDir = Join-Path $AppDir "assets"
    if (Test-Path -LiteralPath $AssetsDir) {
        $BaseLen = $AssetsDir.Length + 1
        Get-ChildItem -Recurse -File $AssetsDir | ForEach-Object {
            $Rel = $_.FullName.Substring($BaseLen).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Zip, $_.FullName, "assets/$Rel", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }
} finally { $Zip.Dispose() }
$AlignedApk = Join-Path $BuildDir "aligned.apk"
& $ZipAlign -f -P 16 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[5/5] sign"
$Keystore = Join-Path $StageRoot "keystore.jks"
$PersistKeystore = Join-Path $ProjectRoot "keystore.jks"
if (Test-Path $PersistKeystore) {
    Copy-Item $PersistKeystore $Keystore -Force
} else {
    & $KeyTool -genkeypair -keystore $Keystore -alias workspace -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android -dname "CN=OneUI Workspace, OU=OneUI Book, O=OneUI Book, L=Seoul, C=KR"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    Copy-Item $Keystore $PersistKeystore -Force
}
$SignedApk = Join-Path $BuildDir "signed.apk"
& $ApkSigner sign --ks $Keystore --ks-key-alias workspace --ks-pass pass:android --key-pass pass:android --out $SignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $ApkSigner verify $SignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$FinalApk = Join-Path $DistDir $ApkName
Copy-Item $SignedApk $FinalApk -Force
Copy-Item $SignedApk (Join-Path $ProjectRoot $ApkName) -Force
$SizeMB = [Math]::Round((Get-Item $FinalApk).Length / 1MB, 2)
Write-Host "BUILD SUCCESSFUL: $FinalApk ($SizeMB MB)"
