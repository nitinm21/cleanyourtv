$ErrorActionPreference='Stop'
$script:Root=Split-Path $PSScriptRoot -Parent
$script:Package='com.fyrus.cleanyourtv.netflix'
$script:HomeActivity="$script:Package/com.gathin.tvhome.MainActivity"
$script:HomeService="$script:Package/com.gathin.tvhome.MiBoxHomeService"
$script:ServerPort=5038
$script:Adb=$null
$script:Serial=$null
$script:DeviceId=$null
$script:CurrentBackup=$null
$script:Installed=$false
$script:OptionalPackages=@('com.mitv.tvhome.atv','com.google.android.play.games','com.google.android.videos')
foreach($name in @('tools','data','backups','logs')){
    [void](New-Item -ItemType Directory -Force (Join-Path $script:Root $name))
}
$script:Log=Join-Path $script:Root ('logs\session-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'.log')

function Invoke-Adb([string[]]$Arguments,[switch]$HostCommand){
    if(!$script:Adb){throw 'Buat langkah 1: semak ADB dahulu.'}
    $all=@('-P',"$script:ServerPort")
    if(!$HostCommand){
        if(!$script:Serial){throw 'Buat langkah 2: sambung Mi Box dahulu.'}
        $all+=@('-s',$script:Serial)
    }
    $all+=$Arguments
    $saved=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        $out=(& $script:Adb @all 2>&1 | ForEach-Object {$_.ToString()} | Out-String).Trim()
        $rc=$LASTEXITCODE
    } finally {$ErrorActionPreference=$saved}
    # Pairing codes are entered interactively and never written to a log.
    if($Arguments[0] -ne 'pair'){
        Add-Content -LiteralPath $script:Log -Value ("`r`n> adb "+($all -join ' ')+"`r`n"+$out)
    }
    if($rc -ne 0 -or $out -match '(?mi)^(error:|exception|failure\s*\[|security exception|failed to |cannot connect)'){
        $display=if($Arguments[0] -eq 'pair'){'pair [kod disembunyikan]'}else{$Arguments -join ' '}
        throw "ADB gagal: ${display}: $out"
    }
    return $out
}

function Initialize-Adb([switch]$AllowDownload){
    $local=Join-Path $script:Root 'tools\platform-tools\adb.exe'
    $candidates=@($local)
    $command=Get-Command adb.exe -ErrorAction SilentlyContinue
    if($command){$candidates+= $command.Source}
    $candidates+= @(Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    foreach($candidate in $candidates){
        if(Test-Path -LiteralPath $candidate){$script:Adb=$candidate;break}
    }
    if(!$script:Adb){
        if(!$AllowDownload){throw 'ADB tiada. Pilih langkah 1 untuk muat turun portable ADB.'}
        Write-Host 'ADB sahaja diperlukan. Tiada SDK penuh, JDK atau Gradle dimuat turun.'
        if((Read-Host 'Muat turun Android platform-tools rasmi ke tools? Taip YA') -cne 'YA'){
            throw 'Muat turun dibatalkan.'
        }
        $zip=Join-Path $script:Root 'tools\platform-tools-download.zip'
        [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
        $ProgressPreference='SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
        Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $script:Root 'tools') -Force
        if(!(Test-Path -LiteralPath $local)){throw 'Muat turun tidak mengandungi adb.exe.'}
        Remove-Item -LiteralPath $zip -Force
        $script:Adb=$local
    }
    # Only process-local environment changes; no registry, PATH or system installation.
    $env:ANDROID_ADB_LOG_PATH=Join-Path $script:Root 'logs\adb-server.log'
    Write-Host (Invoke-Adb @('version') -HostCommand)
    Write-Host "ADB digunakan: $script:Adb"
    Write-Host 'Sedia. Jika ADB sudah ada, tiada muat turun diperlukan.' -ForegroundColor Green
}

function ConvertTo-Endpoint([string]$Value){
    if($Value -notmatch '^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$'){throw 'Masukkan IPv4 atau IPv4:port.'}
    foreach($octet in $Matches[1].Split('.')){if([int]$octet -gt 255){throw 'Alamat IPv4 tidak sah.'}}
    $port=if($Matches[2]){[int]$Matches[2]}else{5555}
    if($port -lt 1 -or $port -gt 65535){throw 'Port tidak sah.'}
    return "$($Matches[1]):$port"
}

function Assert-Device {
    if((Invoke-Adb @('get-state')) -ne 'device'){throw 'ADB belum bersedia. Terima debugging pada TV.'}
    $model=Invoke-Adb @('shell','getprop','ro.product.model')
    if($model -ne 'MiTV-AFKR0'){throw "Henti: pakej ini untuk Mi Box S2 MiTV-AFKR0; ditemui $model."}
    $id=Invoke-Adb @('shell','getprop','ro.serialno')
    if(!$id -or $id -notmatch '^[A-Za-z0-9._-]+$'){throw 'Identiti peranti tidak dapat disahkan.'}
    if($script:DeviceId -and $script:DeviceId -ne $id){throw 'Identiti peranti berubah. Sambung semula melalui langkah 2.'}
    $script:DeviceId=$id
    if((Invoke-Adb @('shell','am','get-current-user')) -ne '0'){throw 'Sila gunakan user 0 utama pada Mi Box.'}
    if((Invoke-Adb @('shell','settings','get','global','device_provisioned')) -ne '1' -or
            (Invoke-Adb @('shell','settings','--user','0','get','secure','user_setup_complete')) -ne '1'){
        throw 'Lengkapkan setup Google TV asal dahulu sebelum memasang pengalihan Home.'
    }
}

function Connect-Device([string]$Endpoint){
    if(!$script:Adb){Initialize-Adb}
    $script:Serial=ConvertTo-Endpoint $Endpoint
    $script:DeviceId=$null
    $script:CurrentBackup=$null
    $script:Installed=$false
    Write-Host (Invoke-Adb @('connect',$script:Serial) -HostCommand)
    Assert-Device
    Write-Host "Disahkan: MiTV-AFKR0; identiti $script:DeviceId" -ForegroundColor Green
}

function Get-Home {
    $out=Invoke-Adb @('shell','cmd','package','resolve-activity','--brief','--user','0','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
    $match=[regex]::Matches($out,'(?m)^\S+/\S+$') | Select-Object -Last 1
    if(!$match){throw "Home tidak dapat dikenal pasti: $out"}
    return $match.Value
}

function Get-Services {
    $value=Invoke-Adb @('shell','settings','--user','0','get','secure','enabled_accessibility_services')
    return @($value -split ':' | Where-Object {$_ -and $_ -ne 'null'})
}

function Set-Services($Services,[string]$Enabled){
    if(@($Services).Count){
        [void](Invoke-Adb @('shell','settings','--user','0','put','secure','enabled_accessibility_services',($Services -join ':')))
    } else {[void](Invoke-Adb @('shell','settings','--user','0','delete','secure','enabled_accessibility_services'))}
    if($Enabled -eq 'null'){
        [void](Invoke-Adb @('shell','settings','--user','0','delete','secure','accessibility_enabled'))
    } else {[void](Invoke-Adb @('shell','settings','--user','0','put','secure','accessibility_enabled',$Enabled))}
}

function Get-WifiProfiles([string]$Wifi){
    $section=[regex]::Match($Wifi,'(?ms)^WifiConfigManager - Configured networks Begin ----\r?\n(.*?)(?=^WifiConfigManager - Configured networks End ----)').Value
    if(!$section){throw 'Format Wi-Fi firmware ini belum disokong. Tiada perubahan dibuat.'}
    $matches=[regex]::Matches($section,'(?ms)^\s*ID:\s*(\d+)\s+SSID:(.*?)(?=^\s*ID:|\z)')
    if(!$matches.Count){throw 'Profil Wi-Fi tersimpan tidak dapat disahkan.'}
    $items=@(foreach($record in $matches){
        $owner=[regex]::Match($record.Value,'\bcname=([^\s,}]+)').Groups[1].Value
        if(!$owner){throw 'Pemilik profil Wi-Fi tidak dapat dibaca.'}
        "$($record.Groups[1].Value):$owner|$(($record.Groups[2].Value -split '\r?\n')[0])"
    })
    return @($items | Sort-Object)
}

function Get-DeviceFolder {return Join-Path $script:Root "backups\$script:DeviceId"}

function New-DeviceBackup {
    Assert-Device
    $wifi=Invoke-Adb @('shell','dumpsys','wifi')
    if($wifi -notmatch 'Supplicant state: COMPLETED'){throw 'Wi-Fi belum bersambung. Tiada perubahan dibuat.'}
    $profiles=@(Get-WifiProfiles $wifi)
    $folder=Join-Path (Get-DeviceFolder) (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
    [void](New-Item -ItemType Directory -Force $folder)
    $disabled=Invoke-Adb @('shell','pm','list','packages','-d','--user','0')
    $state=[pscustomobject]@{
        Schema=3;DeviceId=$script:DeviceId;Serial=$script:Serial;Home=(Get-Home)
        Services=@(Get-Services);AccessibilityEnabled=(Invoke-Adb @('shell','settings','--user','0','get','secure','accessibility_enabled'))
        WifiProfiles=$profiles;DisabledPackages=@($disabled -split '\r?\n' | Where-Object {$_})
        Created=(Get-Date).ToString('o')
    }
    $baseline=Join-Path (Get-DeviceFolder) 'original-state.json'
    if(!(Test-Path -LiteralPath $baseline) -and
            (@($state.Services) -contains $script:HomeService -or
             @($state.Services) -contains "$script:Package/.MiBoxHomeService")){
        throw 'Pengalihan Home sudah aktif tetapi backup asal tiada. Salin folder backups asal atau matikan servis ini di Accessibility dahulu.'
    }
    $state | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $folder 'state.json')
    $wifi | Set-Content -Encoding UTF8 (Join-Path $folder 'wifi-before.txt')
    $disabled | Set-Content (Join-Path $folder 'disabled-before.txt')
    foreach($pkg in @('com.google.android.apps.tv.launcherx','com.google.android.tungsten.setupwraith',$script:Package)){
        (Invoke-Adb @('shell','dumpsys','package',$pkg)) | Set-Content (Join-Path $folder ($pkg+'.txt'))
    }
    $packages=Invoke-Adb @('shell','pm','list','packages','--user','0',$script:Package)
    if(($packages -split '\r?\n') -contains "package:$script:Package"){
        $paths=Invoke-Adb @('shell','pm','path',$script:Package)
        $index=0
        foreach($line in ($paths -split '\r?\n')){
            if($line.StartsWith('package:')){
                [void](Invoke-Adb @('pull',$line.Substring(8),(Join-Path $folder "launcher-before-$index.apk")))
                $index++
            }
        }
    }
    # Set baseline only after all required backup operations have succeeded.
    if(!(Test-Path -LiteralPath $baseline)){Copy-Item -LiteralPath (Join-Path $folder 'state.json') -Destination $baseline}
    $script:CurrentBackup=$folder
    $script:Installed=$false
    'Backup completed.' | Set-Content (Join-Path $folder 'BACKUP_COMPLETE.txt')
    Write-Host "Backup siap: $folder" -ForegroundColor Green
    Write-Host 'Pulihkan Home melalui menu 7 atau RESTORE.bat. Backup ini bukan imej sistem/kata laluan Wi-Fi.'
    return $folder
}

function Assert-Backup {
    Assert-Device
    if(!$script:CurrentBackup -or !(Test-Path -LiteralPath (Join-Path $script:CurrentBackup 'BACKUP_COMPLETE.txt'))){
        throw 'Buat langkah 3: backup lengkap untuk sesi/peranti ini dahulu.'
    }
    $state=Get-Content -LiteralPath (Join-Path $script:CurrentBackup 'state.json') -Raw | ConvertFrom-Json
    if($state.DeviceId -ne $script:DeviceId){throw 'Backup bukan milik peranti semasa.'}
    return $state
}

function Install-Launcher {
    [void](Assert-Backup)
    $apk=Join-Path $script:Root 'app\FyrusLauncher.apk'
    $expected=(Get-Content -LiteralPath (Join-Path $script:Root 'app\SHA256.txt')).Split(' ')[0]
    if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $expected){throw 'Checksum APK tidak sepadan. Ekstrak semula pakej.'}
    Write-Host (Invoke-Adb @('install','-r',$apk))
    [void](Invoke-Adb @('shell','am','start','-W','-n',$script:HomeActivity))
    $script:Installed=$true
    'APK installed with install -r. No uninstall/data deletion.' | Set-Content (Join-Path $script:CurrentBackup 'INSTALLED.txt')
    Write-Host 'FyrusLauncher dipasang. Langkah 5 akan mengaktifkan butang Home.' -ForegroundColor Green
}

function Restore-HomeState($State){
    if($State.DeviceId -ne $script:DeviceId){throw 'Backup milik peranti lain.'}
    $other=@(Get-Services | Where-Object {$_ -ne $script:HomeService -and $_ -ne "$script:Package/.MiBoxHomeService"})
    $services=@(@($State.Services)+$other | Select-Object -Unique)
    $enabled=[string]$State.AccessibilityEnabled
    if(@($other | Where-Object {$_ -notin @($State.Services)}).Count){$enabled='1'}
    Set-Services $services $enabled
    [void](Invoke-Adb @('shell','cmd','package','set-home-activity','--user','0',$State.Home))
}

function Test-Home {
    Assert-Device
    $bound=$false
    for($attempt=0;$attempt -lt 15;$attempt++){
        $access=Invoke-Adb @('shell','dumpsys','accessibility')
        if($access -match 'Bound services:.*label=(?:FyrusLauncher Home|CleanYourTV Mi Box Ho)'){$bound=$true;break}
        Start-Sleep -Seconds 1
    }
    if(!$bound){throw 'Servis Home belum bound. Semak langkah 5 / Accessibility pada TV.'}
    [void](Invoke-Adb @('shell','am','start','-W','-a','android.settings.SETTINGS'))
    [void](Invoke-Adb @('shell','input','keyevent','3'))
    $passed=$false
    for($attempt=0;$attempt -lt 10;$attempt++){
        Start-Sleep -Seconds 1
        $activity=Invoke-Adb @('shell','dumpsys','activity','activities')
        if($activity -match ('mResumedActivity:.*'+[regex]::Escape($script:Package))){$passed=$true;break}
    }
    if(!$passed){throw 'Home tidak membuka FyrusLauncher.'}
    Write-Host 'LULUS: Home melalui ADB membuka FyrusLauncher. Uji juga remote fizikal.' -ForegroundColor Green
}

function Enable-HomeRedirect {
    $state=Assert-Backup
    if(!$script:Installed){throw 'Buat langkah 4: pasang APK dahulu dalam sesi ini.'}
    Write-Host 'Ini mengaktifkan servis Accessibility untuk alih Home. Resolver Android kekal Google TV.'
    Write-Host 'Tiada pakej sistem disabled; Wi-Fi, Cast dan Play Services dikekalkan.'
    if((Read-Host 'Taip HOME untuk meluluskan pengalihan') -cne 'HOME'){throw 'Dibatalkan.'}
    try {
        $services=@(Get-Services | Where-Object {$_ -ne $script:HomeService -and $_ -ne "$script:Package/.MiBoxHomeService"})
        Set-Services @($services+$script:HomeService) '1'
        Test-Home
        $wifi=Invoke-Adb @('shell','dumpsys','wifi')
        $wifi | Set-Content (Join-Path $script:CurrentBackup 'wifi-after.txt')
        if($wifi -notmatch 'Supplicant state: COMPLETED' -or (Compare-Object @($state.WifiProfiles) @(Get-WifiProfiles $wifi))){
            throw 'Pengesahan Wi-Fi tidak sepadan.'
        }
        $disabled=Invoke-Adb @('shell','pm','list','packages','-d','--user','0')
        if((@($state.DisabledPackages | Sort-Object) -join "`n") -ne
                (@($disabled -split '\r?\n' | Where-Object {$_} | Sort-Object) -join "`n")){
            throw 'Senarai pakej disabled berubah.'
        }
        'SUCCESS: Home, Wi-Fi and disabled package set verified.' | Set-Content (Join-Path $script:CurrentBackup 'SUCCESS.txt')
    } catch {
        $failure=$_
        try {Restore-HomeState $state} catch {Write-Warning "Rollback tidak lengkap: $_. Gunakan menu 7 apabila ADB tersedia."}
        throw $failure
    }
}

function Restore-OriginalHome {
    Assert-Device
    $path=Join-Path (Get-DeviceFolder) 'original-state.json'
    if(!(Test-Path -LiteralPath $path)){throw 'Backup asal peranti ini tidak ada dalam folder backups.'}
    $state=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if($state.DeviceId -ne $script:DeviceId){throw 'Identiti backup tidak sepadan.'}
    Write-Host "Backup asal: $path"
    if((Read-Host 'Taip RESTORE untuk pulihkan Home asal') -cne 'RESTORE'){throw 'Dibatalkan.'}
    Restore-HomeState $state
    [void](Invoke-Adb @('shell','input','keyevent','3'))
    if((Get-Home) -ne $state.Home){throw 'Resolver Home asal belum disahkan.'}
    Write-Host 'Home dan tetapan Accessibility asal dipulihkan. APK/data FyrusLauncher dikekalkan.' -ForegroundColor Green
}

function Invoke-OptionalApps([switch]$Restore){
    Assert-Device
    $file=Join-Path (Get-DeviceFolder) 'optional-apps.json'
    if($Restore){
        if(!(Test-Path -LiteralPath $file)){throw 'Tiada rekod disable pilihan untuk peranti ini.'}
        $record=Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        if($record.DeviceId -ne $script:DeviceId){throw 'Identiti tidak sepadan.'}
        if((Read-Host 'Taip APPSRESTORE untuk pulihkan aplikasi pilihan') -cne 'APPSRESTORE'){throw 'Dibatalkan.'}
        foreach($entry in $record.Packages){
            if($entry.Package -notin $script:OptionalPackages){throw 'Pakej di luar senarai dibenarkan.'}
            $op=switch([int]$entry.Enabled){0{'default-state'} 1{'enable'} 2{'disable'} 3{'disable-user'} 4{'disable-until-used'} default{throw 'Keadaan asal tidak sah.'}}
            Write-Host (Invoke-Adb @('shell','pm',$op,'--user','0',$entry.Package))
        }
        $record | Add-Member -NotePropertyName Active -NotePropertyValue $false -Force
        $record | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $file
        return
    }
    [void](Assert-Backup)
    if(Test-Path -LiteralPath $file){
        $prior=Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        if($prior.Active -ne $false){throw 'Rekod disable pilihan sudah ada. Restore dahulu sebelum disable semula.'}
    }
    $wifiProfiles=@(Get-WifiProfiles (Invoke-Adb @('shell','dumpsys','wifi')))
    foreach($pkg in $script:OptionalPackages){
        if(@($wifiProfiles | Where-Object {$_ -match (':'+[regex]::Escape($pkg)+'\|')}).Count){
            throw "Henti: $pkg memiliki profil Wi-Fi tersimpan. Pakej tidak akan disabled."
        }
    }
    $entries=@(foreach($pkg in $script:OptionalPackages){
        $dump=Invoke-Adb @('shell','dumpsys','package',$pkg)
        $m=[regex]::Match($dump,'(?m)^\s*User 0:.*?installed=true.*?\benabled=(\d+)')
        if(!$m.Success){throw "Keadaan user 0 tidak dapat dibaca: $pkg"}
        [pscustomobject]@{Package=$pkg;Enabled=[int]$m.Groups[1].Value}
    })
    Write-Host 'Pilihan sahaja: PatchWall, Google Play Games, Google Play Movies.'
    Write-Host 'Xiaomi TV+, aplikasi streaming lain, Cast, GMS, launcherx dan setupwraith dikekalkan.'
    if((Read-Host 'Taip DISABLE3 untuk meluluskan tiga pakej ini sahaja') -cne 'DISABLE3'){throw 'Dibatalkan.'}
    [pscustomobject]@{DeviceId=$script:DeviceId;Active=$true;Packages=$entries} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $file
    $changed=@()
    try {
        foreach($entry in $entries){
            $changed+=$entry
            Write-Host (Invoke-Adb @('shell','pm','disable-user','--user','0',$entry.Package))
        }
    } catch {
        $failure=$_
        foreach($entry in $changed){
            $op=switch([int]$entry.Enabled){0{'default-state'} 1{'enable'} 2{'disable'} 3{'disable-user'} 4{'disable-until-used'}}
            try {[void](Invoke-Adb @('shell','pm',$op,'--user','0',$entry.Package))} catch {Write-Warning "Restore $($entry.Package) gagal: $_"}
        }
        throw $failure
    }
    Write-Host 'Selesai. Submenu Restore apps memulihkan keadaan asal daripada rekod.'
}
