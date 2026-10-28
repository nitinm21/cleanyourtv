param([switch]$RestoreOnly,[switch]$CheckOnly)
. "$PSScriptRoot\Core.ps1"
if($CheckOnly){
    $apk=Join-Path $script:Root 'app\FyrusLauncher.apk'
    $expected=(Get-Content (Join-Path $script:Root 'app\SHA256.txt')).Split(' ')[0]
    if((Get-FileHash $apk).Hash -ne $expected){throw 'APK checksum mismatch.'}
    Write-Host 'Pakej sah. APK dan skrip tersedia. Tiada sambungan/perubahan TV dibuat.'
    exit 0
}
try {
    if($RestoreOnly){
        Initialize-Adb -AllowDownload
        Connect-Device (Read-Host 'IP Mi Box / IP:port')
        Restore-OriginalHome
        Read-Host 'Tekan Enter untuk tutup' | Out-Null
        exit 0
    }
    while($true){
        Write-Host "`n========== FyrusLauncher 2.6 - Mi Box S2 ==========" -ForegroundColor Cyan
        Write-Host 'Buat langkah 1 hingga 6 mengikut turutan.'
        Write-Host '1. Semak ADB / muat turun hanya jika belum ada'
        Write-Host '2. Sambung Mi Box melalui Wi-Fi'
        Write-Host '3. Semak model + Wi-Fi dan sediakan backup'
        Write-Host '4. Pasang / kemas kini FyrusLauncher (APK siap)'
        Write-Host '5. Aktifkan butang Home (minta kelulusan)'
        Write-Host '6. Uji butang Home'
        Write-Host '7. Restore Home asal / Google TV'
        Write-Host '8. Pilihan aplikasi: disable / restore 3 apps'
        Write-Host '9. Status / buka FyrusLauncher'
        Write-Host '0. Keluar dan hentikan server ADB pakej ini'
        try {
            switch(Read-Host 'Pilih [0-9]'){
                '1' {Initialize-Adb -AllowDownload}
                '2' {
                    Write-Host 'Aktifkan Developer options + USB debugging. Terima kunci PC di TV.'
                    Write-Host 'Jika TV perlukan pairing, taip PAIR; jika tidak masukkan IP semasa.'
                    $inputValue=Read-Host 'IP / IP:port / PAIR'
                    if($inputValue -ceq 'PAIR'){
                        if(!$script:Adb){Initialize-Adb}
                        $pair=ConvertTo-Endpoint (Read-Host 'IP:port pairing dari TV')
                        $pairCode=Read-Host 'Kod pairing 6 digit yang dipaparkan pada TV'
                        if($pairCode -notmatch '^\d{6}$'){throw 'Kod pairing mestilah 6 digit.'}
                        Write-Host (Invoke-Adb @('pair',$pair,$pairCode) -HostCommand)
                        $pairCode=$null
                        $inputValue=Read-Host 'IP:port sambungan (bukan port pairing)'
                    }
                    Connect-Device $inputValue
                }
                '3' {[void](New-DeviceBackup)}
                '4' {Install-Launcher}
                '5' {Enable-HomeRedirect}
                '6' {Test-Home}
                '7' {Restore-OriginalHome}
                '8' {
                    $choice=Read-Host '1=disable tiga apps (pilihan), 2=restore tiga apps, lain=batal'
                    if($choice -eq '1'){Invoke-OptionalApps}
                    if($choice -eq '2'){Invoke-OptionalApps -Restore}
                }
                '9' {
                    Assert-Device
                    Write-Host "Mi Box: $script:Serial; ID: $script:DeviceId; Home resolver: $(Get-Home)"
                    Write-Host (Invoke-Adb @('shell','am','start','-W','-n',$script:HomeActivity))
                }
                '0' {$script:ExitRequested=$true}
                default {Write-Host 'Pilihan tidak sah.'}
            }
        } catch {Write-Host "HENTI: $($_.Exception.Message)" -ForegroundColor Yellow}
        if($script:ExitRequested){break}
    }
} finally {
    if($script:Adb){try {[void](Invoke-Adb @('kill-server') -HostCommand)} catch {}}
}
