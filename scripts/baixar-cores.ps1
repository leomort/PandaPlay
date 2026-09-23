# Baixa o core mGBA (GB/GBC/GBA) do buildbot oficial do libretro
# e coloca em app/src/main/jniLibs com o prefixo "lib" que o Android exige.
# Uso (PowerShell, na raiz do projeto):  .\scripts\baixar-cores.ps1

$ErrorActionPreference = "Stop"
$cores = @("mgba")
$abis  = @("arm64-v8a")   # adicione "armeabi-v7a" para aparelhos 32 bits (e no abiFilters do Gradle)
$base  = "https://buildbot.libretro.com/nightly/android/latest"
$tmp   = Join-Path $env:TEMP "pandaplay-cores"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

foreach ($abi in $abis) {
    $dest = "app/src/main/jniLibs/$abi"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    foreach ($core in $cores) {
        $file = "${core}_libretro_android.so"
        $zip  = Join-Path $tmp "$abi-$file.zip"
        Write-Host "Baixando $core ($abi)..."
        Invoke-WebRequest -Uri "$base/$abi/$file.zip" -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        Move-Item -Force (Join-Path $tmp $file) (Join-Path $dest "lib$file")
        Write-Host "  -> $dest/lib$file"
    }
}
Write-Host "Pronto."
