# Funciones compartidas por vpn-servidor.ps1 y supervisor.ps1.
# Compatible con Windows PowerShell 5.1 y PowerShell 7.

$script:NombreApp = 'VpnCasa'

function Get-RutaBase {
    if ($env:VPNCASA_HOME) { return $env:VPNCASA_HOME }
    return Join-Path $env:ProgramData $script:NombreApp
}

function Write-Utf8SinBom {
    param([string]$Ruta, [string]$Texto)
    # Windows PowerShell 5.1 escribe BOM con -Encoding utf8; Xray espera JSON limpio.
    [IO.File]::WriteAllText($Ruta, $Texto, (New-Object Text.UTF8Encoding $false))
}

function Get-Estado {
    $ruta = Join-Path (Get-RutaBase) 'estado.json'
    if (-not (Test-Path $ruta)) { return $null }
    return Get-Content -Raw -Path $ruta | ConvertFrom-Json
}

function Save-Estado {
    param($Estado)
    Write-Utf8SinBom -Ruta (Join-Path (Get-RutaBase) 'estado.json') -Texto ($Estado | ConvertTo-Json -Depth 5)
}

function New-TextoAleatorio {
    param([int]$Bytes = 8)
    $buf = New-Object byte[] $Bytes
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buf)
    $rng.Dispose()
    return (($buf | ForEach-Object { $_.ToString('x2') }) -join '')
}

# Rangos privados y locales: se bloquean para que quien use el túnel
# no pueda entrar a la red de casa (router, otros equipos, etc.).
$script:RangosPrivados = @(
    '0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8', '169.254.0.0/16',
    '172.16.0.0/12', '192.168.0.0/16', '224.0.0.0/4', '::1/128', 'fc00::/7', 'fe80::/10'
)

function New-ConfigXray {
    param($Estado)

    $reglas = @()
    if (-not $Estado.PermitirLAN) {
        $reglas += [ordered]@{ type = 'field'; ip = $script:RangosPrivados; outboundTag = 'bloqueado' }
    }

    $config = [ordered]@{
        log       = [ordered]@{ loglevel = 'warning'; access = 'none' }
        inbounds  = @(
            [ordered]@{
                tag            = 'entrada'
                listen         = '127.0.0.1'
                port           = [int]$Estado.Puerto
                protocol       = 'vless'
                settings       = [ordered]@{
                    clients    = @([ordered]@{ id = $Estado.Uuid })
                    decryption = 'none'
                }
                streamSettings = [ordered]@{
                    network    = 'ws'
                    wsSettings = [ordered]@{ path = $Estado.Ruta }
                }
            }
        )
        outbounds = @(
            [ordered]@{ tag = 'directo'; protocol = 'freedom' }
            [ordered]@{ tag = 'bloqueado'; protocol = 'blackhole' }
        )
        routing   = [ordered]@{
            domainStrategy = 'IPIfNonMatch'
            rules          = $reglas
        }
    }
    return ($config | ConvertTo-Json -Depth 10)
}

function New-Enlace {
    param($Estado, [string]$Dominio)
    if (-not $Dominio) { return $null }
    $ruta = [Uri]::EscapeDataString($Estado.Ruta)
    return "vless://$($Estado.Uuid)@${Dominio}:443?encryption=none&security=tls&sni=$Dominio&fp=chrome&type=ws&host=$Dominio&path=$ruta#$($script:NombreApp)"
}

function Get-DominioActual {
    param($Estado)
    if ($Estado.Modo -eq 'nombrado') { return $Estado.Dominio }
    $archivo = Join-Path (Get-RutaBase) 'dominio-rapido.txt'
    if (Test-Path $archivo) { return (Get-Content -Raw $archivo).Trim() }
    return $null
}

function Send-Notificacion {
    param($Estado, [string]$Mensaje)
    if (-not $Estado.TemaNtfy) { return }
    try {
        Invoke-RestMethod -Method Post -Uri "https://ntfy.sh/$($Estado.TemaNtfy)" `
            -Body ([Text.Encoding]::UTF8.GetBytes($Mensaje)) -Headers @{ Title = $script:NombreApp } | Out-Null
    } catch {
        Write-Warning "No se pudo enviar la notificacion: $_"
    }
}
