Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'mall-order' } | ForEach-Object { Write-Output $_.ProcessId }
