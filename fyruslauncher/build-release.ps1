param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$Sdk=$env:ANDROID_HOME,
    [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
if(!$SkipBuild){
    if(!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))){throw 'Pass -JavaHome pointing to an existing JDK 17/21.'}
    if(!$Sdk -or !(Test-Path -LiteralPath (Join-Path $Sdk 'platforms\android-34\android.jar'))){throw 'Pass -Sdk pointing to an existing Android SDK with API 34.'}
    $env:JAVA_HOME=$JavaHome
    $env:ANDROID_HOME=$Sdk
    $env:ANDROID_SDK_ROOT=$Sdk
    $env:GRADLE_USER_HOME=Join-Path $project '.build-cache\gradle'
    & (Join-Path $project 'launcher\gradlew.bat') -p (Join-Path $project 'launcher') testDebugUnitTest assembleDebug '-PlauncherApplicationId=com.fyrus.cleanyourtv.netflix' --console=plain
    if($LASTEXITCODE -ne 0){throw 'Gradle build/tests failed.'}
}
$apk=Join-Path $project 'launcher\app\build\outputs\apk\debug\app-debug.apk'
if(!(Test-Path -LiteralPath $apk)){throw 'Build the APK first.'}
$release=Join-Path $project 'release'
$output=Join-Path $release 'FyrusLauncher'
foreach($folder in @($output,(Join-Path $output 'app'),(Join-Path $output 'tools'),(Join-Path $output 'backups'),(Join-Path $output 'logs'),(Join-Path $output 'data'))){
    [void](New-Item -ItemType Directory -Force $folder)
}
# This output is a build-only staging area, never the user's used package.
# Archive only an explicit public file allowlist, not runtime directories.
foreach($name in @('START.bat','RESTORE.bat','BACA_DULU.txt','LICENSE')){
    Copy-Item -LiteralPath (Join-Path $project "portable\$name") -Destination (Join-Path $output $name) -Force
}
foreach($name in @('Core.ps1','Installer.ps1','FONT-LICENSE-OFL.txt')){
    Copy-Item -LiteralPath (Join-Path $project "portable\app\$name") -Destination (Join-Path $output "app\$name") -Force
}
Copy-Item -LiteralPath $apk -Destination (Join-Path $output 'app\FyrusLauncher.apk') -Force
$hash=(Get-FileHash -LiteralPath (Join-Path $output 'app\FyrusLauncher.apk')).Hash
"$hash  FyrusLauncher.apk" | Set-Content -Encoding ASCII (Join-Path $output 'app\SHA256.txt')
$zip=Join-Path $release 'FyrusLauncher-2.6-MiBoxS2.zip'
$temp=Join-Path $release ('package-'+[guid]::NewGuid().ToString('N'))
$stage=Join-Path $temp 'FyrusLauncher'
[void](New-Item -ItemType Directory -Force (Join-Path $stage 'app'))
foreach($name in @('START.bat','RESTORE.bat','BACA_DULU.txt','LICENSE')){Copy-Item (Join-Path $output $name) (Join-Path $stage $name)}
foreach($name in @('Core.ps1','Installer.ps1','FONT-LICENSE-OFL.txt','FyrusLauncher.apk','SHA256.txt')){Copy-Item (Join-Path $output "app\$name") (Join-Path $stage "app\$name")}
try {
    Compress-Archive -LiteralPath $stage -DestinationPath $zip -Force
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if($resolved.StartsWith([IO.Path]::GetFullPath($release)+'\',[StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($resolved).StartsWith('package-')){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
Write-Host "Release ZIP: $zip"
Write-Host "APK SHA256: $hash"
