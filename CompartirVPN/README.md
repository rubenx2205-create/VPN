# Compartir VPN

App complementaria para Android que **comparte la conexión del VPN de Psiphon** (u otro VPN) con
los dispositivos conectados al punto de acceso del teléfono. No modifica Psiphon: funciona a su lado.

Al activarla, se queda esperando; **cada vez que Psiphon conecta, empieza a compartir sola**, y
cuando se desconecta deja de hacerlo.

## Dos modos

| | Root (transparente) | Sin root (proxy) |
|---|---|---|
| Configuración en los clientes | **Ninguna**: solo conectarse al punto de acceso | Un ajuste único: proxy automático (PAC) |
| Qué pasa por el VPN | Todo el tráfico IPv4 (TCP, UDP, DNS) de todas las apps | Lo que respete el proxy del sistema (navegadores y la mayoría de apps) |
| Si el VPN se cae | Kill switch: los clientes se quedan sin internet (no se filtra la IP real) | El proxy rechaza las conexiones |
| Requisito | Magisk / KernelSU | Ninguno |

El modo **Automático** usa root si está disponible y, si no, el proxy.

> ¿Por qué hace falta root para el modo transparente? Android envía el tráfico del punto de acceso
> directamente por la red móvil o la Wi‑Fi, nunca por el VPN, y sin root ninguna app puede
> cambiar esas rutas del sistema.

## Uso

1. Instala el APK (ver más abajo) y ábrelo.
2. En Psiphon, **no excluyas esta app** (si usas "excluir apps" o "solo estas apps").
3. Activa el punto de acceso Wi‑Fi (o anclaje USB / Bluetooth).
4. Activa **Compartir el VPN**. Opcional: "Iniciar al encender el teléfono".

**Modo root**: concede el permiso de superusuario cuando lo pida. Ya está: los dispositivos que se
conecten al punto de acceso navegan por el VPN.

**Modo sin root**: en cada dispositivo cliente, una sola vez:
- Android: Ajustes → Wi‑Fi → mantener pulsada la red del punto de acceso → Modificar → Opciones
  avanzadas → Proxy → **Configuración automática** → `http://192.168.43.1:8080/proxy.pac`
  (la app muestra la dirección exacta).
- iPhone/iPad: Ajustes → Wi‑Fi → (i) → Configurar proxy → **Automático** → misma URL.
- Windows: Configuración → Red → Proxy → Usar script de configuración → misma URL.
- O proxy manual: servidor = IP del teléfono, puerto = 8080 (HTTP y SOCKS5 en el mismo puerto).

## Cómo funciona (modo root)

Cuando detecta el túnel del VPN (`tun0`) y una interfaz compartida (`ap0`, `swlan0`, `rndis0`,
`bt-pan`, `p2p-*`...), aplica:

```
ip rule add iif ap0 lookup 7777            # tráfico de los clientes → tabla 7777
ip route add default dev tun0 table 7777    # ...que sale por el túnel
ip rule add iif tun0 lookup 7778            # respuestas → de vuelta a los clientes
iptables -t nat -A POSTROUTING -o tun0 -j MASQUERADE
iptables -t nat -A PREROUTING -i ap0 -p udp --dport 53 -j DNAT --to <DNS del VPN>
ip6tables -A FORWARD -i ap0 -j REJECT       # el túnel es solo IPv4: sin fugas por IPv6
+ kill switch (unreachable + REJECT) y ajuste de MSS
```

Todas las reglas van en cadenas propias (`vpnshare_*`) y se eliminan al detener la app. También
desactiva la aceleración por hardware del anclaje (`tether_offload_disabled`), que puede saltarse
iptables, y la restaura al terminar. Si los clientes no navegan, apaga y enciende el punto de acceso
una vez con la app activa.

Esta lógica se probó en una simulación con namespaces de red de Linux (cliente → teléfono → VPN /
red normal): el tráfico y el DNS salen por el VPN, con el VPN caído no se filtra nada, y al detener
la app vuelve la compartición normal de Android sin dejar reglas.

## Compilar

Requiere JDK 17 y el SDK de Android (API 35).

```
cd CompartirVPN
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions compila el APK en cada cambio (artefacto `CompartirVPN-debug-apk`).

## Limitaciones

- Modo root: probado en simulación, no en todos los fabricantes. Algunas ROMs usan nombres de
  interfaz poco comunes; si no detecta tu punto de acceso, abre un issue con la salida de
  `ip -o -4 addr show`.
- Modo sin root: el UDP (juegos, llamadas de voz) y las apps que ignoran el proxy del sistema no
  pasan por el VPN.
- Mantiene un *wake lock* parcial mientras está activa para que no se corte con la pantalla apagada.
