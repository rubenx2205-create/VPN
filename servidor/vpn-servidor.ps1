<#
.SYNOPSIS
    Convierte un PC con Windows en servidor de túnel (Xray VLESS + WebSocket)
    accesible a través de Cloudflare Tunnel. No hace falta abrir puertos, así
    que funciona aunque el proveedor de Internet use CGNAT.

.EXAMPLE
    .\vpn-servidor.ps1 instalar -Notificar
    Modo rápido: dominio *.trycloudflare.com gratuito; cambia en cada reinicio
    y el nuevo enlace llega al móvil por ntfy.

.EXAMPLE
    .\vpn-servidor.ps1 instalar -Token eyJh... -Dominio vpn.midominio.com
    Modo nombrado: dominio propio en Cloudflare, enlace fijo.
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('instalar', 'estado', 'enlace', 'reiniciar', 'rotar', 'desinstalar', 'ayuda')]
    [string]$Comando = 'ayuda',
    [string]$Token,
    [string]$Dominio,
    [ValidateRange(1024, 65535)][int]$Puerto = 10086,
    [switch]$Notificar,
    [switch]$PermitirLAN,
    [switch]$NoTocarEnergia,
    [switch]$Elevado
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
. (Join-Path $PSScriptRoot 'comun.ps1')

$base = Get-RutaBase
$tareas = @('xray', 'tunel')
$parametros = $PSBoundParameters

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Invoke-ComoAdmin {
    $argumentos = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", $Comando, '-Elevado')
    foreach ($p in $parametros.GetEnumerator()) {
        if ($p.Key -eq 'Comando') { continue }
        if ($p.Value -is [switch]) { if ($p.Value) { $argumentos += "-$($p.Key)" } }
        else { $argumentos += "-$($p.Key)"; $argumentos += "`"$($p.Value)`"" }
    }
    Start-Process -FilePath 'powershell.exe' -ArgumentList $argumentos -Verb RunAs
}

function Write-Paso { param([string]$Texto) Write-Host "==> $Texto" -ForegroundColor Cyan }

function Get-Archivo {
    param([string]$Url, [string]$Destino)
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destino
}

function Install-Xray {
    $nombre = switch ($env:PROCESSOR_ARCHITECTURE) {
        'ARM64' { 'Xray-windows-arm64-v8a.zip' }
        'x86'   { 'Xray-windows-32.zip' }
        default { 'Xray-windows-64.zip' }
    }
    $url = "https://github.com/XTLS/Xray-core/releases/latest/download/$nombre"
    $tmp = Join-Path $env:TEMP "vpncasa-$(New-TextoAleatorio 4)"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        $zip = Join-Path $tmp $nombre
        Get-Archivo $url $zip
        Get-Archivo "$url.dgst" "$zip.dgst"

        $esperado = [regex]::Match((Get-Content -Raw "$zip.dgst"), 'SHA2-256(?:\([^)]*\))?=\s*([0-9a-fA-F]{64})').Groups[1].Value
        $real = (Get-FileHash -Algorithm SHA256 $zip).Hash
        if (-not $esperado -or $esperado -ne $real) { throw "La suma SHA-256 de Xray no coincide (esperado '$esperado', obtenido '$real')." }

        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        Copy-Item (Join-Path $tmp 'xray.exe') (Join-Path $base 'bin\xray.exe') -Force
    } finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Install-Cloudflared {
    # Cloudflare no publica build ARM64 para Windows; la de x64 funciona emulada.
    $nombre = if ($env:PROCESSOR_ARCHITECTURE -eq 'x86') { 'cloudflared-windows-386.exe' } else { 'cloudflared-windows-amd64.exe' }
    $destino = Join-Path $base 'bin\cloudflared.exe'
    Get-Archivo "https://github.com/cloudflare/cloudflared/releases/latest/download/$nombre" $destino

    $firma = Get-AuthenticodeSignature $destino
    if ($firma.Status -ne 'Valid' -or $firma.SignerCertificate.Subject -notmatch 'Cloudflare') {
        Remove-Item $destino -Force
        throw "cloudflared.exe no tiene una firma valida de Cloudflare ($($firma.Status))."
    }
}

function Set-PermisosPrivados {
    # estado.json guarda la clave del túnel: solo Administradores y SYSTEM pueden leerla.
    & icacls.exe $base /inheritance:r /grant:r '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-18:(OI)(CI)F' | Out-Null
}

function Stop-Componentes {
    foreach ($t in $tareas) {
        Stop-ScheduledTask -TaskName "$($script:NombreApp)-$t" -ErrorAction SilentlyContinue
    }
    Get-Process -Name 'xray', 'cloudflared' -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($base, [StringComparison]::OrdinalIgnoreCase) } |
        Stop-Process -Force
    # Evita leer el dominio temporal anterior mientras el tunel vuelve a arrancar.
    Remove-Item (Join-Path $base 'dominio-rapido.txt') -Force -ErrorAction SilentlyContinue
}

function Start-Componentes {
    foreach ($t in $tareas) { Start-ScheduledTask -TaskName "$($script:NombreApp)-$t" }
}

function Register-Tareas {
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $disparador = New-ScheduledTaskTrigger -AtStartup
    $ajustes = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
    foreach ($t in $tareas) {
        $accion = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument (
            "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$(Join-Path $base 'supervisor.ps1')`" -Componente $t")
        Register-ScheduledTask -TaskName "$($script:NombreApp)-$t" -Action $accion -Trigger $disparador `
            -Principal $principal -Settings $ajustes -Force | Out-Null
    }
}

function Write-Config {
    param($Estado)
    Write-Utf8SinBom -Ruta (Join-Path $base 'config.json') -Texto (New-ConfigXray $Estado)
    # En PowerShell 5.1, con 'Stop', cualquier linea en stderr de un ejecutable lanzaria error.
    $ErrorActionPreference = 'Continue'
    $prueba = & (Join-Path $base 'bin\xray.exe') run -test -c (Join-Path $base 'config.json') 2>&1
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Xray rechazo la configuracion:`n$prueba" }
}

function Wait-Enlace {
    param($Estado)
    if ($Estado.Modo -eq 'nombrado') {
        Write-Utf8SinBom -Ruta (Join-Path $base 'enlace.txt') -Texto (New-Enlace $Estado $Estado.Dominio)
        return
    }
    Write-Paso 'Esperando a que Cloudflare asigne el dominio temporal (hasta 2 minutos)...'
    for ($i = 0; $i -lt 120; $i++) {
        if (Get-DominioActual $Estado) { return }
        Start-Sleep -Seconds 1
    }
    Write-Warning "Cloudflare aun no ha asignado dominio. Revisa $base\logs\tunel.log y prueba 'estado' en un rato."
}

function Show-Enlace {
    $estado = Get-Estado
    $enlace = New-Enlace $estado (Get-DominioActual $estado)
    if (-not $enlace) { Write-Warning 'Todavia no hay enlace (el tunel no ha arrancado).'; return }
    Write-Host ''
    Write-Host 'Enlace para el cliente (v2rayN en Windows, v2rayNG en Android, Streisand/V2Box en iOS):' -ForegroundColor Green
    Write-Host $enlace
    Write-Host ''
    try { Set-Clipboard -Value $enlace; Write-Host '(Copiado al portapapeles)' } catch { }
    if ($estado.TemaNtfy) { Write-Host "Notificaciones: suscribete al tema '$($estado.TemaNtfy)' en la app ntfy." }
    if ($estado.Modo -eq 'rapido') { Write-Host 'Modo rapido: el enlace cambia cada vez que se reinicia el PC o el tunel.' -ForegroundColor Yellow }
}

function Invoke-Instalar {
    if ($Token -and -not $Dominio) { throw 'Con -Token tambien hay que indicar -Dominio (el nombre publico configurado en Cloudflare).' }

    $anterior = Get-Estado
    Write-Paso "Preparando $base"
    if ($anterior) { Stop-Componentes }
    New-Item -ItemType Directory -Force -Path (Join-Path $base 'bin'), (Join-Path $base 'logs') | Out-Null
    Set-PermisosPrivados
    foreach ($f in 'comun.ps1', 'supervisor.ps1', 'vpn-servidor.ps1') {
        $origen = Join-Path $PSScriptRoot $f
        $destino = Join-Path $base $f
        if ($origen -ne $destino) { Copy-Item $origen $destino -Force }
    }

    $tema = $null
    if ($anterior -and $anterior.TemaNtfy) { $tema = $anterior.TemaNtfy }
    if ($Notificar -and -not $tema) { $tema = "vpncasa-$(New-TextoAleatorio 12)" }

    $estado = [pscustomobject][ordered]@{
        Modo        = if ($Token) { 'nombrado' } else { 'rapido' }
        Puerto      = $Puerto
        Uuid        = if ($anterior) { $anterior.Uuid } else { [guid]::NewGuid().ToString() }
        Ruta        = if ($anterior) { $anterior.Ruta } else { "/$(New-TextoAleatorio 8)" }
        Token       = $Token
        Dominio     = $Dominio
        TemaNtfy    = $tema
        PermitirLAN = [bool]$PermitirLAN
    }
    Save-Estado $estado

    Write-Paso 'Descargando Xray (verificando SHA-256)'
    Install-Xray
    Write-Paso 'Descargando cloudflared (verificando firma de Cloudflare)'
    Install-Cloudflared

    Write-Paso 'Generando configuracion'
    Write-Config $estado

    Write-Paso 'Registrando tareas de inicio automatico (se ejecutan como SYSTEM al encender el PC)'
    Register-Tareas

    if (-not $NoTocarEnergia) {
        Write-Paso 'Desactivando suspension e hibernacion con el PC enchufado'
        & powercfg.exe /change standby-timeout-ac 0
        & powercfg.exe /change hibernate-timeout-ac 0
    }

    Write-Paso 'Arrancando'
    Start-Componentes
    Wait-Enlace $estado
    Show-Enlace

    if ($estado.Modo -eq 'nombrado') {
        Write-Host ''
        Write-Host "Recuerda: en Cloudflare, el Public Hostname '$Dominio' debe apuntar a http://localhost:$Puerto" -ForegroundColor Yellow
    }
}

function Show-Estado {
    $estado = Get-Estado
    if (-not $estado) { Write-Host 'No esta instalado.'; return }
    Write-Host "Modo:       $($estado.Modo)"
    Write-Host "Puerto:     127.0.0.1:$($estado.Puerto)"
    Write-Host "Red local:  $(if ($estado.PermitirLAN) { 'permitida' } else { 'bloqueada' })"
    foreach ($t in $tareas) {
        $tarea = Get-ScheduledTask -TaskName "$($script:NombreApp)-$t" -ErrorAction SilentlyContinue
        $txt = if ($tarea) { $tarea.State } else { 'no registrada' }
        Write-Host ("Tarea {0,-6}{1}" -f "${t}:", $txt)
    }
    $escucha = $false
    try {
        $c = New-Object Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', [int]$estado.Puerto); $escucha = $c.Connected; $c.Close()
    } catch { }
    Write-Host "Xray:       $(if ($escucha) { 'escuchando' } else { 'NO responde' })"
    Write-Host "Dominio:    $(Get-DominioActual $estado)"
    $log = Join-Path $base 'logs\tunel.log'
    if (Test-Path $log) {
        Write-Host ''
        Write-Host 'Ultimas lineas del tunel:'
        Get-Content $log -Tail 5
    }
}

function Invoke-Rotar {
    $estado = Get-Estado
    if (-not $estado) { throw 'No esta instalado.' }
    $estado.Uuid = [guid]::NewGuid().ToString()
    $estado.Ruta = "/$(New-TextoAleatorio 8)"
    Save-Estado $estado
    Write-Config $estado
    Stop-Componentes
    Start-Componentes
    Wait-Enlace $estado
    if ($estado.Modo -eq 'nombrado') { Send-Notificacion $estado "Nuevo enlace de $($script:NombreApp):`n$(New-Enlace $estado $estado.Dominio)" }
    Write-Host 'Clave cambiada: el enlace anterior ya no funciona.' -ForegroundColor Green
    Show-Enlace
}

function Invoke-Desinstalar {
    Stop-Componentes
    foreach ($t in $tareas) {
        Unregister-ScheduledTask -TaskName "$($script:NombreApp)-$t" -Confirm:$false -ErrorAction SilentlyContinue
    }
    Set-Location $env:TEMP
    Remove-Item $base -Recurse -Force
    Write-Host "Desinstalado. (Los ajustes de energia no se han revertido; cambialos en Configuracion > Sistema > Energia si quieres.)"
}

if ($Comando -eq 'ayuda') { Get-Help $PSCommandPath -Detailed; return }

if (-not (Test-Admin)) {
    Write-Host 'Se necesitan permisos de administrador; abriendo ventana elevada...'
    Invoke-ComoAdmin
    return
}

try {
    switch ($Comando) {
        'instalar'    { Invoke-Instalar }
        'estado'      { Show-Estado }
        'enlace'      { Show-Enlace }
        'reiniciar'   { Stop-Componentes; Start-Componentes; Wait-Enlace (Get-Estado); Show-Enlace }
        'rotar'       { Invoke-Rotar }
        'desinstalar' { Invoke-Desinstalar }
    }
} catch {
    Write-Host "ERROR: $_" -ForegroundColor Red
    if ($Elevado) { Read-Host 'Pulsa Enter para cerrar' }
    exit 1
}
if ($Elevado) { Read-Host 'Pulsa Enter para cerrar' }
