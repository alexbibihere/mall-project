# 杀掉所有 mall-* Java 服务进程（用 CIM Terminate，Stop-Process/taskkill 对其无效）
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'mall-' } | ForEach-Object {
  $procId = $_.ProcessId
  $r = Invoke-CimMethod -InputObject $_ -MethodName Terminate
  Write-Output ("{0}:{1}" -f $procId, $r.ReturnValue)
}
