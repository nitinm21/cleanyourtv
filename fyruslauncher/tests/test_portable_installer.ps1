param([string]$PackageRoot=(Join-Path (Split-Path $PSScriptRoot -Parent) 'release\FyrusLauncher'))
$ErrorActionPreference='Stop'
. (Join-Path $PackageRoot 'app\Core.ps1')
$realRoot=$script:Root
$testRoot=Join-Path $env:TEMP ('fyrus-installer-test-'+[guid]::NewGuid().ToString('N'))
$script:Root=$testRoot
New-Item -ItemType Directory -Force "$testRoot\app","$testRoot\backups","$testRoot\logs" | Out-Null
Copy-Item (Join-Path $realRoot 'app\FyrusLauncher.apk') "$testRoot\app\FyrusLauncher.apk"
Copy-Item (Join-Path $realRoot 'app\SHA256.txt') "$testRoot\app\SHA256.txt"
$script:FakeModel='MiTV-AFKR0'
$script:FakeServices='example.reader/.Reader'
$script:FakeEnabled='1'
$script:FakeInstalled=$false
$script:FakeDisabled=@()
$script:FakeFailHome=$false
$script:FakeHome='com.google.android.apps.tv.launcherx/.home.HomeActivity'
$script:FakeWifi=@'
Wi-Fi is enabled
mWifiInfo SSID: example, Supplicant state: COMPLETED, Net ID: 0
WifiConfigManager - Configured networks Begin ----
ID: 0 SSID: "example" PROVIDER-NAME: null
 NetworkSelectionStatus NETWORK_SELECTION_ENABLED
 cuid=1000 cname=android.uid.system:1000
WifiConfigManager - Configured networks End ----
'@
$script:Responses=New-Object 'System.Collections.Generic.Queue[string]'
$script:Calls=New-Object 'System.Collections.Generic.List[string]'
function Read-Host {param([string]$Prompt) if(!$script:Responses.Count){throw "Unexpected prompt: $Prompt"};return $script:Responses.Dequeue()}
function Start-Sleep {param([int]$Seconds)}
function Invoke-Adb {
    param([string[]]$Arguments,[switch]$HostCommand)
    $cmd=$Arguments -join ' '
    $script:Calls.Add($cmd)
    switch -Regex ($cmd){
        '^version$' {return 'Android Debug Bridge version 1.0.41 (test)'}
        '^get-state$' {return 'device'}
        '^shell getprop ro.product.model$' {return $script:FakeModel}
        '^shell getprop ro.serialno$' {return 'TESTBOX001'}
        '^shell am get-current-user$' {return '0'}
        '^shell settings get global device_provisioned$' {return '1'}
        '^shell settings --user 0 get secure user_setup_complete$' {return '1'}
        '^shell dumpsys wifi$' {return $script:FakeWifi}
        '^shell cmd package resolve-activity' {return $script:FakeHome}
        '^shell cmd package set-home-activity' {$script:FakeHome=$Arguments[-1];return 'Success'}
        '^shell settings --user 0 get secure enabled_accessibility_services$' {return $script:FakeServices}
        '^shell settings --user 0 get secure accessibility_enabled$' {return $script:FakeEnabled}
        '^shell settings --user 0 put secure enabled_accessibility_services' {$script:FakeServices=$Arguments[-1];return ''}
        '^shell settings --user 0 put secure accessibility_enabled' {$script:FakeEnabled=$Arguments[-1];return ''}
        '^shell settings --user 0 delete secure enabled_accessibility_services' {$script:FakeServices='null';return ''}
        '^shell pm list packages -d --user 0$' {return ($script:FakeDisabled -join "`n")}
        '^shell pm list packages --user 0' {if($script:FakeInstalled){return 'package:com.fyrus.cleanyourtv.netflix'};return ''}
        '^shell pm path ' {return 'package:/mock/base.apk'}
        '^shell dumpsys package ' {return 'User 0: installed=true hidden=false enabled=1'}
        '^pull ' {Set-Content $Arguments[-1] 'mock APK backup';return 'pulled'}
        '^install -r ' {$script:FakeInstalled=$true;return 'Success'}
        '^shell am start ' {return 'Status: ok'}
        '^shell dumpsys accessibility$' {return 'Bound services:{Service[label=FyrusLauncher Home]'}
        '^shell input keyevent 3$' {return ''}
        '^shell dumpsys activity activities$' {
            if(!$script:FakeFailHome -and $script:FakeServices.Contains('MiBoxHomeService')){
                return 'mResumedActivity: com.fyrus.cleanyourtv.netflix/com.gathin.tvhome.MainActivity'
            }
            return 'mResumedActivity: com.google.android.apps.tv.launcherx/.home.HomeActivity'
        }
        '^shell pm disable-user --user 0 ' {$script:FakeDisabled+= "package:$($Arguments[-1])";return 'disabled-user'}
        '^shell pm enable --user 0 ' {$script:FakeDisabled=@($script:FakeDisabled | Where-Object {$_ -ne "package:$($Arguments[-1])"});return 'enabled'}
        default {throw "Unexpected ADB command: $cmd"}
    }
}
function Assert($Condition,$Message){if(!$Condition){throw "FAILED: $Message"}}
function Must-Fail([scriptblock]$Action,$Message){$failed=$false;try{& $Action}catch{$failed=$true};Assert $failed $Message}
try {
    Assert ((ConvertTo-Endpoint '192.168.1.7') -eq '192.168.1.7:5555') 'default port'
    Assert ((ConvertTo-Endpoint '192.168.1.7:37111') -eq '192.168.1.7:37111') 'custom wireless port'
    Must-Fail {ConvertTo-Endpoint '192.168.1.999'} 'invalid IPv4 rejected'
    Must-Fail {ConvertTo-Endpoint '192.168.1.7:0'} 'invalid port rejected'
    $script:Serial='192.168.1.7:5555'
    Must-Fail {Install-Launcher} 'installation requires completed device backup'
    $script:FakeModel='OTHER'
    Must-Fail {Assert-Device} 'wrong model rejected'
    $script:FakeModel='MiTV-AFKR0'
    [void](New-DeviceBackup)
    $baseline=Get-Content (Join-Path (Get-DeviceFolder) 'original-state.json') -Raw
    Install-Launcher
    $script:Responses.Enqueue('HOME')
    Enable-HomeRedirect
    Assert ($script:FakeServices.Contains('example.reader/.Reader')) 'unrelated Accessibility service preserved'
    Assert (Test-Path (Join-Path $script:CurrentBackup 'SUCCESS.txt')) 'success requires Home and unchanged Wi-Fi/packages'
    $script:Responses.Enqueue('RESTORE')
    Restore-OriginalHome
    Assert ($script:FakeServices -eq 'example.reader/.Reader') 'restore removes only added Home service'
    Assert ($script:FakeEnabled -eq '1') 'restore Accessibility enabled state'
    [void](New-DeviceBackup)
    Assert ((Get-Content (Join-Path (Get-DeviceFolder) 'original-state.json') -Raw) -eq $baseline) 'original baseline not overwritten'
    Install-Launcher
    $script:FakeFailHome=$true
    $script:Responses.Enqueue('HOME')
    Must-Fail {Enable-HomeRedirect} 'failed Home triggers rollback'
    Assert ($script:FakeServices -eq 'example.reader/.Reader') 'automatic rollback restores prior Accessibility services'
    $script:FakeFailHome=$false
    $beforeCalls=$script:Calls.Count
    $script:Responses.Enqueue('CANCEL')
    Must-Fail {Invoke-OptionalApps} 'system package changes require explicit approval'
    Assert (@($script:Calls | Select-Object -Skip $beforeCalls | Where-Object {$_ -match '^shell pm disable-user'}).Count -eq 0) 'no disable before approval'
    $script:Responses.Enqueue('DISABLE3')
    Invoke-OptionalApps
    Assert ($script:FakeDisabled.Count -eq 3) 'only three approved packages disabled'
    Assert (@($script:FakeDisabled | Where-Object {$_ -match 'mitvplus|gms|cast|setupwraith|launcherx'}).Count -eq 0) 'protected packages untouched'
    $script:Responses.Enqueue('APPSRESTORE')
    Invoke-OptionalApps -Restore
    Assert ($script:FakeDisabled.Count -eq 0) 'optional apps restore exact initial enabled states'
    $script:DeviceId='ANOTHERBOX'
    Must-Fail {Assert-Backup} 'backup rejected for another device'
    # Exercise the missing-ADB download path using a local fixture ZIP, never the network.
    $script:Root=Join-Path $testRoot 'download-case'
    New-Item -ItemType Directory -Force "$script:Root\tools","$script:Root\logs" | Out-Null
    $script:Adb=$null
    $oldLocalAppData=$env:LOCALAPPDATA
    try {
        $env:LOCALAPPDATA=Join-Path $testRoot 'no-existing-sdk'
        function Get-Command {param($Name,$ErrorAction) return $null}
        function Invoke-WebRequest {
            param([switch]$UseBasicParsing,[Parameter(Position=0)][string]$Uri,[string]$OutFile)
            Assert ($Uri -eq 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip') 'download uses official Google URL'
            Assert ($OutFile -eq (Join-Path $script:Root 'tools\platform-tools-download.zip')) 'download remains in package tools folder'
            $fixture=Join-Path $testRoot 'download-fixture'
            New-Item -ItemType Directory -Force "$fixture\platform-tools" | Out-Null
            Set-Content "$fixture\platform-tools\adb.exe" 'fake executable; Invoke-Adb is mocked'
            Compress-Archive -LiteralPath "$fixture\platform-tools" -DestinationPath $OutFile
        }
        $script:Responses.Enqueue('YA')
        Initialize-Adb -AllowDownload
        Assert ($script:Adb -eq (Join-Path $script:Root 'tools\platform-tools\adb.exe')) 'missing ADB installed only inside package'
    } finally {$env:LOCALAPPDATA=$oldLocalAppData}
    Write-Host 'PASS: endpoint validation, model/device guards, staged backup/install, Home success/failure rollback, original baseline, explicit package approval and restore.'
} finally {
    $absolute=[IO.Path]::GetFullPath($testRoot)
    if($absolute.StartsWith([IO.Path]::GetFullPath($env:TEMP),[StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($absolute).StartsWith('fyrus-installer-test-')){
        Remove-Item -LiteralPath $absolute -Recurse -Force
    }
}
