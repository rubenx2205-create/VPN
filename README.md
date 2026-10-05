# VPN

Herramienta para convertir un PC con Windows (por ejemplo, el de casa) en un
servidor de túnel propio y salir a Internet a través de él desde cualquier red,
incluidas las redes con portal cautivo o con filtros.

```
Tu PC/móvil ══HTTPS 443══► Cloudflare ◄══túnel saliente══ PC de casa (cloudflared → Xray) ──► Internet
```

- **No hace falta abrir puertos en el router**: el PC de casa es quien se conecta
  a Cloudflare, así que funciona aunque el proveedor use CGNAT.
- **Por fuera parece tráfico HTTPS normal** hacia Cloudflare (puerto 443), algo que
  las redes casi nunca bloquean.
- Protocolo: Xray **VLESS sobre WebSocket**, con TLS gestionado por Cloudflare.
- Por defecto **bloquea el acceso a la red local de casa** (router, otros equipos):
  el túnel solo sirve para salir a Internet.

## Instalación en el PC de casa (Windows 10/11)

1. Descarga la carpeta [`servidor/`](servidor/) a ese PC.
2. Elige un modo:

### Modo rápido (gratis, sin dominio)

Clic derecho sobre `instalar.bat` → **Ejecutar como administrador**.

- Cloudflare asigna un dominio temporal `*.trycloudflare.com`.
- **El dominio cambia cada vez que se reinicia el PC o el túnel.** Para enterarte
  del nuevo enlace, la instalación crea un tema de [ntfy](https://ntfy.sh)
  y te lo muestra: instala la app ntfy en el móvil, suscríbete a ese tema y te
  llegará cada enlace nuevo.
- Los túneles rápidos no tienen garantías de Cloudflare: sirven para probar o
  para un uso ligero.

### Modo nombrado (estable, recomendado)

Necesitas una cuenta gratuita de Cloudflare y un dominio gestionado en ella
(los hay desde 1–10 €/año).

1. En el panel de Cloudflare: **Zero Trust → Networks → Tunnels → Create a tunnel**
   (tipo *Cloudflared*). Copia el **token** que aparece en el comando de instalación.
2. En el túnel, añade un **Public Hostname**, por ejemplo `vpn.tudominio.com`,
   con servicio **HTTP** → `localhost:10086`.
3. En el PC de casa, abre PowerShell como administrador dentro de la carpeta `servidor`:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\vpn-servidor.ps1 instalar -Token "eyJh..." -Dominio vpn.tudominio.com
   ```

   El enlace será siempre el mismo.

### Qué hace el instalador

- Descarga **Xray** (verificando su SHA-256) y **cloudflared** (verificando la
  firma digital de Cloudflare) desde sus releases oficiales de GitHub.
- Genera una clave (UUID) y una ruta WebSocket aleatorias.
- Lo instala todo en `C:\ProgramData\VpnCasa`, legible solo por administradores.
- Crea dos tareas programadas (`VpnCasa-xray`, `VpnCasa-tunel`) que arrancan con
  Windows como SYSTEM, sin necesidad de iniciar sesión, y se reinician si fallan.
- Desactiva la suspensión y la hibernación con el PC enchufado (`-NoTocarEnergia`
  para no tocarlas). Conviene además activar en la BIOS *Restore on AC power loss*.

## Uso del cliente

Muestra el enlace en el PC de casa:

```powershell
powershell -ExecutionPolicy Bypass -File C:\ProgramData\VpnCasa\vpn-servidor.ps1 enlace
```

Importa ese enlace `vless://...` en:

| Dispositivo | App |
|---|---|
| Windows | [v2rayN](https://github.com/2dust/v2rayN): *Servidores → Importar desde portapapeles*, luego *Proxy del sistema → Establecer* |
| Android | v2rayNG |
| iOS / macOS | Streisand, V2Box |

En una red con portal cautivo: **primero inicia sesión en el portal** con tu
usuario y contraseña, y después activa el cliente.

## Comandos

```
vpn-servidor.ps1 instalar     [-Token T -Dominio D] [-Puerto 10086] [-Notificar] [-PermitirLAN] [-NoTocarEnergia]
vpn-servidor.ps1 estado       # tareas, si Xray responde, dominio actual, últimas líneas del túnel
vpn-servidor.ps1 enlace       # muestra y copia el enlace
vpn-servidor.ps1 reiniciar
vpn-servidor.ps1 rotar        # nueva clave: el enlace anterior deja de funcionar
vpn-servidor.ps1 desinstalar
```

`-PermitirLAN` deja acceder a la red de casa a través del túnel (por ejemplo, a
una impresora o a un NAS).

## A tener en cuenta

- **El enlace es la contraseña.** Quien lo tenga puede usar la conexión de tu casa.
  Si se filtra, ejecuta `rotar`.
- Todo lo que hagas sale con la IP de casa: hazlo con permiso de quien paga la conexión.
- La velocidad máxima es la de **subida** de la conexión de casa.
- Cloudflare termina el TLS en sus servidores y VLESS no añade cifrado propio:
  Cloudflare podría ver el tráfico que no vaya por HTTPS. Esto te protege frente a
  la red en la que estás, no frente a Cloudflare.
- Revisa las normas de uso de la red desde la que te conectas: algunas prohíben las VPN.
