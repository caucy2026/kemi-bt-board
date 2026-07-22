$range = 1..254
foreach ($i in $range) {
    $ip = "192.168.3.$i"
    $client = New-Object System.Net.Sockets.TcpClient
    $iar = $client.BeginConnect($ip, 5555, $null, $null)
    if ($iar.AsyncWaitHandle.WaitOne(50, $false)) {
        try {
            $client.EndConnect($iar)
            Write-Host "FOUND ADB ON $ip"
        } catch {}
    }
    $client.Close()
}
