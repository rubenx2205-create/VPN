# Mantiene vivo un componente (xray o tunel). Lo lanza una tarea programada
# al arrancar Windows; si el proceso muere, lo vuelve a arrancar.
param(
    [Parameter(Mandatory)][ValidateSet('xray', 'tunel')][string]$Componente
)

. (Join-Path $PSScriptRoot 'comun.ps1')

$base = Get-RutaBase
$logs = Join-Path $base 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

function Limpiar-Log {
    param([string]$Ruta)
    if ((Test-Path $Ruta) -and (Get-Item $Ruta).Length -gt 5MB) { Remove-Item $Ruta -Force }
}

function Iniciar-Xray {
    $err = Join-Path $logs 'xray.log'
    Limpiar-Log $err
    return Start-Process -FilePath (Join-Path $base 'bin\xray.exe') `
        -ArgumentList 'run', '-c', "`"$(Join-Path $base 'config.json')`"" `
        -RedirectStandardOutput (Join-Path $logs 'xray-salida.log') -RedirectStandardError $err -PassThru
}

function Iniciar-Tunel {
    param($Estado)
    $log = Join-Path $logs 'tunel.log'
    Remove-Item $log -Force -ErrorAction SilentlyContinue
    $exe = Join-Path $base 'bin\cloudflared.exe'

    if ($Estado.Modo -eq 'nombrado') {
        # El token va por variable de entorno para que no se vea en la linea de comandos.
        $env:TUNNEL_TOKEN = $Estado.Token
        $argumentos = @('tunnel', '--no-autoupdate', '--logfile', "`"$log`"", 'run')
    } else {
        $argumentos = @('tunnel', '--no-autoupdate', '--logfile', "`"$log`"",
            '--url', "http://127.0.0.1:$($Estado.Puerto)")
    }
    return Start-Process -FilePath $exe -ArgumentList $argumentos -WindowStyle Hidden -PassThru
}

function Esperar-DominioRapido {
    param($Proceso, $Estado)
    $log = Join-Path $logs 'tunel.log'
    $archivo = Join-Path $base 'dominio-rapido.txt'
    Remove-Item $archivo -Force -ErrorAction SilentlyContinue

    for ($i = 0; $i -lt 120 -and -not $Proceso.HasExited; $i++) {
        Start-Sleep -Seconds 1
        if (-not (Test-Path $log)) { continue }
        $texto = Get-Content -Raw -Path $log -ErrorAction SilentlyContinue
        $m = [regex]::Matches([string]$texto, 'https://([a-z0-9-]+\.trycloudflare\.com)') |
            Where-Object { $_.Groups[1].Value -ne 'api.trycloudflare.com' } | Select-Object -First 1
        if ($m) {
            $dominio = $m.Groups[1].Value
            Write-Utf8SinBom -Ruta $archivo -Texto $dominio
            Write-Utf8SinBom -Ruta (Join-Path $base 'enlace.txt') -Texto (New-Enlace $Estado $dominio)
            Send-Notificacion $Estado "Nuevo enlace de $($script:NombreApp) (copialo en v2rayN / v2rayNG):`n$(New-Enlace $Estado $dominio)"
            return
        }
    }
}

while ($true) {
    $estado = Get-Estado
    if (-not $estado) { Start-Sleep -Seconds 30; continue }

    if ($Componente -eq 'xray') {
        $proc = Iniciar-Xray
    } else {
        $proc = Iniciar-Tunel $estado
        if ($estado.Modo -eq 'rapido') { Esperar-DominioRapido $proc $estado }
    }

    $proc.WaitForExit()
    Start-Sleep -Seconds 10
}
